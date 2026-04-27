from __future__ import annotations

import logging
import math
import threading
import time
from collections import defaultdict, deque
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from typing import Callable, Protocol

import numpy as np
from sklearn.cluster import DBSCAN
from sklearn.ensemble import IsolationForest


LOGGER = logging.getLogger("congestion_engine")


@dataclass
class ProcessedGpsPoint:
    timestamp_s: float
    latitude: float
    longitude: float
    speed_kph: float
    heading_deg: float
    accuracy_m: float


@dataclass
class CongestionMetrics:
    congestion_probability: float
    confidence_score: float
    severity_index: float
    components: dict[str, float] = field(default_factory=dict)


@dataclass
class EngineRuntimeMetrics:
    processed_batches: int = 0
    processed_segments: int = 0
    cache_hits: int = 0
    failures: int = 0
    total_latency_ms: float = 0.0

    def observe_latency(self, latency_ms: float) -> None:
        self.total_latency_ms += latency_ms

    @property
    def avg_latency_ms(self) -> float:
        if self.processed_segments <= 0:
            return 0.0
        return self.total_latency_ms / self.processed_segments


class SegmentInputProcessor(Protocol):
    def process(self, samples: list[dict]) -> list[ProcessedGpsPoint]:
        raise NotImplementedError


class SignalProcessor(Protocol):
    def process(self, values: np.ndarray) -> np.ndarray:
        raise NotImplementedError


class CongestionPredictor(Protocol):
    def predict(self, speeds: np.ndarray) -> float:
        raise NotImplementedError


class GpsInputProcessor:
    def __init__(self, max_speed_kph: float = 220.0, max_accuracy_m: float = 200.0) -> None:
        self.max_speed_kph = max_speed_kph
        self.max_accuracy_m = max_accuracy_m

    def process(self, samples: list[dict]) -> list[ProcessedGpsPoint]:
        out: list[ProcessedGpsPoint] = []
        for sample in samples:
            if not self._gps_valid(sample):
                continue
            speed = float(sample.get("speed_kph", 0.0))
            if not self._speed_valid(speed):
                continue
            ts = self._timestamp_seconds(sample.get("timestamp"))
            if ts is None:
                continue
            out.append(
                ProcessedGpsPoint(
                    timestamp_s=ts,
                    latitude=float(sample["latitude"]),
                    longitude=float(sample["longitude"]),
                    speed_kph=speed,
                    heading_deg=float(sample.get("heading_deg", 0.0)),
                    accuracy_m=float(sample.get("accuracy_m", 10.0)),
                )
            )
        if not out:
            return out
        out.sort(key=lambda p: p.timestamp_s)
        snapped = self._coordinate_snap(out)
        return self._noise_filter(snapped)

    def _gps_valid(self, sample: dict) -> bool:
        lat = sample.get("latitude")
        lon = sample.get("longitude")
        if lat is None or lon is None:
            return False
        lat_f = float(lat)
        lon_f = float(lon)
        if lat_f < -90 or lat_f > 90:
            return False
        if lon_f < -180 or lon_f > 180:
            return False
        acc = float(sample.get("accuracy_m", 10.0))
        if acc < 0 or acc > self.max_accuracy_m:
            return False
        return True

    def _speed_valid(self, speed_kph: float) -> bool:
        return 0.0 <= speed_kph <= self.max_speed_kph

    @staticmethod
    def _timestamp_seconds(raw: object) -> float | None:
        if raw is None:
            return None
        text = str(raw)
        if text.isdigit():
            return float(text)
        try:
            from datetime import datetime

            return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()
        except Exception:  # noqa: BLE001
            return None

    @staticmethod
    def _coordinate_snap(points: list[ProcessedGpsPoint]) -> list[ProcessedGpsPoint]:
        snapped: list[ProcessedGpsPoint] = []
        for point in points:
            snapped.append(
                ProcessedGpsPoint(
                    timestamp_s=point.timestamp_s,
                    latitude=round(point.latitude, 6),
                    longitude=round(point.longitude, 6),
                    speed_kph=point.speed_kph,
                    heading_deg=point.heading_deg,
                    accuracy_m=point.accuracy_m,
                )
            )
        return snapped

    @staticmethod
    def _noise_filter(points: list[ProcessedGpsPoint]) -> list[ProcessedGpsPoint]:
        if len(points) <= 2:
            return points
        filtered = [points[0]]
        for idx in range(1, len(points) - 1):
            prev = points[idx - 1]
            curr = points[idx]
            nxt = points[idx + 1]
            median_speed = float(np.median([prev.speed_kph, curr.speed_kph, nxt.speed_kph]))
            if abs(curr.speed_kph - median_speed) > 50:
                filtered.append(
                    ProcessedGpsPoint(
                        timestamp_s=curr.timestamp_s,
                        latitude=curr.latitude,
                        longitude=curr.longitude,
                        speed_kph=median_speed,
                        heading_deg=curr.heading_deg,
                        accuracy_m=curr.accuracy_m,
                    )
                )
            else:
                filtered.append(curr)
        filtered.append(points[-1])
        return filtered


