from __future__ import annotations

import hashlib
import logging
import os
import time
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from typing import Any

import numpy as np
from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, Field

from backend_platform.config import settings
from backend_platform.container import ServiceContainer
from backend_platform.dependencies import get_auth_context
from backend_platform.observability import (
    HEALTH_STATUS,
    REQUEST_COUNT,
    REQUEST_LATENCY,
    AlertEvent,
    alerts,
    configure_logging,
    metrics_payload,
)
from backend_platform.security import AuthContext, security_engine


LOGGER = logging.getLogger("backend_app")


class PredictInput(BaseModel):
    cache_key: str
    segment_id: str
    horizon_minutes: int = Field(ge=1, le=240)
    features: list[float]


class ReportVoteItem(BaseModel):
    user_id: str
    support: bool
    trust_score: float = Field(ge=0.0, le=1.0)
    reputation: float = Field(ge=0.0, le=100.0)
    timestamp: datetime


class ReportVoteInput(BaseModel):
    report_id: str
    segment_id: str
    votes: list[ReportVoteItem]


container = ServiceContainer()


@asynccontextmanager
async def lifespan(_: FastAPI):
    configure_logging()
    HEALTH_STATUS.labels(component="api").set(1)
    try:
        await container.startup()
        HEALTH_STATUS.labels(component="firebase").set(1)
        yield
    except Exception as exc:  # noqa: BLE001
        HEALTH_STATUS.labels(component="firebase").set(0)
        alerts.emit(
            AlertEvent(
                component="firebase",
                severity="critical",
                message="startup_failed",
                labels={"error": str(exc)[:120]},
            )
        )
        LOGGER.exception("startup_failed")
        raise
    finally:
        await container.shutdown()
        HEALTH_STATUS.labels(component="api").set(0)


app = FastAPI(title="Smart Traffic Backend", version="1.1.0", lifespan=lifespan)


@app.middleware("http")
async def observability_middleware(request: Request, call_next):
    route = request.url.path
    method = request.method
    start = time.perf_counter()
    status_code = "500"
    try:
        response = await call_next(request)
        status_code = str(response.status_code)
        return response
    finally:
        duration = time.perf_counter() - start
        REQUEST_COUNT.labels(route=route, method=method, status=status_code).inc()
        REQUEST_LATENCY.labels(route=route, method=method).observe(duration)


@app.get("/health")
async def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "time": datetime.now(timezone.utc).isoformat(),
        "firebase": container.db is not None,
        "firebase_error": container.firebase_bootstrap_error,
        "ingestion_ready": container.ingestion is not None,
        "prediction_ready": container.predictor.worker_task is not None,
        "write_queue_depth": container.write_queue.queue.qsize() if container.write_queue else 0,
    }


@app.get("/metrics")
async def metrics() -> Response:
    return Response(content=metrics_payload(), media_type="text/plain; version=0.0.4")


@app.post("/ingest/gps")
async def ingest_gps(batch_payload: dict[str, Any], request: Request, auth: AuthContext = Depends(get_auth_context)) -> dict[str, Any]:
    if container.ingestion is None:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="ingestion_unavailable")

    body = await request.body()
    allowed, reason = security_engine.authorize_request(
        auth=auth,
        raw_body=body,
        signature=request.headers.get("x-signature"),
        timestamp=request.headers.get("x-signature-timestamp"),
        signature_id=request.headers.get("x-signature-id"),
    )
    if not allowed:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=reason)

    from backend_platform.ingestion import GPSBatch

    validated = GPSBatch.model_validate(batch_payload)
    outcome = await container.ingestion.ingest_batch(validated)
    if outcome.accepted == 0 and outcome.backpressure_drops > 0:
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail="backpressure_drop")

    return {
        "accepted": outcome.accepted,
        "rejected": outcome.rejected,
        "anomalies": outcome.anomalies,
        "backpressure_drops": outcome.backpressure_drops,
    }


