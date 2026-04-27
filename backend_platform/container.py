from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Any

from firebase_admin import firestore

from backend_platform.aggregation import TrafficAggregationEngine
from backend_platform.config import settings
from backend_platform.firebase_bootstrap import FirebaseBootstrapper
from backend_platform.incident_validation import IncidentValidationEngine
from backend_platform.ingestion import StreamIngestionEngine
from backend_platform.prediction_service import PredictionService
from backend_platform.schemas import SchemaValidationEngine
from backend_platform.storage import AsyncWriteQueue, FirestoreRepository, NoopRepository


LOGGER = logging.getLogger("container")


class ServiceContainer:
    def __init__(self) -> None:
        self.db = None
        self.repo: FirestoreRepository | None = None
        self.firebase_bootstrap_error: str | None = None
        self.write_queue: AsyncWriteQueue | None = None
        self.ingestion: StreamIngestionEngine | None = None
        self.aggregator = TrafficAggregationEngine(window_minutes=5)
        self.incidents = IncidentValidationEngine()
        self.predictor = PredictionService()
        self.schema_validator = SchemaValidationEngine()
        self.segment_speed_limits: dict[str, float] = {}

    async def startup(self) -> None:
        segments: list[dict[str, Any]] = []

        try:
            bootstrap = FirebaseBootstrapper()
            bootstrap.initialize()
            bootstrap.ensure_collections()
            bootstrap.validate_configuration()

            self.db = bootstrap.db
            self.repo = FirestoreRepository(self.db, self.schema_validator)
            self.firebase_bootstrap_error = None
            segments = self._load_road_segments()
            LOGGER.info("firebase_bootstrap_ready")
        except Exception as exc:  # noqa: BLE001
            LOGGER.exception("firebase_bootstrap_failed", extra={"error": str(exc)[:240]})
            self.firebase_bootstrap_error = str(exc)[:500]
            self.db = None
            self.repo = NoopRepository()

        if not segments and settings.auto_seed_segments:
            segments = self._seed_default_segments()

        self.segment_speed_limits = {
            str(s["segment_id"]): float(s.get("speed_limit", 60.0)) for s in segments if s.get("segment_id")
        }

        self.write_queue = AsyncWriteQueue(
            repo=self.repo,
            maxsize=settings.ingestion_queue_maxsize,
            batch_size=min(200, settings.ingestion_batch_size),
            flush_interval_s=0.05,
        )
        await self.write_queue.start(workers=settings.ingestion_worker_count)

        self.ingestion = StreamIngestionEngine(queue=self.write_queue, road_segments=segments)
        await self.predictor.start()

    async def shutdown(self) -> None:
        if self.write_queue:
            await self.write_queue.stop()
        await self.predictor.stop()

    def _load_road_segments(self) -> list[dict[str, Any]]:
        if self.db is None:
            return []

        collection = self.db.collection("road_segments")
        docs: list[dict[str, Any]] = []
        page_size = 2000
        last = None

        while len(docs) < settings.max_road_segment_load:
            query = collection.order_by("__name__").limit(page_size)
            if last is not None:
                query = query.start_after(last)

            page = list(query.stream())
            if not page:
                break

            for snap in page:
                if snap.exists:
                    docs.append(snap.to_dict())
                    if len(docs) >= settings.max_road_segment_load:
                        break

            last = page[-1]

        return docs

    def _seed_default_segments(self) -> list[dict[str, Any]]:
        if self.repo is None:
            return []

        defaults = []
        now = datetime.now(timezone.utc).isoformat()
        for i in range(200):
            lat = 37.75 + (i * 0.00035)
            lon = -122.45 + (i * 0.00035)
            segment_id = f"seed_seg_{i:05d}"
            payload = {
                "segment_id": segment_id,
                "start_node": f"seed_n_{i:05d}",
                "end_node": f"seed_n_{i + 1:05d}",
                "speed_limit": 50.0,
                "lanes": 2,
                "geohash_prefix": "9q8y",
                "geometry": [(lat, lon), (lat + 0.0003, lon + 0.0003)],
                "schema_version": "1.0.0",
                "created_at": now,
                "updated_at": now,
            }
            self.repo.upsert("road_segments", segment_id, payload)
            defaults.append(payload)
        LOGGER.warning("seed_segments_created", extra={"count": len(defaults)})
        return defaults

    def load_recent_samples(self, segment_id: str, limit: int = 500) -> list[dict[str, Any]]:
        if self.db is None:
            return []
        try:
            docs = (
                self.db.collection("traffic_samples")
                .where("segment_id", "==", segment_id)
                .order_by("timestamp", direction=firestore.Query.DESCENDING)
                .limit(limit)
                .stream()
            )
            return [doc.to_dict() for doc in docs if doc.exists]
        except Exception as exc:  # noqa: BLE001
            # Composite index may not exist yet in some environments; fallback to in-memory sort.
            LOGGER.warning("traffic_sample_index_missing_fallback", extra={"error": str(exc)[:200], "segment_id": segment_id})
            docs = (
                self.db.collection("traffic_samples")
                .where("segment_id", "==", segment_id)
                .limit(limit * 3)
                .stream()
            )
            rows = [doc.to_dict() for doc in docs if doc.exists]
            rows.sort(key=lambda r: str(r.get("timestamp", "")), reverse=True)
            return rows[:limit]
