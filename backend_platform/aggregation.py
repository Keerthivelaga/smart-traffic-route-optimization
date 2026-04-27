from __future__ import annotations

import logging
from collections import defaultdict, deque
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import numpy as np
from sklearn.cluster import DBSCAN
from sklearn.ensemble import IsolationForest


LOGGER = logging.getLogger("aggregation")


@dataclass
class AggregatedStats:
    segment_id: str
    window_start: datetime
    window_end: datetime
    congestion_score: float
    confidence: float
    avg_speed_kph: float
    p95_travel_time_s: float
    anomaly_score: float
    speed_distribution: dict[str, float]


class Kalman1D:
    def __init__(self, process_var: float = 1.0, measure_var: float = 4.0):
        self.process_var = process_var
        self.measure_var = measure_var
        self.posteri_est = None
        self.posteri_err_est = 1.0

    def filter(self, measurement: float) -> float:
        if self.posteri_est is None:
            self.posteri_est = measurement
            return measurement

        priori_est = self.posteri_est
        priori_err_est = self.posteri_err_est + self.process_var

        gain = priori_err_est / (priori_err_est + self.measure_var)
        self.posteri_est = priori_est + gain * (measurement - priori_est)
        self.posteri_err_est = (1 - gain) * priori_err_est
        return self.posteri_est


class BayesianCongestionModel:
    def __init__(self) -> None:
        self.alpha: dict[str, float] = defaultdict(lambda: 2.0)
        self.beta: dict[str, float] = defaultdict(lambda: 2.0)

    def update(self, segment_id: str, slow_events: int, normal_events: int) -> float:
        self.alpha[segment_id] += slow_events
        self.beta[segment_id] += normal_events
        total = self.alpha[segment_id] + self.beta[segment_id]
        return float(self.alpha[segment_id] / max(total, 1e-9))


class TrafficAggregationEngine:
    def __init__(self, window_minutes: int = 5) -> None:
        self.window = timedelta(minutes=window_minutes)
        self.buffers: dict[str, deque[dict]] = defaultdict(deque)
        self.kalman: dict[str, Kalman1D] = defaultdict(Kalman1D)
        self.outlier_model = IsolationForest(contamination=0.03, random_state=42)
        self.bayes = BayesianCongestionModel()
        self.last_score: dict[str, float] = defaultdict(lambda: 0.4)

    def add_sample(self, sample: dict) -> None:
        segment_id = sample["segment_id"]
        timestamp = self._parse_dt(sample["timestamp"])
        self.buffers[segment_id].append({**sample, "parsed_ts": timestamp})
        self._trim_window(segment_id)

    def aggregate(self, segment_id: str, speed_limit: float = 60.0) -> AggregatedStats | None:
        rows = list(self.buffers.get(segment_id, []))
        if len(rows) < 8:
            return None

        speeds = np.array([r["speed_kph"] for r in rows], dtype=float)
        coords = np.array([[r["latitude"], r["longitude"]] for r in rows], dtype=float)

        clustering = DBSCAN(eps=0.0015, min_samples=4).fit(coords)
        major_cluster = self._major_cluster_mask(clustering.labels_)
        cluster_speeds = speeds[major_cluster] if major_cluster.any() else speeds

        smoothed = np.array([self.kalman[segment_id].filter(v) for v in cluster_speeds], dtype=float)
        cleaned = self._reject_outliers(smoothed)
        if cleaned.size == 0:
            cleaned = smoothed

        anomaly_score = self._anomaly_score(cleaned)
        avg_speed = float(np.mean(cleaned))
        speed_ratio = np.clip(avg_speed / max(speed_limit, 1), 0.0, 1.5)

        slow_events = int((cleaned < speed_limit * 0.55).sum())
        normal_events = int(cleaned.size - slow_events)
        bayesian_prob = self.bayes.update(segment_id, slow_events=slow_events, normal_events=normal_events)

        # Probabilistic congestion score combining speed signal, Bayesian posterior, and anomaly mass.
        speed_component = 1.0 - np.clip(speed_ratio, 0.0, 1.0)
        raw_score = np.clip(speed_component * 0.55 + bayesian_prob * 0.30 + anomaly_score * 0.15, 0.0, 1.0)
        congestion_score = 0.70 * self.last_score[segment_id] + 0.30 * raw_score
        self.last_score[segment_id] = float(congestion_score)

        dist = {
            "p10": float(np.percentile(cleaned, 10)),
            "p50": float(np.percentile(cleaned, 50)),
            "p90": float(np.percentile(cleaned, 90)),
            "std": float(np.std(cleaned)),
        }
        p95_travel_time = self._travel_time_proxy(cleaned, segment_length_m=500)
        confidence = float(min(1.0, len(cleaned) / 120))

        window_end = max(r["parsed_ts"] for r in rows)
        window_start = window_end - self.window
        return AggregatedStats(
            segment_id=segment_id,
            window_start=window_start,
            window_end=window_end,
            congestion_score=float(np.clip(congestion_score, 0.0, 1.0)),
            confidence=confidence,
            avg_speed_kph=avg_speed,
            p95_travel_time_s=p95_travel_time,
            anomaly_score=anomaly_score,
            speed_distribution=dist,
        )

    def _trim_window(self, segment_id: str) -> None:
        cutoff = datetime.now(timezone.utc) - self.window
        q = self.buffers[segment_id]
        while q and q[0]["parsed_ts"] < cutoff:
            q.popleft()

    def _major_cluster_mask(self, labels: np.ndarray) -> np.ndarray:
        valid = labels[labels >= 0]
        if valid.size == 0:
            return np.ones_like(labels, dtype=bool)
        unique, counts = np.unique(valid, return_counts=True)
        major = unique[np.argmax(counts)]
        return labels == major

    def _reject_outliers(self, values: np.ndarray) -> np.ndarray:
        if values.size < 8:
            return values
        q1, q3 = np.percentile(values, [25, 75])
        iqr = q3 - q1
        lower, upper = q1 - 1.5 * iqr, q3 + 1.5 * iqr
        return values[(values >= lower) & (values <= upper)]

    def _anomaly_score(self, values: np.ndarray) -> float:
        if values.size < 20:
            return 0.1
        data = values.reshape(-1, 1)
        preds = self.outlier_model.fit_predict(data)
        frac = (preds == -1).sum() / len(preds)
        return float(np.clip(frac, 0.0, 1.0))

    @staticmethod
    def _travel_time_proxy(speeds: np.ndarray, segment_length_m: float) -> float:
        speeds_ms = np.clip(speeds / 3.6, 0.1, None)
        times = segment_length_m / speeds_ms
        return float(np.percentile(times, 95))

    @staticmethod
    def _parse_dt(value: str | datetime) -> datetime:
        if isinstance(value, datetime):
            return value.astimezone(timezone.utc)
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)
