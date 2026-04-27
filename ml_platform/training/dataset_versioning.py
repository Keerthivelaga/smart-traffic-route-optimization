from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path

from ml_platform.utils import utc_now_iso


@dataclass
class DatasetVersion:
    version_id: str
    sha256: str
    rows: int
    cols: int
    source_path: str


class DatasetVersioning:
    def __init__(self, registry_path: str | Path = "artifacts/dataset_versions.json") -> None:
        self.registry_path = Path(registry_path)
        self.registry_path.parent.mkdir(parents=True, exist_ok=True)
        if not self.registry_path.exists():
            self.registry_path.write_text("[]", encoding="utf-8")

    @staticmethod
    def compute_hash(path: str | Path) -> str:
        h = hashlib.sha256()
        with Path(path).open("rb") as fh:
            for chunk in iter(lambda: fh.read(1024 * 1024), b""):
                h.update(chunk)
        return h.hexdigest()

    def register(self, path: str | Path, rows: int, cols: int) -> DatasetVersion:
        sha = self.compute_hash(path)
        version_id = f"ds_{sha[:12]}"
        entry = {
            "version_id": version_id,
            "sha256": sha,
            "rows": rows,
            "cols": cols,
            "source_path": str(path),
            "registered_at": utc_now_iso(),
        }

        data = json.loads(self.registry_path.read_text(encoding="utf-8"))
        if not any(item["sha256"] == sha for item in data):
            data.append(entry)
            self.registry_path.write_text(json.dumps(data, indent=2), encoding="utf-8")

        return DatasetVersion(version_id=version_id, sha256=sha, rows=rows, cols=cols, source_path=str(path))
