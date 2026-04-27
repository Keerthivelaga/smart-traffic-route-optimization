from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.decomposition import TruncatedSVD
from sklearn.ensemble import IsolationForest
from sklearn.experimental import enable_iterative_imputer  # noqa: F401
from sklearn.impute import IterativeImputer
from sklearn.preprocessing import OneHotEncoder, StandardScaler

from ml_platform.config import settings
from ml_platform.utils import ensure_dirs


LOGGER = logging.getLogger("data_engineering")


@dataclass
class DatasetBundle:
    features: np.ndarray
    targets: np.ndarray
    feature_names: list[str]
    segment_index: np.ndarray
    timestamps: np.ndarray


class TrafficFeaturePipeline:
    def __init__(self) -> None:
        self.imputer = IterativeImputer(random_state=settings.seed, max_iter=10)
        self.scaler = StandardScaler()
        self.anomaly_detector = IsolationForest(contamination=0.02, random_state=settings.seed)
        self.segment_encoder = OneHotEncoder(handle_unknown="ignore", sparse_output=True)
        self.segment_svd = TruncatedSVD(n_components=8, random_state=settings.seed)
        self.preprocessor: ColumnTransformer | None = None
        self._fit_complete = False

    def fit_transform(self, csv_path: str | Path) -> DatasetBundle:
        df = self._load(csv_path)
        enriched = self._engineer(df)
        clean = self._remove_anomalies(enriched)

        y = clean[settings.target_column].to_numpy(dtype=np.float32)
        X_df = clean.drop(columns=[settings.target_column])

        numerical = X_df.select_dtypes(include=["number", "bool"]).columns.tolist()
        numerical = [c for c in numerical if c != "segment_id"]

        self.preprocessor = ColumnTransformer(
            transformers=[
                ("num", self.imputer, numerical),
            ],
            remainder="drop",
        )

        X_num = self.preprocessor.fit_transform(X_df)
        X_num = self.scaler.fit_transform(X_num)

        seg_emb = self._segment_embeddings(X_df["segment_id"].astype(str).to_numpy(), fit=True)

        features = np.hstack([X_num.astype(np.float32), seg_emb.astype(np.float32)])
        names = [f"num_{i}" for i in range(X_num.shape[1])] + [f"segment_emb_{i}" for i in range(seg_emb.shape[1])]

        self._fit_complete = True
        return DatasetBundle(
            features=features,
            targets=y,
            feature_names=names,
            segment_index=X_df["segment_id"].astype(str).to_numpy(),
            timestamps=self._timestamp_array(X_df["timestamp"]),
        )

    def transform(self, csv_path: str | Path) -> DatasetBundle:
        if not self._fit_complete or self.preprocessor is None:
            raise RuntimeError("Pipeline must be fitted before transform")

        df = self._load(csv_path)
        enriched = self._engineer(df)

        y = enriched[settings.target_column].to_numpy(dtype=np.float32)
        X_df = enriched.drop(columns=[settings.target_column])

        X_num = self.preprocessor.transform(X_df)
        X_num = self.scaler.transform(X_num)
        seg_emb = self._segment_embeddings(X_df["segment_id"].astype(str).to_numpy(), fit=False)

        features = np.hstack([X_num.astype(np.float32), seg_emb.astype(np.float32)])
        names = [f"num_{i}" for i in range(X_num.shape[1])] + [f"segment_emb_{i}" for i in range(seg_emb.shape[1])]

        return DatasetBundle(
            features=features,
            targets=y,
            feature_names=names,
            segment_index=X_df["segment_id"].astype(str).to_numpy(),
            timestamps=self._timestamp_array(X_df["timestamp"]),
        )

    def persist(self) -> None:
        ensure_dirs(settings.artifacts)
        import joblib

        joblib.dump(self, settings.artifacts / "feature_pipeline.joblib")

    @staticmethod
    def load(path: str | Path | None = None) -> "TrafficFeaturePipeline":
        import joblib

        target = Path(path) if path else settings.artifacts / "feature_pipeline.joblib"
        return joblib.load(target)

    def _load(self, csv_path: str | Path) -> pd.DataFrame:
        df = pd.read_csv(csv_path)
        if "timestamp" not in df.columns:
            raise ValueError("Missing timestamp column")
        df["timestamp"] = pd.to_datetime(df["timestamp"], utc=True, errors="coerce")
        df = df.sort_values(["segment_id", "timestamp"]).reset_index(drop=True)
        return df

    def _engineer(self, df: pd.DataFrame) -> pd.DataFrame:
        out = df.copy()
        out = self._ensure_weather_features(out)

        ts = out["timestamp"]
        out["hour_sin"] = np.sin(2 * np.pi * ts.dt.hour / 24)
        out["hour_cos"] = np.cos(2 * np.pi * ts.dt.hour / 24)
        out["dow_sin"] = np.sin(2 * np.pi * ts.dt.dayofweek / 7)
        out["dow_cos"] = np.cos(2 * np.pi * ts.dt.dayofweek / 7)

        out["lat_sin"] = np.sin(np.radians(out["latitude"]))
        out["lat_cos"] = np.cos(np.radians(out["latitude"]))
        out["lon_sin"] = np.sin(np.radians(out["longitude"]))
        out["lon_cos"] = np.cos(np.radians(out["longitude"]))

        out["weather_fusion"] = (
            0.35 * out["rain_intensity"].fillna(0)
            + 0.25 * np.clip(out["wind_speed_mps"].fillna(0), 0, 30) / 30
            + 0.4 * np.clip(np.abs(out["temperature_c"].fillna(20) - 20) / 25, 0, 1)
        )

        out = self._add_lag_features(out)
        out = self._add_stat_features(out)
        out = self._temporal_smoothing(out)

        if settings.target_column not in out.columns:
            # fallback if target missing from external dataset
            out[settings.target_column] = 1 - np.clip(out["speed_kph"] / 70.0, 0, 1)

        out = out.replace([np.inf, -np.inf], np.nan)
        return out

    def _ensure_weather_features(self, df: pd.DataFrame) -> pd.DataFrame:
        out = df.copy()
        if "temperature_c" not in out:
            out["temperature_c"] = 20.0
        if "rain_intensity" not in out:
            out["rain_intensity"] = 0.0
        if "wind_speed_mps" not in out:
            out["wind_speed_mps"] = 3.0
        if "volume" not in out:
            out["volume"] = 120
        if "occupancy" not in out:
            out["occupancy"] = 0.35
        return out

    def _add_lag_features(self, df: pd.DataFrame) -> pd.DataFrame:
        out = df.copy()
        for lag in (1, 2, 3, 6):
            out[f"speed_lag_{lag}"] = out.groupby("segment_id")["speed_kph"].shift(lag)
            out[f"volume_lag_{lag}"] = out.groupby("segment_id")["volume"].shift(lag)
        return out

    def _add_stat_features(self, df: pd.DataFrame) -> pd.DataFrame:
        out = df.copy()
        grp = out.groupby("segment_id")
        out["speed_roll_mean_6"] = grp["speed_kph"].transform(lambda s: s.rolling(6, min_periods=1).mean())
        out["speed_roll_std_6"] = grp["speed_kph"].transform(lambda s: s.rolling(6, min_periods=1).std().fillna(0))
        out["speed_roll_p90_12"] = grp["speed_kph"].transform(lambda s: s.rolling(12, min_periods=1).quantile(0.9))
        out["volume_roll_mean_6"] = grp["volume"].transform(lambda s: s.rolling(6, min_periods=1).mean())
        return out

    def _temporal_smoothing(self, df: pd.DataFrame) -> pd.DataFrame:
        out = df.copy()
        out["speed_smoothed"] = (
            out.groupby("segment_id")["speed_kph"].transform(lambda s: s.ewm(alpha=0.35, adjust=False).mean())
        )
        return out

    def _remove_anomalies(self, df: pd.DataFrame) -> pd.DataFrame:
        cols = [c for c in ["speed_kph", "volume", "occupancy", "rain_intensity"] if c in df.columns]
        probe = df[cols].fillna(df[cols].median())
        mask = self.anomaly_detector.fit_predict(probe) != -1
        filtered = df.loc[mask].reset_index(drop=True)
        LOGGER.info("anomaly_filter", extra={"removed": int((~mask).sum()), "kept": len(filtered)})
        return filtered

    def _segment_embeddings(self, segment_ids: np.ndarray, fit: bool) -> np.ndarray:
        seg = segment_ids.reshape(-1, 1)
        if fit:
            sparse = self.segment_encoder.fit_transform(seg)
            emb = self.segment_svd.fit_transform(sparse)
        else:
            sparse = self.segment_encoder.transform(seg)
            emb = self.segment_svd.transform(sparse)
        return emb

    @staticmethod
    def _timestamp_array(series: pd.Series) -> np.ndarray:
        if hasattr(series.dt, "tz") and series.dt.tz is not None:
            return series.dt.tz_convert("UTC").dt.tz_localize(None).to_numpy(dtype="datetime64[ns]")
        return series.to_numpy(dtype="datetime64[ns]")


def build_feature_dataset(csv_path: str | Path) -> DatasetBundle:
    pipeline = TrafficFeaturePipeline()
    bundle = pipeline.fit_transform(csv_path)
    pipeline.persist()
    return bundle
