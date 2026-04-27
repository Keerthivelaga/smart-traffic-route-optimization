from __future__ import annotations

from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class MLSettings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    seed: int = Field(default=42)
    raw_data_dir: str = Field(default="data/raw")
    processed_data_dir: str = Field(default="data/processed")
    graph_data_dir: str = Field(default="data/graph")
    artifacts_dir: str = Field(default="artifacts")

    kaggle_config_path: str = Field(default="kaggle.json")
    synthetic_rows: int = Field(default=250_000)
    target_column: str = Field(default="congestion_score")

    batch_size: int = Field(default=256)
    epochs: int = Field(default=30)
    learning_rate: float = Field(default=5e-4)
    weight_decay: float = Field(default=1e-4)
    patience: int = Field(default=6)
    n_splits: int = Field(default=5)
    bayes_trials: int = Field(default=20)

    inference_host: str = Field(default="0.0.0.0")
    inference_port: int = Field(default=8090)
    inference_cache_ttl_seconds: int = Field(default=60)
    inference_cache_max_items: int = Field(default=200_000)
    inference_queue_maxsize: int = Field(default=100_000)
    inference_batch_max_size: int = Field(default=512)
    inference_batch_timeout_ms: int = Field(default=10)
    inference_enqueue_timeout_ms: int = Field(default=50)
    inference_timeout_ms: int = Field(default=5000)

    @property
    def raw_dir(self) -> Path:
        return Path(self.raw_data_dir)

    @property
    def processed_dir(self) -> Path:
        return Path(self.processed_data_dir)

    @property
    def graph_dir(self) -> Path:
        return Path(self.graph_data_dir)

    @property
    def artifacts(self) -> Path:
        return Path(self.artifacts_dir)


settings = MLSettings()
