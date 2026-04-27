from __future__ import annotations

import asyncio
import os
from pathlib import Path

import joblib
import numpy as np
from sklearn.ensemble import HistGradientBoostingRegressor

from ml_platform.inference_server import InferenceRuntime, PredictRequest


def _ensure_fallback():
    Path("artifacts").mkdir(exist_ok=True)
    for extra in ["deep_hybrid.pt", "training_aux.npz", "model_meta.joblib"]:
        p = Path("artifacts") / extra
        if p.exists():
            p.unlink()
    path = Path("artifacts/runtime_fallback.joblib")
    if path.exists():
        return
    x = np.random.rand(1500, 16)
    y = np.random.rand(1500)
    model = HistGradientBoostingRegressor(random_state=42)
    model.fit(x, y)
    joblib.dump(model, path)


async def _stress():
    _ensure_fallback()
    runtime = InferenceRuntime()
    await runtime.start()

    try:
        users = _safe_user_count()
        tasks = []
        for i in range(users):
            req = PredictRequest(cache_key=f"stress_{i}", x_seq=np.random.rand(12, 16).tolist(), node_idx=0)
            tasks.append(runtime.predict(req))
        results = await asyncio.gather(*tasks)
        assert len(results) == users
        assert all("prediction" in r for r in results)
    finally:
        await runtime.stop()


def test_stress_concurrent_predictions():
    asyncio.run(_stress())


def _safe_user_count() -> int:
    raw = os.getenv("SMART_TRAFFIC_TEST_USERS", "10").strip()
    try:
        parsed = int(raw)
    except ValueError:
        parsed = 10
    return max(5, min(10, parsed))