class KalmanFilter1D:
    def __init__(self, process_var: float = 1.0, measure_var: float = 4.0) -> None:
        self.process_var = process_var
        self.measure_var = measure_var
        self.posteri_est: float | None = None
        self.posteri_err_est = 1.0

    def step(self, measurement: float) -> float:
        if self.posteri_est is None:
            self.posteri_est = measurement
            return measurement
        priori_est = self.posteri_est
        priori_err = self.posteri_err_est + self.process_var
        gain = priori_err / (priori_err + self.measure_var)
        self.posteri_est = priori_est + gain * (measurement - priori_est)
        self.posteri_err_est = (1 - gain) * priori_err
        return self.posteri_est


class KalmanSignalProcessor:
    def __init__(self, process_var: float = 1.0, measure_var: float = 4.0) -> None:
        self.filter = KalmanFilter1D(process_var=process_var, measure_var=measure_var)

    def process(self, values: np.ndarray) -> np.ndarray:
        if values.size == 0:
            return values
        return np.asarray([self.filter.step(float(v)) for v in values], dtype=float)


class AdaptiveSmoother:
    def process(self, values: np.ndarray) -> np.ndarray:
        if values.size <= 2:
            return values
        volatility = float(np.std(values))
        alpha = float(np.clip(0.65 - volatility / 120.0, 0.15, 0.65))
        out = np.zeros_like(values, dtype=float)
        out[0] = float(values[0])
        for i in range(1, len(values)):
            out[i] = alpha * float(values[i]) + (1 - alpha) * out[i - 1]
        return out


class OutlierRejector:
    def process(self, values: np.ndarray) -> np.ndarray:
        if values.size < 8:
            return values
        q1, q3 = np.percentile(values, [25, 75])
        iqr = q3 - q1
        low, high = q1 - 1.5 * iqr, q3 + 1.5 * iqr
        mask = (values >= low) & (values <= high)
        kept = values[mask]
        if kept.size == 0:
            return values
        return kept


class CongestionDetectionSuite:
    def density_cluster_ratio(self, points: np.ndarray) -> float:
        if points.shape[0] < 4:
            return 0.0
        labels = DBSCAN(eps=0.0015, min_samples=4).fit_predict(points)
        in_clusters = float(np.mean(labels >= 0))
        return float(np.clip(in_clusters, 0.0, 1.0))

    def slow_zone_score(self, speeds: np.ndarray, speed_limit_kph: float) -> float:
        if speeds.size == 0:
            return 0.0
        threshold = max(speed_limit_kph * 0.55, 5.0)
        return float(np.clip(np.mean(speeds < threshold), 0.0, 1.0))

    def bottleneck_score(self, speeds: np.ndarray) -> float:
        if speeds.size < 5:
            return 0.0
        p90 = float(np.percentile(speeds, 90))
        p20 = float(np.percentile(speeds, 20))
        if p90 <= 0:
            return 0.0
        drop = (p90 - p20) / p90
        return float(np.clip(drop, 0.0, 1.0))

    def traffic_wave_score(self, speeds: np.ndarray) -> float:
        if speeds.size < 6:
            return 0.0
        gradient = np.abs(np.diff(speeds))
        if gradient.size == 0:
            return 0.0
        return float(np.clip(np.mean(gradient) / 25.0, 0.0, 1.0))


