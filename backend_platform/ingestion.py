from __future__ import annotations

import asyncio
import logging
import time
import zlib
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from hashlib import sha1
from typing import Any

from pydantic import BaseModel, Field, field_validator

from backend_platform.config import settings
from backend_platform.observability import INGESTION_QUEUE_DEPTH
from backend_platform.storage import AsyncWriteQueue
from backend_platform.utils.spatial import RoadSegment, SpatialIndex, encode_geohash


LOGGER = logging.getLogger("ingestion")


class GPSPoint(BaseModel):
    timestamp: datetime
    latitude: float
    longitude: float
    speed_kph: float = Field(ge=0, le=220)
    heading_deg: float = Field(ge=0, le=360, default=0)
    accuracy_m: float = Field(ge=0, le=200, default=10)

    @field_validator("latitude")
    @classmethod
    def latitude_range(cls, v: float) -> float:
        if v < -90 or v > 90:
            raise ValueError("latitude out of range")
        return v

    @field_validator("longitude")
    @classmethod
    def longitude_range(cls, v: float) -> float:
        if v < -180 or v > 180:
            raise ValueError("longitude out of range")
        return v


class GPSBatch(BaseModel):
    user_id: str
    points: list[GPSPoint]
    source: str = Field(default="mobile")


@dataclass
class IngestionOutcome:
    accepted: int
    rejected: int
    anomalies: int
    backpressure_drops: int


class RecentDeduper:
    def __init__(self, ttl_seconds: int = 1800, max_items: int = 2_000_000) -> None:
        self.ttl_seconds = ttl_seconds
        self.max_items = max_items
        self.store: dict[str, float] = {}

    def seen(self, key: str) -> bool:
        now = time.time()
        expiry = self.store.get(key)
        if expiry and expiry > now:
            return True
        self.store[key] = now + self.ttl_seconds
        if len(self.store) > self.max_items:
            self._evict(now)
        return False

    def _evict(self, now: float) -> None:
        expired = [k for k, v in self.store.items() if v < now]
        for k in expired:
            self.store.pop(k, None)
        if len(self.store) <= self.max_items:
            return
        # Best-effort pressure relief when many entries are still active.
        for k in list(self.store.keys())[: len(self.store) - self.max_items]:
            self.store.pop(k, None)


class StreamIngestionEngine:
    def __init__(self, queue: AsyncWriteQueue, road_segments: list[dict[str, Any]], map_match_max_distance_m: float = 200.0) -> None:
        self.queue = queue
        self.map_match_max_distance_m = map_match_max_distance_m
        self.default_segment_id = "seg_0001"
        valid_segments = [RoadSegment(segment_id=s["segment_id"], geometry=s["geometry"]) for s in road_segments if s.get("geometry")]
        self.has_map_segments = len(valid_segments) > 0
        self.spatial_index = SpatialIndex(geohash_precision=6)
        self.spatial_index.build(valid_segments)
        self.deduper = RecentDeduper()

    async def ingest_batch(self, batch: GPSBatch) -> IngestionOutcome:
        accepted = 0
        rejected = 0
        anomalies = 0
        backpressure_drops = 0

        if len(batch.points) > settings.ingestion_batch_size:
            batch_points = batch.points[: settings.ingestion_batch_size]
            rejected += len(batch.points) - settings.ingestion_batch_size
        else:
            batch_points = batch.points

        for point in batch_points:
            if not self._timestamp_valid(point.timestamp):
                anomalies += 1
                rejected += 1
                continue

            digest = self._point_digest(batch.user_id, point)
            if self.deduper.seen(digest):
                rejected += 1
                continue

            segment_id, distance = self.spatial_index.match_segment(point.latitude, point.longitude)
            if segment_id is None:
                if self.has_map_segments:
                    anomalies += 1
                    LOGGER.warning("map_match_failed", extra={"user_id": batch.user_id})
                    continue
                # Fallback for environments without road graph loaded yet; keeps end-to-end pipeline live.
                segment_id = self.default_segment_id
                distance = 0.0

            if distance > self.map_match_max_distance_m:
                anomalies += 1
                rejected += 1
                continue

            geohash = encode_geohash(point.latitude, point.longitude, 7)
            sample_id = digest
            now_iso = datetime.now(timezone.utc).isoformat()
            payload = {
                "sample_id": sample_id,
                "user_id": batch.user_id,
                "segment_id": segment_id,
                "latitude": point.latitude,
                "longitude": point.longitude,
                "speed_kph": point.speed_kph,
                "heading_deg": point.heading_deg,
                "accuracy_m": point.accuracy_m,
                "timestamp": point.timestamp.astimezone(timezone.utc).isoformat(),
                "geohash": geohash,
                "source": batch.source,
                "compressed": self._compress_point(point),
                "schema_version": "1.0.0",
                "created_at": now_iso,
                "updated_at": now_iso,
            }
            queued = await self.queue.enqueue("traffic_samples", sample_id, payload)
            if not queued:
                backpressure_drops += 1
                rejected += 1
                continue

            accepted += 1
            INGESTION_QUEUE_DEPTH.set(self.queue.queue.qsize())

        return IngestionOutcome(
            accepted=accepted,
            rejected=rejected,
            anomalies=anomalies,
            backpressure_drops=backpressure_drops,
        )

    @staticmethod
    def _point_digest(user_id: str, point: GPSPoint) -> str:
        material = f"{user_id}:{point.timestamp.isoformat()}:{point.latitude:.6f}:{point.longitude:.6f}:{point.speed_kph:.2f}".encode(
            "utf-8"
        )
        return sha1(material, usedforsecurity=False).hexdigest()

    @staticmethod
    def _compress_point(point: GPSPoint) -> str:
        blob = f"{point.timestamp.isoformat()}|{point.latitude}|{point.longitude}|{point.speed_kph}|{point.heading_deg}|{point.accuracy_m}".encode(
            "utf-8"
        )
        return zlib.compress(blob, level=6).hex()

    @staticmethod
    def _timestamp_valid(ts: datetime) -> bool:
        now = datetime.now(timezone.utc)
        ts_utc = ts.astimezone(timezone.utc)
        if ts_utc > now + timedelta(minutes=2):
            return False
        if ts_utc < now - timedelta(hours=6):
            return False
        return True


async def start_queue_workers(queue: AsyncWriteQueue, workers: int) -> None:
    await queue.start(workers=workers)


async def stop_queue_workers(queue: AsyncWriteQueue) -> None:
    await queue.stop()
    await asyncio.sleep(0)
