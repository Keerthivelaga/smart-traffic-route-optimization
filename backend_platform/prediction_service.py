from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import joblib
import numpy as np

from backend_platform.config import settings
from backend_platform.observability import PREDICTION_BATCH_SIZE
from backend_platform.resilience import CircuitBreaker, CircuitBreakerConfig, with_timeout


LOGGER = logging.getLogger("prediction_service")

try:
    import onnxruntime as ort
except Exception:  # noqa: BLE001
    ort = None


@dataclass
class CacheValue:
    prediction: float
    expires_at: float


@dataclass
class PredictionRequest:
    key: str
    features: np.ndarray
    future: asyncio.Future[float]


class PredictionService:
    def __init__(
        self,
        model_path: str = "artifacts/runtime_fallback.joblib",
        onnx_path: str = "artifacts/model.onnx",
        fallback_model_path: str = "artifacts/runtime_fallback.joblib",
        cache_ttl: int | None = None,
    ) -> None:
        self.model_path = Path(model_path)
        self.onnx_path = Path(onnx_path)
        self.fallback_model_path = Path(fallback_model_path)

        self.cache_ttl = cache_ttl or settings.prediction_cache_ttl_seconds
        self.cache: dict[str, CacheValue] = {}

        self.queue: asyncio.Queue[PredictionRequest] = asyncio.Queue(maxsize=50_000)
        self.batch_max_size = settings.prediction_batch_max_size
        self.batch_timeout = settings.prediction_batch_timeout_ms / 1000.0
        self.prediction_timeout = settings.prediction_timeout_ms / 1000.0

        self.model: Any = None
        self.fallback_model: Any = None
        self.onnx_session = None
        self.worker_task: asyncio.Task[Any] | None = None
        self.expected_feature_dim: int | None = None

        self.circuit = CircuitBreaker(CircuitBreakerConfig(failure_threshold=6, recovery_timeout_s=20, half_open_max_calls=2))

    def load_model(self) -> None:
        self.model = None
        self.fallback_model = None
        self.onnx_session = None
        self.expected_feature_dim = None

        if self.onnx_path.exists() and ort is not None:
            session = ort.InferenceSession(self.onnx_path.as_posix(), providers=["CPUExecutionProvider"])
            if self._onnx_is_vector_model(session):
                self.onnx_session = session
                shape = session.get_inputs()[0].shape
                if len(shape) >= 2 and isinstance(shape[1], int):
                    self.expected_feature_dim = int(shape[1])
                LOGGER.info("onnx_vector_model_loaded", extra={"path": str(self.onnx_path)})
            else:
                LOGGER.warning("onnx_model_shape_incompatible_for_backend", extra={"path": str(self.onnx_path)})

        if self.model_path.exists():
            self.model = joblib.load(self.model_path)
            if self.expected_feature_dim is None:
                self.expected_feature_dim = int(getattr(self.model, "n_features_in_", 0) or 0) or None
            LOGGER.info("primary_model_loaded", extra={"path": str(self.model_path)})

        if self.fallback_model_path.exists():
            self.fallback_model = joblib.load(self.fallback_model_path)
            if self.expected_feature_dim is None:
                self.expected_feature_dim = int(getattr(self.fallback_model, "n_features_in_", 0) or 0) or None

        if self.model is None and self.onnx_session is None and self.fallback_model is None:
            raise FileNotFoundError("No prediction model artifact found")

    async def start(self) -> None:
        if self.model is None and self.onnx_session is None and self.fallback_model is None:
            self.load_model()
        self.worker_task = asyncio.create_task(self._batch_worker())

    async def stop(self) -> None:
        if self.worker_task:
            self.worker_task.cancel()
            await asyncio.gather(self.worker_task, return_exceptions=True)
            self.worker_task = None

    async def predict(self, key: str, features: np.ndarray) -> float:
        cached = self._cache_get(key)
        if cached is not None:
            return cached

        loop = asyncio.get_running_loop()
        future: asyncio.Future[float] = loop.create_future()
        await self.queue.put(PredictionRequest(key=key, features=features, future=future))
        return await with_timeout(future, timeout_s=self.prediction_timeout)

    def invalidate(self, prefix: str | None = None) -> int:
        if prefix is None:
            size = len(self.cache)
            self.cache.clear()
            return size
        keys = [k for k in self.cache.keys() if str(k).startswith(prefix)]
        for key in keys:
            self.cache.pop(key, None)
        return len(keys)

    def hot_reload(self) -> None:
        self.load_model()
        self.cache.clear()

    async def _batch_worker(self) -> None:
        while True:
            req = await self.queue.get()
            batch = [req]
            start = time.perf_counter()

            while len(batch) < self.batch_max_size:
                remaining = self.batch_timeout - (time.perf_counter() - start)
                if remaining <= 0:
                    break
                try:
                    nxt = await asyncio.wait_for(self.queue.get(), timeout=remaining)
                    batch.append(nxt)
                except asyncio.TimeoutError:
                    break

            PREDICTION_BATCH_SIZE.observe(len(batch))
            await self._execute_batch(batch)

    async def _execute_batch(self, batch: list[PredictionRequest]) -> None:
        arr = self._normalize_features(np.vstack([r.features for r in batch]).astype(np.float32))
        try:
            preds = self._predict_array(arr)
            for item, pred in zip(batch, preds):
                self._cache_put(item.key, float(pred))
                if not item.future.done():
                    item.future.set_result(float(pred))
                self.queue.task_done()
            self.circuit.record_success()
        except Exception as exc:  # noqa: BLE001
            self.circuit.record_failure()
            LOGGER.error("prediction_batch_failed", extra={"error": str(exc)[:200]})
            fallback_preds = self._fallback_predict(arr)
            for item, pred in zip(batch, fallback_preds):
                self._cache_put(item.key, float(pred))
                if not item.future.done():
                    item.future.set_result(float(pred))
                self.queue.task_done()

    def _predict_array(self, arr: np.ndarray) -> np.ndarray:
        if not self.circuit.allow():
            raise RuntimeError("prediction_circuit_open")

        if self.onnx_session is not None:
            input_name = self.onnx_session.get_inputs()[0].name
            output_name = self.onnx_session.get_outputs()[0].name
            out = self.onnx_session.run([output_name], {input_name: arr})[0]
            return np.asarray(out).reshape(-1)

        if self.model is not None:
            return np.asarray(self.model.predict(arr)).reshape(-1)

        raise RuntimeError("Prediction model not loaded")

    def _fallback_predict(self, arr: np.ndarray) -> np.ndarray:
        if self.fallback_model is not None:
            try:
                return np.asarray(self.fallback_model.predict(arr)).reshape(-1)
            except Exception as exc:  # noqa: BLE001
                LOGGER.error("fallback_prediction_failed", extra={"error": str(exc)[:200]})
        return np.zeros((arr.shape[0],), dtype=np.float32)

    def _normalize_features(self, arr: np.ndarray) -> np.ndarray:
        if arr.ndim == 1:
            arr = arr.reshape(1, -1)

        expected = self.expected_feature_dim
        if expected is None or expected <= 0:
            return arr

        current = int(arr.shape[1])
        if current == expected:
            return arr
        if current > expected:
            return arr[:, :expected]

        pad = expected - current
        return np.pad(arr, ((0, 0), (0, pad)), mode="constant", constant_values=0.0)

    def _cache_get(self, key: str) -> float | None:
        item = self.cache.get(key)
        now = time.time()
        if item is None:
            return None
        if item.expires_at <= now:
            self.cache.pop(key, None)
            return None
        return item.prediction

    def _cache_put(self, key: str, prediction: float) -> None:
        now = time.time()
        self.cache[key] = CacheValue(prediction=prediction, expires_at=now + self.cache_ttl)

    @staticmethod
    def _onnx_is_vector_model(session) -> bool:
        try:
            shape = session.get_inputs()[0].shape
        except Exception:  # noqa: BLE001
            return False
        return len(shape) == 2