class HistoricalBaselineModel:
    def __init__(self, max_history: int = 14 * 24) -> None:
        self.max_history = max_history
        self._store: dict[str, deque[float]] = defaultdict(lambda: deque(maxlen=max_history))

    def update(self, segment_id: str, value: float) -> None:
        self._store[segment_id].append(float(value))

    def baseline(self, segment_id: str) -> float:
        history = self._store.get(segment_id)
        if not history:
            return 0.0
        return float(np.mean(np.asarray(history, dtype=float)))


class SegmentSpeedDistribution:
    def summarize(self, speeds: np.ndarray) -> dict[str, float]:
        if speeds.size == 0:
            return {"p10": 0.0, "p50": 0.0, "p90": 0.0, "std": 0.0}
        return {
            "p10": float(np.percentile(speeds, 10)),
            "p50": float(np.percentile(speeds, 50)),
            "p90": float(np.percentile(speeds, 90)),
            "std": float(np.std(speeds)),
        }


class ZScoreDeviationEngine:
    def score(self, speeds: np.ndarray, baseline_speed: float) -> float:
        if speeds.size < 2:
            return 0.0
        sigma = float(np.std(speeds))
        if sigma <= 1e-6:
            return 0.0
        z = abs(float(np.mean(speeds)) - baseline_speed) / sigma
        return float(np.clip(z / 4.0, 0.0, 1.0))


class AnomalyDetectionSuite:
    def __init__(self) -> None:
        self._isolation = IsolationForest(contamination=0.05, random_state=42)

    def isolation_score(self, speeds: np.ndarray) -> float:
        if speeds.size < 12:
            return 0.0
        reshaped = speeds.reshape(-1, 1)
        pred = self._isolation.fit_predict(reshaped)
        return float(np.clip(np.mean(pred == -1), 0.0, 1.0))

    def sudden_slowdown_score(self, speeds: np.ndarray) -> float:
        if speeds.size < 4:
            return 0.0
        deltas = np.diff(speeds)
        slowdown = np.abs(np.minimum(deltas, 0.0))
        return float(np.clip(np.mean(slowdown) / 30.0, 0.0, 1.0))

    def abnormal_pattern_score(self, speeds: np.ndarray) -> float:
        if speeds.size < 6:
            return 0.0
        diffs = np.diff(speeds)
        sign_changes = np.sum(np.sign(diffs[1:]) != np.sign(diffs[:-1]))
        return float(np.clip(sign_changes / max(len(diffs), 1), 0.0, 1.0))


class NeighborPropagationModel:
    def __init__(self, neighbors: dict[str, list[str]] | None = None) -> None:
        self.neighbors = neighbors or {}

    def propagate(self, segment_id: str, value: float, neighborhood_scores: dict[str, float] | None = None) -> float:
        linked = self.neighbors.get(segment_id, [])
        if not linked:
            return value
        neighbor_values = []
        for neighbor in linked:
            if neighborhood_scores and neighbor in neighborhood_scores:
                neighbor_values.append(neighborhood_scores[neighbor])
        if not neighbor_values:
            return value
        return float(np.clip(value * 0.7 + float(np.mean(neighbor_values)) * 0.3, 0.0, 1.0))


