from __future__ import annotations

import asyncio
from pathlib import Path

import joblib
import numpy as np
import pytest
from fastapi import HTTPException
from sklearn.ensemble import HistGradientBoostingRegressor

from ml_platform.inference_server import InferenceRuntime, PredictRequest


def _prepare_fallback() -> None:
    Path("artifacts").mkdir(exist_ok=True)
    for extra in ["deep_hybrid.pt", "training_aux.npz", "model_meta.joblib"]:
        p = Path("artifacts") / extra
        if p.exists():
            p.unlink()
    path = Path("artifacts/runtime_fallback.joblib")
    x = np.random.rand(1200, 16)
    y = np.random.rand(1200)
    model = HistGradientBoostingRegressor(random_state=42)
    model.fit(x, y)
    joblib.dump(model, path)


async def _adversarial_runtime_check() -> None:
    _prepare_fallback()
    runtime = InferenceRuntime()
    await runtime.start()
    try:
        req = PredictRequest(cache_key="adv", x_seq=(np.random.randn(12, 16) * 1e6).tolist(), node_idx=0)
        out = await runtime.predict(req)
        assert np.isfinite(out["prediction"])
    finally:
        await runtime.stop()


def test_adversarial_input_stability():
    asyncio.run(_adversarial_runtime_check())


def test_edge_case_sequence_length_guard():
    runtime = InferenceRuntime()
    runtime.sequence_length = 12
    req = PredictRequest(cache_key="edge", x_seq=np.random.rand(3, 16).tolist(), node_idx=0)
    with pytest.raises(HTTPException):
        runtime._validate_request_shape(req)
