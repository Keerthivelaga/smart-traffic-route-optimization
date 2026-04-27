from __future__ import annotations

import asyncio
import time
from pathlib import Path

import joblib
import numpy as np
from sklearn.ensemble import HistGradientBoostingRegressor

from ml_platform.inference_server import InferenceRuntime, PredictRequest


def _prepare_fallback_artifact():
    Path("artifacts").mkdir(exist_ok=True)
    for extra in ["deep_hybrid.pt", "training_aux.npz", "model_meta.joblib"]:
        p = Path("artifacts") / extra
        if p.exists():
            p.unlink()
    x = np.random.rand(2000, 16)
    y = np.random.rand(2000)
    model = HistGradientBoostingRegressor(random_state=42)
    model.fit(x, y)
    joblib.dump(model, "artifacts/runtime_fallback.joblib")


async def _run_latency_test():
    _prepare_fallback_artifact()
    runtime = InferenceRuntime()
    await runtime.start()

    try:
        req = PredictRequest(cache_key="k0", x_seq=np.random.rand(12, 16).tolist(), node_idx=0)
        # warmup
        _ = await runtime.predict(req)

        n = 100
        start = time.perf_counter()
        for i in range(n):
            req = PredictRequest(cache_key=f"k{i+1}", x_seq=np.random.rand(12, 16).tolist(), node_idx=0)
            _ = await runtime.predict(req)
        latency_ms = ((time.perf_counter() - start) / n) * 1000
        assert latency_ms < 50
    finally:
        await runtime.stop()


def test_inference_latency_under_target():
    asyncio.run(_run_latency_test())
