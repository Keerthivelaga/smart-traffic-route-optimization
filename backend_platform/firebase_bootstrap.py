from __future__ import annotations

import json
import logging
import os
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import firebase_admin
import yaml
from firebase_admin import credentials, firestore

from backend_platform.config import settings
from backend_platform.schemas import SCHEMA_REGISTRY


LOGGER = logging.getLogger("firebase_bootstrap")


@dataclass
class BootstrapResult:
    app_initialized: bool
    rules_deployed: bool
    indexes_deployed: bool
    collections_validated: bool


class FirebaseBootstrapper:
    REQUIRED_COLLECTIONS = tuple(SCHEMA_REGISTRY.keys())
    MARKER_ID = "schema_marker"

    def __init__(self) -> None:
        self.app = None
        self.db = None

    def initialize(self) -> None:
        if firebase_admin._apps:
            self.app = firebase_admin.get_app()
        else:
            kwargs: dict[str, Any] = {}
            cred = self._load_credential()
            if cred is not None:
                kwargs["credential"] = cred
            if settings.firebase_project_id:
                kwargs["options"] = {"projectId": settings.firebase_project_id}
            self.app = firebase_admin.initialize_app(**kwargs)

        self.db = firestore.client(self.app)
        LOGGER.info("firebase_initialized")

    @staticmethod
    def _load_credential():
        path = Path(settings.firebase_service_account_path)
        if not path.exists():
            LOGGER.info("service_account_missing_using_adc", extra={"path": str(path)})
            return None

        try:
            return credentials.Certificate(str(path))
        except json.JSONDecodeError:
            # Accept UTF-8 BOM encoded JSON credentials.
            raw = json.loads(path.read_text(encoding="utf-8-sig"))
            return credentials.Certificate(raw)

    def deploy_rules(self) -> bool:
        if not settings.rules_file.exists():
            LOGGER.warning("rules_file_missing", extra={"path": str(settings.rules_file)})
            return False

        return self._run_firebase_cli("firestore:rules", settings.rules_file)

    def deploy_indexes(self) -> bool:
        if not settings.indexes_file.exists():
            LOGGER.warning("indexes_file_missing", extra={"path": str(settings.indexes_file)})
            return False
        json.loads(settings.indexes_file.read_text(encoding="utf-8"))

        return self._run_firebase_cli("firestore:indexes", settings.indexes_file)

    def ensure_collections(self) -> None:
        assert self.db is not None
        for collection in self.REQUIRED_COLLECTIONS:
            ref = self.db.collection(collection).document(self.MARKER_ID)
            snapshot = ref.get()
            if not snapshot.exists:
                ref.set(
                    {
                        "schema_version": "1.0.0",
                        "bootstrap": True,
                    },
                    merge=True,
                )

    def validate_configuration(self) -> bool:
        assert self.db is not None

        schema_path = settings.schema_file
        if not schema_path.exists():
            raise FileNotFoundError(f"Schema file not found at {schema_path}")

        schema_cfg = yaml.safe_load(schema_path.read_text(encoding="utf-8"))
        expected = schema_cfg.get("collections", {})
        if not isinstance(expected, dict):
            LOGGER.error("invalid_schema_collections")
            return False

        for collection, cfg in expected.items():
            marker = self.db.collection(collection).document(self.MARKER_ID).get()
            if not marker.exists:
                LOGGER.error("missing_collection_marker", extra={"collection": collection})
                return False
            required = cfg.get("required", [])
            if not isinstance(required, list):
                LOGGER.error("invalid_schema_config", extra={"collection": collection})
                return False

        return True

    def write_bootstrap_metadata(self) -> None:
        assert self.db is not None
        metadata = {
            "rules_path": str(settings.rules_file),
            "indexes_path": str(settings.indexes_file),
            "schema_path": str(settings.schema_file),
        }
        self.db.collection("system_metrics").document("bootstrap_status").set(
            {
                "metric_name": "bootstrap_status",
                "value": 1.0,
                "labels": metadata,
                "timestamp": firestore.SERVER_TIMESTAMP,
                "schema_version": "1.0.0",
            },
            merge=True,
        )

    def run(self) -> BootstrapResult:
        self.initialize()
        rules_ok = self.deploy_rules()
        indexes_ok = self.deploy_indexes()
        self.ensure_collections()
        valid = self.validate_configuration()
        self.write_bootstrap_metadata()

        return BootstrapResult(
            app_initialized=True,
            rules_deployed=rules_ok,
            indexes_deployed=indexes_ok,
            collections_validated=valid,
        )

    @staticmethod
    def _run_firebase_cli(resource: str, path: Path) -> bool:
        config_path = Path("config/firebase.json")
        command = [
            "firebase",
            "deploy",
            "--only",
            resource,
            "--config",
            str(config_path if config_path.exists() else Path("firebase.json")),
            "--force",
        ]
        env = {
            "FIREBASE_RULES_PATH": str(path),
            "FIRESTORE_INDEXES_PATH": str(path),
        }
        try:
            subprocess.run(command, check=True, capture_output=True, text=True, env={**os.environ, **env})
            return True
        except FileNotFoundError:
            LOGGER.warning("firebase_cli_missing")
        except subprocess.CalledProcessError as exc:
            LOGGER.error("firebase_deploy_failed", extra={"stderr": exc.stderr[-500:]})
        return False


def bootstrap_firebase() -> BootstrapResult:
    return FirebaseBootstrapper().run()
