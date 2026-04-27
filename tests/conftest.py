from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd
import pytest


@pytest.fixture(scope="session")
def synthetic_csv(tmp_path_factory) -> Path:
    root = tmp_path_factory.mktemp("data")
    path = root / "traffic.csv"
    rng = np.random.default_rng(42)
    n = 3000
    ts = pd.date_range("2024-01-01", periods=n, freq="min")
    seg = rng.integers(0, 30, size=n)
    speed = np.clip(rng.normal(45, 10, size=n), 5, 110)
    df = pd.DataFrame(
        {
            "timestamp": ts,
            "segment_id": seg.astype(str),
            "latitude": 37.77 + rng.normal(0, 0.01, n),
            "longitude": -122.41 + rng.normal(0, 0.01, n),
            "speed_kph": speed,
            "volume": rng.integers(10, 400, n),
            "occupancy": np.clip(rng.normal(0.45, 0.18, n), 0, 1),
            "temperature_c": rng.normal(20, 7, n),
            "rain_intensity": rng.uniform(0, 1, n) * rng.binomial(1, 0.2, n),
            "wind_speed_mps": np.abs(rng.normal(4, 2, n)),
            "incident_flag": rng.binomial(1, 0.03, n),
            "demand_index": rng.uniform(0.2, 1.0, n),
        }
    )
    df["congestion_score"] = np.clip(1 - df["speed_kph"] / 70 + 0.2 * df["incident_flag"], 0, 1)
    df.to_csv(path, index=False)
    return path
