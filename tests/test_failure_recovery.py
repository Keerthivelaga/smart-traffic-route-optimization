from __future__ import annotations

import asyncio
from pathlib import Path

import joblib
import numpy as np
from sklearn.ensemble import HistGradientBoostingRegressor

from ml_platform.inference_server import InferenceRuntime, PredictRequest


def _mk_fallback():
    Path("artifacts").mkdir(exist_ok=True)
    x = np.random.rand(1000, 16)
    y = np.random.rand(1000)
    model = HistGradientBoostingRegressor(random_state=42)
    model.fit(x, y)
    joblib.dump(model, "artifacts/runtime_fallback.joblib")


async def _recovery():
    _mk_fallback()
    # Intentionally ensure deep model artifacts are absent
    for path in ["artifacts/deep_hybrid.pt", "artifacts/training_aux.npz", "artifacts/model_meta.joblib"]:
        p = Path(path)
        if p.exists():
            p.unlink()

    runtime = InferenceRuntime()
    await runtime.start()
    try:
        req = PredictRequest(cache_key="recover", x_seq=np.random.rand(12, 16).tolist(), node_idx=0)
        out = await runtime.predict(req)
        assert out["fallback"] == 1.0
        assert "prediction" in out
    finally:
        await runtime.stop()


def test_failure_recovery_to_fallback():
    asyncio.run(_recovery())