class GraphDiffusionLogic:
    def diffuse(self, segment_id: str, base_score: float, neighbors: list[float]) -> float:
        if not neighbors:
            return base_score
        score = base_score
        for _ in range(2):
            score = score * 0.75 + float(np.mean(neighbors)) * 0.25
        return float(np.clip(score, 0.0, 1.0))


class UpstreamDownstreamAnalyzer:
    def evaluate(self, upstream_scores: list[float], downstream_scores: list[float]) -> float:
        up = float(np.mean(upstream_scores)) if upstream_scores else 0.0
        down = float(np.mean(downstream_scores)) if downstream_scores else 0.0
        return float(np.clip(up * 0.6 + down * 0.4, 0.0, 1.0))


class TemporalTrendPredictor:
    def predict(self, speeds: np.ndarray) -> float:
        if speeds.size == 0:
            return 0.0
        alpha = 0.35
        value = float(speeds[0])
        for speed in speeds[1:]:
            value = alpha * float(speed) + (1 - alpha) * value
        return value


class SequenceAttentionPredictor:
    def predict(self, speeds: np.ndarray) -> float:
        if speeds.size == 0:
            return 0.0
        idx = np.arange(speeds.size, dtype=float)
        weights = np.exp((idx - idx.max()) / max(speeds.size, 1))
        weights /= np.sum(weights)
        return float(np.dot(speeds.astype(float), weights))


class EnsembleFusionModule:
    def fuse(self, temporal_speed: float, sequence_speed: float, baseline_speed: float) -> float:
        return float(0.5 * temporal_speed + 0.35 * sequence_speed + 0.15 * baseline_speed)


@dataclass
class CacheValue:
    value: CongestionMetrics
    expires_at_s: float


