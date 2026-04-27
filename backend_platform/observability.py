from __future__ import annotations

import json
import logging
import os
import time
from contextlib import contextmanager
from dataclasses import dataclass
from typing import Any

from prometheus_client import Counter, Gauge, Histogram, generate_latest

from backend_platform.config import settings


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "ts": int(time.time() * 1000),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        for attr in ("trace_id", "span_id", "request_id", "user_id", "segment_id"):
            if hasattr(record, attr):
                payload[attr] = getattr(record, attr)
        return json.dumps(payload, separators=(",", ":"))


def configure_logging() -> None:
    os.makedirs(settings.logs_dir, exist_ok=True)
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    root.handlers.clear()

    stream = logging.StreamHandler()
    stream.setFormatter(JsonFormatter())
    root.addHandler(stream)


REQUEST_COUNT = Counter(
    f"{settings.metrics_namespace}_requests_total",
    "Total requests",
    ["route", "method", "status"],
)
REQUEST_LATENCY = Histogram(
    f"{settings.metrics_namespace}_request_latency_seconds",
    "Request latency",
    ["route", "method"],
    buckets=(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0),
)
INGESTION_QUEUE_DEPTH = Gauge(
    f"{settings.metrics_namespace}_ingestion_queue_depth",
    "Current ingestion queue depth",
)
PREDICTION_BATCH_SIZE = Histogram(
    f"{settings.metrics_namespace}_prediction_batch_size",
    "Prediction micro-batch sizes",
    buckets=(1, 4, 8, 16, 32, 64, 128, 256, 512, 1024),
)
ABUSE_EVENTS = Counter(
    f"{settings.metrics_namespace}_abuse_events_total",
    "Abuse detections",
    ["type"],
)
HEALTH_STATUS = Gauge(
    f"{settings.metrics_namespace}_health_status",
    "Health status by component",
    ["component"],
)


@dataclass
class AlertEvent:
    component: str
    severity: str
    message: str
    labels: dict[str, str]


class AlertManager:
    def __init__(self) -> None:
        self.logger = logging.getLogger("alerts")

    def emit(self, event: AlertEvent) -> None:
        self.logger.error(
            "alert_triggered",
            extra={
                "component": event.component,
                "severity": event.severity,
                "message": event.message,
                "labels": event.labels,
            },
        )


alerts = AlertManager()


@contextmanager
def trace_span(name: str, attrs: dict[str, Any] | None = None):
    start = time.perf_counter()
    try:
        yield
    finally:
        duration = time.perf_counter() - start
        logging.getLogger("tracing").info(
            "span",
            extra={"span_id": name, "duration_seconds": duration, "attrs": attrs or {}},
        )


def metrics_payload() -> bytes:
    return generate_latest()
