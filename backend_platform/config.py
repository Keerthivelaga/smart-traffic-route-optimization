from __future__ import annotations

from pathlib import Path
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class BackendSettings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    env: str = Field(default="dev")
    service_name: str = Field(default="smart-traffic-backend")
    host: str = Field(default="0.0.0.0")
    port: int = Field(default=8080)

    firebase_service_account_path: str = Field(default="serviceAccountKey.json")
    firebase_project_id: str | None = Field(default=None)
    firebase_rules_path: str = Field(default="config/firebase_rules.rules")
    firestore_indexes_path: str = Field(default="config/firestore.indexes.json")
    schema_config_path: str = Field(default="config/schema_versions.yaml")

    hmac_secret: str = Field(default="change_me")
    max_requests_per_minute: int = Field(default=300)
    abuse_zscore_threshold: float = Field(default=4.0)

    ingestion_queue_maxsize: int = Field(default=20_000)
    ingestion_batch_size: int = Field(default=500)
    ingestion_worker_count: int = Field(default=4)
    prediction_cache_ttl_seconds: int = Field(default=30)
    prediction_batch_timeout_ms: int = Field(default=10)
    prediction_batch_max_size: int = Field(default=512)
    prediction_timeout_ms: int = Field(default=200)
    auth_cache_ttl_seconds: int = Field(default=120)
    auto_seed_segments: bool = Field(default=False)
    max_road_segment_load: int = Field(default=100_000)

    metrics_namespace: str = Field(default="smart_traffic")
    logs_dir: str = Field(default="logs")

    @property
    def rules_file(self) -> Path:
        return Path(self.firebase_rules_path)

    @property
    def indexes_file(self) -> Path:
        return Path(self.firestore_indexes_path)

    @property
    def schema_file(self) -> Path:
        return Path(self.schema_config_path)


settings = BackendSettings()