class CongestionEngine:
    def __init__(
        self,
        input_processor: SegmentInputProcessor | None = None,
        signal_processors: list[SignalProcessor] | None = None,
        detector: CongestionDetectionSuite | None = None,
        baseline_model: HistoricalBaselineModel | None = None,
        distribution_model: SegmentSpeedDistribution | None = None,
        zscore_engine: ZScoreDeviationEngine | None = None,
        anomaly_suite: AnomalyDetectionSuite | None = None,
        neighbor_model: NeighborPropagationModel | None = None,
        diffusion_logic: GraphDiffusionLogic | None = None,
        flow_analyzer: UpstreamDownstreamAnalyzer | None = None,
        temporal_predictor: TemporalTrendPredictor | None = None,
        sequence_predictor: SequenceAttentionPredictor | None = None,
        fusion: EnsembleFusionModule | None = None,
        cache_ttl_s: int = 20,
        max_workers: int = 4,
    ) -> None:
        self.input_processor = input_processor or GpsInputProcessor()
        self.signal_processors = signal_processors or [KalmanSignalProcessor(), AdaptiveSmoother(), OutlierRejector()]
        self.detector = detector or CongestionDetectionSuite()
        self.baseline_model = baseline_model or HistoricalBaselineModel()
        self.distribution_model = distribution_model or SegmentSpeedDistribution()
        self.zscore_engine = zscore_engine or ZScoreDeviationEngine()
        self.anomaly_suite = anomaly_suite or AnomalyDetectionSuite()
        self.neighbor_model = neighbor_model or NeighborPropagationModel()
        self.diffusion_logic = diffusion_logic or GraphDiffusionLogic()
        self.flow_analyzer = flow_analyzer or UpstreamDownstreamAnalyzer()
        self.temporal_predictor = temporal_predictor or TemporalTrendPredictor()
        self.sequence_predictor = sequence_predictor or SequenceAttentionPredictor()
        self.fusion = fusion or EnsembleFusionModule()

        self.cache_ttl_s = cache_ttl_s
        self.cache: dict[str, CacheValue] = {}
        self.cache_lock = threading.Lock()
        self.pool = ThreadPoolExecutor(max_workers=max_workers)

        self.metrics = EngineRuntimeMetrics()

    def process_segment(
        self,
        segment_id: str,
        samples: list[dict],
        speed_limit_kph: float = 60.0,
        neighborhood_scores: dict[str, float] | None = None,
        upstream_scores: list[float] | None = None,
        downstream_scores: list[float] | None = None,
    ) -> CongestionMetrics:
        now_s = time.time()
        cached = self._cache_get(segment_id, now_s)
        if cached is not None:
            self.metrics.cache_hits += 1
            return cached

        start = time.perf_counter()
        try:
            processed = self.input_processor.process(samples)
            if len(processed) < 3:
                fallback = CongestionMetrics(
                    congestion_probability=0.0,
                    confidence_score=0.2,
                    severity_index=0.0,
                    components={"fallback": 1.0},
                )
                self._cache_put(segment_id, fallback, now_s)
                self.metrics.processed_segments += 1
                return fallback

            coords = np.asarray([[p.latitude, p.longitude] for p in processed], dtype=float)
            speeds = np.asarray([p.speed_kph for p in processed], dtype=float)
            for processor in self.signal_processors:
                speeds = processor.process(speeds)
            if speeds.size == 0:
                speeds = np.asarray([p.speed_kph for p in processed], dtype=float)

            baseline_speed = self.baseline_model.baseline(segment_id)
            if baseline_speed <= 0:
                baseline_speed = float(np.mean(speeds))
            self.baseline_model.update(segment_id, float(np.mean(speeds)))

            density = self.detector.density_cluster_ratio(coords)
            slow_zone = self.detector.slow_zone_score(speeds, speed_limit_kph=speed_limit_kph)
            bottleneck = self.detector.bottleneck_score(speeds)
            wave = self.detector.traffic_wave_score(speeds)

            zscore = self.zscore_engine.score(speeds, baseline_speed=baseline_speed)
            isolation = self.anomaly_suite.isolation_score(speeds)
            slowdown = self.anomaly_suite.sudden_slowdown_score(speeds)
            abnormal = self.anomaly_suite.abnormal_pattern_score(speeds)

            temporal_speed = self.temporal_predictor.predict(speeds)
            sequence_speed = self.sequence_predictor.predict(speeds)
            predicted_speed = self.fusion.fuse(temporal_speed, sequence_speed, baseline_speed)

            predicted_congestion = float(np.clip(1.0 - predicted_speed / max(speed_limit_kph, 1.0), 0.0, 1.0))
            structural = float(np.clip((slow_zone * 0.35 + bottleneck * 0.20 + wave * 0.10 + density * 0.10), 0.0, 1.0))
            anomaly = float(np.clip((zscore * 0.28 + isolation * 0.34 + slowdown * 0.24 + abnormal * 0.14), 0.0, 1.0))
            base_probability = float(np.clip(predicted_congestion * 0.52 + structural * 0.30 + anomaly * 0.18, 0.0, 1.0))

            propagated = self.neighbor_model.propagate(segment_id, base_probability, neighborhood_scores)
            diffused = self.diffusion_logic.diffuse(
                segment_id=segment_id,
                base_score=propagated,
                neighbors=list((neighborhood_scores or {}).values()),
            )
            flow_factor = self.flow_analyzer.evaluate(
                upstream_scores=upstream_scores or [],
                downstream_scores=downstream_scores or [],
            )
            probability = float(np.clip(diffused * 0.82 + flow_factor * 0.18, 0.0, 1.0))

            distribution = self.distribution_model.summarize(speeds)
            confidence = float(
                np.clip(
                    0.25
                    + min(1.0, len(processed) / 60.0) * 0.45
                    + (1.0 - distribution["std"] / max(speed_limit_kph, 1.0)) * 0.3,
                    0.1,
                    0.99,
                )
            )

            severity = float(np.clip(probability * (0.6 + 0.4 * confidence), 0.0, 1.0))
            metrics = CongestionMetrics(
                congestion_probability=probability,
                confidence_score=confidence,
                severity_index=severity,
                components={
                    "predicted_congestion": predicted_congestion,
                    "structural": structural,
                    "anomaly": anomaly,
                    "density": density,
                    "slow_zone": slow_zone,
                    "bottleneck": bottleneck,
                    "traffic_wave": wave,
                    "zscore": zscore,
                    "isolation": isolation,
                    "sudden_slowdown": slowdown,
                    "abnormal_pattern": abnormal,
                },
            )
            self._cache_put(segment_id, metrics, now_s)
            self.metrics.processed_segments += 1
            return metrics
        except Exception as exc:  # noqa: BLE001
            self.metrics.failures += 1
            LOGGER.exception("congestion_engine_failure", extra={"segment_id": segment_id, "error": str(exc)[:220]})
            return CongestionMetrics(
                congestion_probability=0.0,
                confidence_score=0.15,
                severity_index=0.0,
                components={"fallback": 1.0},
            )
        finally:
            latency_ms = (time.perf_counter() - start) * 1000.0
            self.metrics.observe_latency(latency_ms)

    def process_batch(
        self,
        samples_by_segment: dict[str, list[dict]],
        speed_limit_provider: Callable[[str], float] | None = None,
    ) -> dict[str, CongestionMetrics]:
        if not samples_by_segment:
            return {}

        start = time.perf_counter()
        futures = {}
        for segment_id, segment_samples in samples_by_segment.items():
            speed_limit = speed_limit_provider(segment_id) if speed_limit_provider else 60.0
            futures[self.pool.submit(self.process_segment, segment_id, segment_samples, speed_limit)] = segment_id

        result: dict[str, CongestionMetrics] = {}
        for future in as_completed(futures):
            segment_id = futures[future]
            try:
                result[segment_id] = future.result()
            except Exception as exc:  # noqa: BLE001
                self.metrics.failures += 1
                LOGGER.exception("congestion_engine_batch_item_failed", extra={"segment_id": segment_id, "error": str(exc)[:220]})
                result[segment_id] = CongestionMetrics(
                    congestion_probability=0.0,
                    confidence_score=0.1,
                    severity_index=0.0,
                    components={"fallback": 1.0},
                )

        self.metrics.processed_batches += 1
        self.metrics.observe_latency((time.perf_counter() - start) * 1000.0)
        return result

    def _cache_get(self, segment_id: str, now_s: float) -> CongestionMetrics | None:
        with self.cache_lock:
            value = self.cache.get(segment_id)
            if value is None:
                return None
            if value.expires_at_s < now_s:
                self.cache.pop(segment_id, None)
                return None
            return value.value

    def _cache_put(self, segment_id: str, value: CongestionMetrics, now_s: float) -> None:
        with self.cache_lock:
            self.cache[segment_id] = CacheValue(value=value, expires_at_s=now_s + self.cache_ttl_s)
            if len(self.cache) <= 20_000:
                return
            expired = [key for key, cached in self.cache.items() if cached.expires_at_s < now_s]
            for key in expired:
                self.cache.pop(key, None)
            if len(self.cache) > 20_000:
                trim = len(self.cache) - 20_000
                keys = list(self.cache.keys())[:trim]
                for key in keys:
                    self.cache.pop(key, None)


__all__ = [
    "CongestionEngine",
    "CongestionMetrics",
    "EngineRuntimeMetrics",
    "ProcessedGpsPoint",
    "GpsInputProcessor",
    "KalmanSignalProcessor",
    "AdaptiveSmoother",
    "OutlierRejector",
    "CongestionDetectionSuite",
    "HistoricalBaselineModel",
    "SegmentSpeedDistribution",
    "ZScoreDeviationEngine",
    "AnomalyDetectionSuite",
    "NeighborPropagationModel",
    "GraphDiffusionLogic",
    "UpstreamDownstreamAnalyzer",
    "TemporalTrendPredictor",
    "SequenceAttentionPredictor",
    "EnsembleFusionModule",
]
