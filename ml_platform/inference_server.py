from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import torch
from cachetools import TTLCache
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, Field

from ml_platform.config import settings
from ml_platform.models.hybrid_model import HybridTrafficModel
from ml_platform.resilience import CircuitBreaker, CircuitConfig
from ml_platform.utils import pick_device


LOGGER = logging.getLogger("inference_server")


class PredictRequest(BaseModel):
    cache_key: str
    x_seq: list[list[float]]
    node_idx: int = Field(ge=0)


class BatchPredictRequest(BaseModel):
    items: list[PredictRequest]


@dataclass
class Pending:
    req: PredictRequest
    future: asyncio.Future[dict[str, float]]


class InferenceRuntime:
    def __init__(self) -> None:
        self.device = pick_device()
        self.cache = TTLCache(maxsize=settings.inference_cache_max_items, ttl=settings.inference_cache_ttl_seconds)
        self.queue: asyncio.Queue[Pending] = asyncio.Queue(maxsize=settings.inference_queue_maxsize)
        self.worker: asyncio.Task[Any] | None = None

        self.deep_model: HybridTrafficModel | None = None
        self.meta_model = None
        self.fallback_model = None
        self.node_features: torch.Tensor | None = None
        self.edge_index: torch.Tensor | None = None
        self.edge_weight: torch.Tensor | None = None
        self.sequence_length: int = 12
        self.feature_dim: int | None = None

        self.circuit = CircuitBreaker(CircuitConfig(failure_threshold=5, recovery_timeout_s=15.0, half_open_calls=2))

    def load(self) -> None:
        ckpt = Path(settings.artifacts) / "deep_hybrid.pt"
        aux = Path(settings.artifacts) / "training_aux.npz"
        meta = Path(settings.artifacts) / "model_meta.joblib"
        fallback = Path(settings.artifacts) / "runtime_fallback.joblib"

        if not fallback.exists():
            raise FileNotFoundError("runtime_fallback.joblib is required")
        self.fallback_model = joblib.load(fallback)

        if ckpt.exists() and aux.exists() and meta.exists():
            checkpoint = torch.load(ckpt, map_location="cpu")
            aux_data = np.load(aux)
            self.node_features = torch.from_numpy(aux_data["node_features"]).float().to(self.device)
            self.edge_index = torch.from_numpy(aux_data["edge_index"]).long().to(self.device)
            self.edge_weight = torch.from_numpy(aux_data["edge_weight"]).float().to(self.device)

            model = HybridTrafficModel(
                feature_dim=int(checkpoint["feature_dim"]),
                node_feat_dim=int(checkpoint["node_feat_dim"]),
                hidden_dim=int(checkpoint["hidden_dim"]),
            )
            model.load_state_dict(checkpoint["state_dict"])
            model.eval()
            self.deep_model = model.to(self.device)
            self.meta_model = joblib.load(meta)
            self.sequence_length = int(checkpoint["sequence_length"])
            self.feature_dim = int(checkpoint["feature_dim"])
            LOGGER.info("deep_meta_models_loaded")
        else:
            self.deep_model = None
            self.meta_model = None
            self.node_features = None
            self.edge_index = None
            self.edge_weight = None
            self.feature_dim = None
            LOGGER.warning("deep_model_missing_using_fallback")

    async def start(self) -> None:
        self.load()
        self.worker = asyncio.create_task(self._worker_loop())

    async def stop(self) -> None:
        if self.worker:
            self.worker.cancel()
            await asyncio.gather(self.worker, return_exceptions=True)
            self.worker = None

    async def predict(self, req: PredictRequest) -> dict[str, float]:
        self._validate_request_shape(req)
        cached = self.cache.get(req.cache_key)
        if cached is not None:
            return cached

        loop = asyncio.get_running_loop()
        fut: asyncio.Future[dict[str, float]] = loop.create_future()
        try:
            await asyncio.wait_for(self.queue.put(Pending(req=req, future=fut)), timeout=settings.inference_enqueue_timeout_ms / 1000.0)
        except asyncio.TimeoutError:
            return self._fallback_predict(req)

        try:
            return await asyncio.wait_for(fut, timeout=settings.inference_timeout_ms / 1000.0)
        except asyncio.TimeoutError:
            return self._fallback_predict(req)

    def hot_reload(self) -> None:
        self.load()

    async def _worker_loop(self) -> None:
        while True:
            item = await self.queue.get()
            batch = [item]
            start = time.perf_counter()
            while len(batch) < settings.inference_batch_max_size:
                remaining = settings.inference_batch_timeout_ms / 1000.0 - (time.perf_counter() - start)
                if remaining <= 0:
                    break
                try:
                    nxt = await asyncio.wait_for(self.queue.get(), timeout=remaining)
                    batch.append(nxt)
                except asyncio.TimeoutError:
                    break

            try:
                results = self._infer_batch([b.req for b in batch])
            except Exception as exc:  # noqa: BLE001
                LOGGER.error("batch_infer_failed", extra={"error": str(exc)[:200]})
                results = [self._fallback_predict(b.req) for b in batch]

            for pending, result in zip(batch, results):
                self.cache[pending.req.cache_key] = result
                if not pending.future.done():
                    pending.future.set_result(result)
                self.queue.task_done()

    def _infer_batch(self, reqs: list[PredictRequest]) -> list[dict[str, float]]:
        if (
            self.deep_model is None
            or self.meta_model is None
            or self.node_features is None
            or self.edge_index is None
            or self.edge_weight is None
            or not self.circuit.allow()
        ):
            return self._fallback_predict_batch(reqs)

        try:
            x = np.asarray([r.x_seq for r in reqs], dtype=np.float32)
            node_idx = np.asarray([r.node_idx for r in reqs], dtype=np.int64)

            x_t = torch.from_numpy(x).float().to(self.device)
            n_t = torch.from_numpy(node_idx).long().to(self.device)

            with torch.no_grad():
                base_pred, fused = self.deep_model(x_t, self.node_features, self.edge_index, self.edge_weight, n_t)
            base_np = base_pred.detach().cpu().numpy().reshape(-1, 1)
            fused_np = fused.detach().cpu().numpy()
            meta_x = np.hstack([fused_np, base_np])
            final = self.meta_model.predict(meta_x)
            self.circuit.record_success()

            return [
                {
                    "prediction": float(pred),
                    "base_prediction": float(base),
                    "fallback": 0.0,
                }
                for pred, base in zip(final, base_np.reshape(-1))
            ]
        except Exception as exc:  # noqa: BLE001
            self.circuit.record_failure()
            LOGGER.error("deep_inference_failed", extra={"error": str(exc)[:200]})
            return self._fallback_predict_batch(reqs)

    def _fallback_predict(self, req: PredictRequest) -> dict[str, float]:
        if self.fallback_model is None:
            raise RuntimeError("Fallback model unavailable")
        last = np.asarray(req.x_seq[-1], dtype=np.float32).reshape(1, -1)
        pred = float(self.fallback_model.predict(last)[0])
        return {"prediction": pred, "base_prediction": pred, "fallback": 1.0}

    def _fallback_predict_batch(self, reqs: list[PredictRequest]) -> list[dict[str, float]]:
        if self.fallback_model is None:
            raise RuntimeError("Fallback model unavailable")
        last = np.asarray([req.x_seq[-1] for req in reqs], dtype=np.float32)
        preds = self.fallback_model.predict(last).reshape(-1)
        return [
            {"prediction": float(pred), "base_prediction": float(pred), "fallback": 1.0}
            for pred in preds
        ]

    def _validate_request_shape(self, req: PredictRequest) -> None:
        if not req.x_seq:
            raise HTTPException(status_code=400, detail="x_seq_required")
        if self.feature_dim is not None:
            for row in req.x_seq:
                if len(row) != self.feature_dim:
                    raise HTTPException(status_code=400, detail="feature_dim_mismatch")
        if self.sequence_length and len(req.x_seq) != self.sequence_length:
            raise HTTPException(status_code=400, detail="sequence_length_mismatch")


