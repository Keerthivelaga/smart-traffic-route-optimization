from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class Role(str, Enum):
    user = "user"
    admin = "admin"
    ops = "ops"
    service = "service"
    mlops = "mlops"


class BaseDocument(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: str = Field(default="1.0.0")
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class UserDoc(BaseDocument):
    user_id: str
    role: Role
    trust_score: float = Field(ge=0.0, le=1.0, default=0.5)
    reputation: float = Field(ge=0.0, le=100.0, default=50.0)
    request_count: int = Field(ge=0, default=0)
    flags: list[str] = Field(default_factory=list)


class TrafficSampleDoc(BaseDocument):
    sample_id: str
    user_id: str
    segment_id: str
    latitude: float
    longitude: float
    speed_kph: float = Field(ge=0, le=220)
    heading_deg: float = Field(ge=0, le=360)
    accuracy_m: float = Field(ge=0, le=200)
    timestamp: datetime
    geohash: str = Field(min_length=4, max_length=12)
    source: Literal["mobile", "vehicle", "sensor"] = "mobile"
    compressed: str | None = None

    @field_validator("latitude")
    @classmethod
    def valid_lat(cls, v: float) -> float:
        if not -90 <= v <= 90:
            raise ValueError("latitude out of range")
        return v

    @field_validator("longitude")
    @classmethod
    def valid_lon(cls, v: float) -> float:
        if not -180 <= v <= 180:
            raise ValueError("longitude out of range")
        return v


class RoadSegmentDoc(BaseDocument):
    segment_id: str
    start_node: str
    end_node: str
    speed_limit: float = Field(ge=10, le=180)
    lanes: int = Field(ge=1, le=12, default=2)
    geohash_prefix: str = Field(min_length=4, max_length=8)
    geometry: list[tuple[float, float]]


class CongestionStatsDoc(BaseDocument):
    segment_id: str
    window_start: datetime
    window_end: datetime
    congestion_score: float = Field(ge=0.0, le=1.0)
    confidence: float = Field(ge=0.0, le=1.0)
    avg_speed_kph: float = Field(ge=0.0, le=220.0)
    p95_travel_time_s: float = Field(ge=0)
    anomaly_score: float = Field(ge=0.0, le=1.0)
    speed_distribution: dict[str, float] = Field(default_factory=dict)


class ReportStatus(str, Enum):
    pending = "pending"
    verified = "verified"
    rejected = "rejected"


class ReportDoc(BaseDocument):
    report_id: str
    user_id: str
    segment_id: str
    report_type: Literal["accident", "hazard", "closure", "police", "congestion"]
    severity: int = Field(ge=1, le=5)
    status: ReportStatus = ReportStatus.pending
    message: str = Field(default="", max_length=512)
    latitude: float
    longitude: float
    credibility_score: float = Field(ge=0.0, le=1.0, default=0.5)


class LeaderboardDoc(BaseDocument):
    user_id: str
    score: float
    rank: int
    period: Literal["daily", "weekly", "monthly", "all_time"]


class PredictionCacheDoc(BaseDocument):
    cache_key: str
    segment_id: str
    horizon_minutes: int = Field(ge=1, le=240)
    prediction: float
    confidence: float = Field(ge=0.0, le=1.0)
    expires_at: datetime


class ModelMetadataDoc(BaseDocument):
    model_id: str
    version: str
    active: bool = True
    metrics: dict[str, float]
    artifact_paths: dict[str, str]


class SystemMetricsDoc(BaseDocument):
    metric_name: str
    value: float
    timestamp: datetime
    labels: dict[str, str] = Field(default_factory=dict)


SCHEMA_REGISTRY: dict[str, type[BaseDocument]] = {
    "users": UserDoc,
    "traffic_samples": TrafficSampleDoc,
    "road_segments": RoadSegmentDoc,
    "congestion_stats": CongestionStatsDoc,
    "reports": ReportDoc,
    "leaderboard": LeaderboardDoc,
    "predictions_cache": PredictionCacheDoc,
    "model_metadata": ModelMetadataDoc,
    "system_metrics": SystemMetricsDoc,
}


class SchemaValidationEngine:
    def validate(self, collection: str, payload: dict[str, Any]) -> BaseDocument:
        model = SCHEMA_REGISTRY.get(collection)
        if model is None:
            raise ValueError(f"Unknown collection: {collection}")
        try:
            return model.model_validate(payload)
        except ValidationError as exc:
            raise ValueError(f"Schema validation failed for {collection}: {exc}") from exc

    def serialize(self, document: BaseDocument) -> dict[str, Any]:
        return document.model_dump(mode="json")
