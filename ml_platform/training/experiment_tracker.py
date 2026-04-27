from __future__ import annotations

import json
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ml_platform.utils import utc_now_iso


@dataclass
class ExperimentRun:
    run_id: str
    name: str
    params: dict[str, Any]


class ExperimentTracker:
    def __init__(self, base_dir: str | Path = "artifacts/experiments") -> None:
        self.base = Path(base_dir)
        self.base.mkdir(parents=True, exist_ok=True)

    def start_run(self, name: str, params: dict[str, Any]) -> ExperimentRun:
        run_id = f"run_{uuid.uuid4().hex[:12]}"
        run_dir = self.base / run_id
        run_dir.mkdir(parents=True, exist_ok=True)

        meta = {
            "run_id": run_id,
            "name": name,
            "params": params,
            "start_time": utc_now_iso(),
        }
        (run_dir / "meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")
        return ExperimentRun(run_id=run_id, name=name, params=params)

    def log_metrics(self, run_id: str, metrics: dict[str, float], step: int | None = None) -> None:
        run_dir = self.base / run_id
        run_dir.mkdir(parents=True, exist_ok=True)
        log_path = run_dir / "metrics.jsonl"
        payload = {"time": utc_now_iso(), "step": step, "metrics": metrics}
        with log_path.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(payload) + "\n")

    def finish_run(self, run_id: str, status: str = "completed") -> None:
        run_dir = self.base / run_id
        done = {"finish_time": utc_now_iso(), "status": status}
        (run_dir / "status.json").write_text(json.dumps(done, indent=2), encoding="utf-8")