runtime = InferenceRuntime()


@asynccontextmanager
async def lifespan(_: FastAPI):
    await runtime.start()
    try:
        yield
    finally:
        await runtime.stop()


app = FastAPI(title="Smart Traffic Inference", version="1.1.0", lifespan=lifespan)


@app.get("/health")
async def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "deep_loaded": runtime.deep_model is not None,
        "queue_depth": runtime.queue.qsize(),
        "circuit_state": runtime.circuit.state.value,
    }


@app.post("/predict")
async def predict(req: PredictRequest) -> dict[str, float]:
    return await runtime.predict(req)


@app.post("/predict/batch")
async def predict_batch(payload: BatchPredictRequest) -> dict[str, Any]:
    if not payload.items:
        return {"items": []}
    tasks = [runtime.predict(item) for item in payload.items]
    out = await asyncio.gather(*tasks)
    return {"items": out}


@app.websocket("/predict/stream")
async def predict_stream(ws: WebSocket) -> None:
    await ws.accept()
    try:
        while True:
            msg = await ws.receive_text()
            raw = json.loads(msg)
            req = PredictRequest.model_validate(raw)
            pred = await runtime.predict(req)
            await ws.send_json(pred)
    except WebSocketDisconnect:
        return


@app.post("/admin/hot-reload")
async def hot_reload() -> dict[str, str]:
    runtime.hot_reload()
    runtime.cache.clear()
    return {"status": "reloaded"}


def main() -> None:
    import uvicorn

    port = int(os.getenv("PORT", str(settings.inference_port)))
    uvicorn.run("ml_platform.inference_server:app", host=settings.inference_host, port=port)


if __name__ == "__main__":
    main()
