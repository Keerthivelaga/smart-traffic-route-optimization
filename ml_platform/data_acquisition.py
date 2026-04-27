from __future__ import annotations

import hashlib
import json
import logging
import os
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from ml_platform.config import settings
from ml_platform.utils import ensure_dirs, utc_now_iso, write_json


LOGGER = logging.getLogger("data_acquisition")


class DataAcquisitionManager:
    def __init__(self) -> None:
        ensure_dirs(settings.raw_dir, settings.processed_dir, settings.graph_dir, settings.artifacts)

    def acquire(self, query: str = "traffic speed congestion") -> Path:
        dataset_path = self._download_from_kaggle(query)
        if dataset_path is None:
            dataset_path = self._generate_synthetic_dataset(rows=settings.synthetic_rows)

        self._validate_schema(dataset_path)
        self._write_metadata(dataset_path)
        return dataset_path

    def _download_from_kaggle(self, query: str) -> Path | None:
        kaggle_cfg = Path(settings.kaggle_config_path)
        if not kaggle_cfg.exists():
            LOGGER.warning("kaggle_config_missing")
            return None

        try:
            from kaggle import KaggleApi

            os.environ["KAGGLE_CONFIG_DIR"] = str(kaggle_cfg.parent.resolve())
            api = KaggleApi()
            api.authenticate()
            datasets = api.dataset_list(search=query, file_type="csv")
            if not datasets:
                return None

            chosen = datasets[0]
            slug = f"{chosen.ref}"
            output_dir = settings.raw_dir / "kaggle"
            output_dir.mkdir(parents=True, exist_ok=True)
            api.dataset_download_files(slug, path=str(output_dir), unzip=True, quiet=True)

            csv_files = sorted(output_dir.glob("*.csv"), key=lambda p: p.stat().st_size, reverse=True)
            if not csv_files:
                return None

            selected = csv_files[0]
            LOGGER.info("kaggle_dataset_downloaded", extra={"dataset": slug, "file": selected.name})
            return selected
        except Exception as exc:  # noqa: BLE001
            LOGGER.warning("kaggle_download_failed", extra={"error": str(exc)[:200]})
            return None

    def _generate_synthetic_dataset(self, rows: int = 250_000) -> Path:
        rng = np.random.default_rng(42)
        timestamps = pd.date_range("2025-01-01", periods=rows, freq="2min")

        city_profiles = [
            {"code": "DEL", "lat": 28.6139, "lon": 77.2090, "lat_spread": 0.11, "lon_spread": 0.13, "traffic_weight": 1.25, "peak_penalty": 17.0, "rain_prob": 0.12, "temp_mean": 24.0, "temp_std": 6.0, "demand_base": 0.64},
            {"code": "MUM", "lat": 19.0760, "lon": 72.8777, "lat_spread": 0.09, "lon_spread": 0.10, "traffic_weight": 1.20, "peak_penalty": 15.5, "rain_prob": 0.29, "temp_mean": 28.0, "temp_std": 3.0, "demand_base": 0.67},
            {"code": "BLR", "lat": 12.9716, "lon": 77.5946, "lat_spread": 0.08, "lon_spread": 0.09, "traffic_weight": 1.05, "peak_penalty": 16.0, "rain_prob": 0.19, "temp_mean": 24.0, "temp_std": 3.0, "demand_base": 0.62},
            {"code": "CHN", "lat": 13.0827, "lon": 80.2707, "lat_spread": 0.08, "lon_spread": 0.09, "traffic_weight": 0.88, "peak_penalty": 14.0, "rain_prob": 0.22, "temp_mean": 29.0, "temp_std": 3.5, "demand_base": 0.57},
            {"code": "KOL", "lat": 22.5726, "lon": 88.3639, "lat_spread": 0.09, "lon_spread": 0.10, "traffic_weight": 0.86, "peak_penalty": 14.5, "rain_prob": 0.27, "temp_mean": 27.0, "temp_std": 4.0, "demand_base": 0.58},
            {"code": "HYD", "lat": 17.3850, "lon": 78.4867, "lat_spread": 0.09, "lon_spread": 0.10, "traffic_weight": 0.84, "peak_penalty": 14.8, "rain_prob": 0.17, "temp_mean": 27.0, "temp_std": 4.0, "demand_base": 0.56},
            {"code": "PUN", "lat": 18.5204, "lon": 73.8567, "lat_spread": 0.07, "lon_spread": 0.08, "traffic_weight": 0.63, "peak_penalty": 12.5, "rain_prob": 0.24, "temp_mean": 25.0, "temp_std": 4.0, "demand_base": 0.49},
            {"code": "AHM", "lat": 23.0225, "lon": 72.5714, "lat_spread": 0.08, "lon_spread": 0.08, "traffic_weight": 0.59, "peak_penalty": 12.0, "rain_prob": 0.10, "temp_mean": 30.0, "temp_std": 5.0, "demand_base": 0.50},
            {"code": "JAI", "lat": 26.9124, "lon": 75.7873, "lat_spread": 0.07, "lon_spread": 0.08, "traffic_weight": 0.44, "peak_penalty": 10.5, "rain_prob": 0.08, "temp_mean": 28.0, "temp_std": 5.0, "demand_base": 0.44},
            {"code": "LKO", "lat": 26.8467, "lon": 80.9462, "lat_spread": 0.07, "lon_spread": 0.08, "traffic_weight": 0.47, "peak_penalty": 11.2, "rain_prob": 0.11, "temp_mean": 27.0, "temp_std": 5.0, "demand_base": 0.46},
            {"code": "KOC", "lat": 9.9312, "lon": 76.2673, "lat_spread": 0.06, "lon_spread": 0.07, "traffic_weight": 0.34, "peak_penalty": 9.8, "rain_prob": 0.31, "temp_mean": 28.0, "temp_std": 2.5, "demand_base": 0.40},
            {"code": "GAU", "lat": 26.1445, "lon": 91.7362, "lat_spread": 0.07, "lon_spread": 0.08, "traffic_weight": 0.30, "peak_penalty": 9.0, "rain_prob": 0.25, "temp_mean": 25.0, "temp_std": 4.0, "demand_base": 0.38},
        ]

        city_codes = np.array([city["code"] for city in city_profiles], dtype=object)
        city_weights = np.array([city["traffic_weight"] for city in city_profiles], dtype=float)
        city_weights = city_weights / city_weights.sum()

        segment_count = max(360, min(2200, rows // 90))
        segment_city_idx = rng.choice(len(city_profiles), size=segment_count, p=city_weights)
        road_type_idx = rng.choice(3, size=segment_count, p=[0.32, 0.43, 0.25])
        road_type_speed_limit = np.array([66.0, 52.0, 38.0], dtype=float)

        segment_ids = np.array(
            [f"{city_codes[segment_city_idx[idx]]}_{idx:04d}" for idx in range(segment_count)],
            dtype=object,
        )
        segment_lat = np.array([city_profiles[idx]["lat"] for idx in segment_city_idx], dtype=float) + rng.normal(
            0,
            np.array([city_profiles[idx]["lat_spread"] for idx in segment_city_idx], dtype=float),
            size=segment_count,
        )
        segment_lon = np.array([city_profiles[idx]["lon"] for idx in segment_city_idx], dtype=float) + rng.normal(
            0,
            np.array([city_profiles[idx]["lon_spread"] for idx in segment_city_idx], dtype=float),
            size=segment_count,
        )
        segment_speed_limit = np.clip(
            road_type_speed_limit[road_type_idx] + rng.normal(0, 4.2, size=segment_count),
            28.0,
            92.0,
        )
        segment_base_speed = segment_speed_limit * rng.uniform(0.62, 0.88, size=segment_count)

        segment_weight = city_weights[segment_city_idx] * rng.uniform(0.8, 1.2, size=segment_count)
        segment_weight = segment_weight / segment_weight.sum()
        row_segment_idx = rng.choice(segment_count, size=rows, p=segment_weight)
        row_city_idx = segment_city_idx[row_segment_idx]

        month = timestamps.month.to_numpy()
        hour = timestamps.hour.to_numpy()
        day_of_week = timestamps.dayofweek.to_numpy()

        morning_peak = ((hour >= 8) & (hour <= 11)).astype(float)
        evening_peak = ((hour >= 17) & (hour <= 21)).astype(float)
        late_night = ((hour <= 5) | (hour >= 23)).astype(float)
        weekend = (day_of_week >= 5).astype(float)
        monsoon = ((month >= 6) & (month <= 9)).astype(float)

        city_peak_penalty = np.array([city_profiles[idx]["peak_penalty"] for idx in row_city_idx], dtype=float)
        city_rain_prob = np.array([city_profiles[idx]["rain_prob"] for idx in row_city_idx], dtype=float)
        city_demand_base = np.array([city_profiles[idx]["demand_base"] for idx in row_city_idx], dtype=float)
        city_temp_mean = np.array([city_profiles[idx]["temp_mean"] for idx in row_city_idx], dtype=float)
        city_temp_std = np.array([city_profiles[idx]["temp_std"] for idx in row_city_idx], dtype=float)

        rain_probability = np.clip(
            city_rain_prob + 0.18 * monsoon + 0.06 * (morning_peak + evening_peak),
            0.02,
            0.92,
        )
        rain_event = rng.binomial(1, rain_probability, size=rows)
        rain_intensity = rain_event * rng.gamma(
            shape=1.6 + 0.8 * monsoon,
            scale=0.45,
            size=rows,
        )
        rain_intensity = np.clip(rain_intensity, 0.0, 3.5)

        incident_probability = np.clip(
            0.012
            + 0.035 * rain_event
            + 0.015 * (morning_peak + evening_peak)
            + 0.010 * weekend,
            0.005,
            0.25,
        )
        incident_flag = rng.binomial(1, incident_probability, size=rows)

        demand_index = (
            city_demand_base
            + 0.23 * (morning_peak + evening_peak)
            + 0.08 * weekend
            - 0.11 * late_night
            + rng.normal(0, 0.06, size=rows)
        )
        demand_index = np.clip(demand_index, 0.10, 1.00)

        volume = 70 + demand_index * 520 + rain_event * 20 + incident_flag * 35 + rng.normal(0, 35, size=rows)
        volume = np.clip(np.round(volume), 15, 1200).astype(int)

        occupancy = (
            0.12
            + 0.62 * demand_index
            + 0.17 * rain_event
            + 0.14 * incident_flag
            + rng.normal(0, 0.08, size=rows)
        )
        occupancy = np.clip(occupancy, 0.02, 0.99)

        seasonal_temp_shift = 6.5 * np.sin((month - 1) * 2 * np.pi / 12 - 1.1)
        temperature_c = city_temp_mean + seasonal_temp_shift + rng.normal(0, city_temp_std, size=rows)
        temperature_c = np.clip(temperature_c, 7.0, 45.0)

        wind_speed_mps = rng.gamma(2.1, 1.4, size=rows) + 0.65 * rain_event + 0.35 * monsoon + rng.normal(0, 0.5, size=rows)
        wind_speed_mps = np.clip(wind_speed_mps, 0.2, 18.0)

        row_speed_limit = segment_speed_limit[row_segment_idx]
        row_base_speed = segment_base_speed[row_segment_idx]
        peak_drag = city_peak_penalty * (0.45 * morning_peak + 0.55 * evening_peak)
        demand_drag = (demand_index - 0.30) * 32.0
        rain_drag = rain_intensity * rng.uniform(5.0, 11.5, size=rows)
        incident_drag = incident_flag * rng.uniform(14.0, 28.0, size=rows)
        night_boost = late_night * rng.uniform(5.0, 12.0, size=rows)

        speed_kph = row_base_speed - peak_drag - demand_drag - rain_drag - incident_drag + night_boost + rng.normal(0, 4.2, size=rows)
        speed_kph = np.clip(speed_kph, 4.0, row_speed_limit + 8.0)

        speed_congestion = 1.0 - np.clip(speed_kph / np.maximum(row_speed_limit, 35.0), 0.0, 1.35)
        weather_pressure = np.clip(rain_intensity / 3.5, 0.0, 1.0)
        congestion_score = (
            speed_congestion * 0.58
            + occupancy * 0.22
            + demand_index * 0.14
            + weather_pressure * 0.04
            + incident_flag * 0.12
            + rng.normal(0, 0.03, size=rows)
        )
        congestion_score = np.clip(congestion_score, 0.0, 1.0)

        latitude = segment_lat[row_segment_idx] + rng.normal(0, 0.0012, size=rows)
        longitude = segment_lon[row_segment_idx] + rng.normal(0, 0.0012, size=rows)
        latitude = np.clip(latitude, 7.8, 37.4)
        longitude = np.clip(longitude, 68.2, 97.6)

        df = pd.DataFrame(
            {
                "timestamp": timestamps,
                "segment_id": segment_ids[row_segment_idx],
                "latitude": latitude,
                "longitude": longitude,
                "speed_kph": speed_kph,
                "volume": volume,
                "occupancy": occupancy,
                "temperature_c": temperature_c,
                "rain_intensity": rain_intensity,
                "wind_speed_mps": wind_speed_mps,
                "incident_flag": incident_flag,
                "demand_index": demand_index,
                "congestion_score": congestion_score,
            }
        )

        output = settings.raw_dir / "synthetic_traffic.csv"
        df.to_csv(output, index=False)
        LOGGER.info("synthetic_dataset_generated", extra={"rows": rows, "path": str(output)})
        return output

    def _validate_schema(self, csv_path: Path) -> None:
        df = pd.read_csv(csv_path, nrows=5000)
        required = {
            "timestamp",
            "segment_id",
            "latitude",
            "longitude",
            "speed_kph",
        }
        missing = required.difference(df.columns)
        if missing:
            raise ValueError(f"Dataset missing required columns: {sorted(missing)}")

    def _write_metadata(self, csv_path: Path) -> None:
        h = hashlib.sha256()
        with csv_path.open("rb") as fh:
            for chunk in iter(lambda: fh.read(1024 * 1024), b""):
                h.update(chunk)

        metadata: dict[str, Any] = {
            "path": str(csv_path),
            "sha256": h.hexdigest(),
            "size_bytes": csv_path.stat().st_size,
            "created_at": utc_now_iso(),
        }
        write_json(settings.artifacts / "dataset_metadata.json", metadata)


def acquire_dataset(query: str = "traffic speed congestion") -> Path:
    return DataAcquisitionManager().acquire(query=query)