@app.post("/aggregate/{segment_id}")
async def aggregate_segment(segment_id: str, auth: AuthContext = Depends(get_auth_context)) -> dict[str, Any]:
    if not security_engine.has_role(auth, {"admin", "ops", "service", "user"}):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="forbidden")

    rows = container.load_recent_samples(segment_id=segment_id, limit=500)
    for row in rows:
        container.aggregator.add_sample(row)

    speed_limit = container.segment_speed_limits.get(segment_id, 60.0)
    stats = container.aggregator.aggregate(segment_id, speed_limit=speed_limit)
    if stats is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="insufficient_data")

    now = datetime.now(timezone.utc).isoformat()
    payload = {
        "segment_id": stats.segment_id,
        "window_start": stats.window_start.isoformat(),
        "window_end": stats.window_end.isoformat(),
        "congestion_score": stats.congestion_score,
        "confidence": stats.confidence,
        "avg_speed_kph": stats.avg_speed_kph,
        "p95_travel_time_s": stats.p95_travel_time_s,
        "anomaly_score": stats.anomaly_score,
        "speed_distribution": stats.speed_distribution,
        "schema_version": "1.0.0",
        "created_at": now,
        "updated_at": now,
    }
    shard = int(hashlib.md5(segment_id.encode("utf-8")).hexdigest(), 16) % 32
    doc_id = f"{segment_id}:{stats.window_end.strftime('%Y%m%d%H%M')}:{shard:02d}"
    container.repo.upsert("congestion_stats", doc_id, payload)
    return payload


@app.post("/reports/validate")
async def validate_report(req: ReportVoteInput, auth: AuthContext = Depends(get_auth_context)) -> dict[str, Any]:
    if not security_engine.has_role(auth, {"admin", "ops", "service", "user"}):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="forbidden")

    votes = []
    for raw in req.votes:
        from backend_platform.incident_validation import ReportVote

        votes.append(
            ReportVote(
                report_id=req.report_id,
                user_id=raw.user_id,
                segment_id=req.segment_id,
                support=raw.support,
                trust_score=float(raw.trust_score),
                reputation=float(raw.reputation),
                timestamp=raw.timestamp,
            )
        )

    decision = container.incidents.evaluate(report_id=req.report_id, votes=votes)
    if container.repo:
        now = datetime.now(timezone.utc).isoformat()
        container.repo.upsert(
            "reports",
            req.report_id,
            {
                "report_id": req.report_id,
                "user_id": auth.user_id,
                "segment_id": req.segment_id,
                "report_type": "congestion",
                "severity": 3,
                "status": decision.status,
                "message": "aggregated decision",
                "latitude": 0.0,
                "longitude": 0.0,
                "credibility_score": decision.posterior_credibility,
                "schema_version": "1.0.0",
                "created_at": now,
                "updated_at": now,
            },
        )

    return {
        "report_id": req.report_id,
        "status": decision.status,
        "posterior_credibility": decision.posterior_credibility,
        "consensus_score": decision.consensus_score,
        "adversarial_risk": decision.adversarial_risk,
    }


@app.post("/predict")
async def predict(payload: PredictInput, auth: AuthContext = Depends(get_auth_context)) -> dict[str, Any]:
    if not security_engine.has_role(auth, {"admin", "ops", "service", "mlops", "user"}):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="forbidden")

    features = np.asarray(payload.features, dtype=np.float32)[None, :]
    start = time.perf_counter()
    pred = await container.predictor.predict(payload.cache_key, features)
    latency_ms = (time.perf_counter() - start) * 1000

    now = datetime.now(timezone.utc)
    shard = int(hashlib.sha256(payload.cache_key.encode("utf-8")).hexdigest(), 16) % 64
    cache_doc_id = f"{payload.segment_id}:{payload.horizon_minutes}:{shard:02d}:{payload.cache_key[:40]}"

    if container.repo is not None:
        container.repo.upsert(
            "predictions_cache",
            cache_doc_id,
            {
                "cache_key": payload.cache_key,
                "segment_id": payload.segment_id,
                "horizon_minutes": payload.horizon_minutes,
                "prediction": float(pred),
                "confidence": 0.8,
                "expires_at": (now + timedelta(seconds=settings.prediction_cache_ttl_seconds)).isoformat(),
                "schema_version": "1.0.0",
                "created_at": now.isoformat(),
                "updated_at": now.isoformat(),
            },
        )

    return {
        "prediction": float(pred),
        "latency_ms": latency_ms,
        "cache_key": payload.cache_key,
    }


@app.post("/admin/reload-model")
async def reload_model(auth: AuthContext = Depends(get_auth_context)) -> dict[str, str]:
    if not security_engine.has_role(auth, {"admin", "mlops"}):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="forbidden")
    container.predictor.hot_reload()
    return {"status": "reloaded"}


@app.exception_handler(Exception)
async def global_exception_handler(_: Request, exc: Exception):
    LOGGER.exception("unhandled_exception")
    return JSONResponse(status_code=500, content={"detail": "internal_error", "error": str(exc)[:300]})


def main() -> None:
    import uvicorn

    uvicorn.run(
        "backend_platform.app:app",
        host=settings.host,
        port=settings.port,
        reload=os.getenv("RELOAD", "false").lower() == "true",
    )


if __name__ == "__main__":
    main()
