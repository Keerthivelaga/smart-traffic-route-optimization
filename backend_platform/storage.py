from __future__ import annotations

import asyncio
import logging
import random
import time
from dataclasses import dataclass
from typing import Any

from firebase_admin import firestore

from backend_platform.schemas import SchemaValidationEngine


LOGGER = logging.getLogger("storage")


class FirestoreRepository:
    def __init__(self, db: firestore.Client, validator: SchemaValidationEngine) -> None:
        self.db = db
        self.validator = validator

    def upsert(self, collection: str, doc_id: str, payload: dict[str, Any]) -> None:
        doc = self.validator.validate(collection, payload)
        self.db.collection(collection).document(doc_id).set(self.validator.serialize(doc), merge=True)

    def bulk_upsert(self, collection: str, rows: list[tuple[str, dict[str, Any]]]) -> None:
        if not rows:
            return
        batch = self.db.batch()
        for doc_id, payload in rows:
            doc = self.validator.validate(collection, payload)
            ref = self.db.collection(collection).document(doc_id)
            batch.set(ref, self.validator.serialize(doc), merge=True)
        batch.commit()

    def bulk_upsert_mixed(self, rows: list[tuple[str, str, dict[str, Any]]], batch_limit: int = 450) -> None:
        if not rows:
            return

        cursor = 0
        while cursor < len(rows):
            batch_rows = rows[cursor : cursor + batch_limit]
            cursor += batch_limit

            batch = self.db.batch()
            for collection, doc_id, payload in batch_rows:
                doc = self.validator.validate(collection, payload)
                ref = self.db.collection(collection).document(doc_id)
                batch.set(ref, self.validator.serialize(doc), merge=True)
            batch.commit()


class NoopRepository:
    def upsert(self, collection: str, doc_id: str, payload: dict[str, Any]) -> None:  # noqa: ARG002
        return

    def bulk_upsert(self, collection: str, rows: list[tuple[str, dict[str, Any]]]) -> None:  # noqa: ARG002
        return

    def bulk_upsert_mixed(self, rows: list[tuple[str, str, dict[str, Any]]], batch_limit: int = 450) -> None:  # noqa: ARG002
        return


@dataclass
class WriteTask:
    collection: str
    doc_id: str
    payload: dict[str, Any]
    retries: int = 0


class AsyncWriteQueue:
    def __init__(
        self,
        repo: FirestoreRepository,
        maxsize: int = 5000,
        retry_limit: int = 5,
        batch_size: int = 200,
        flush_interval_s: float = 0.05,
    ) -> None:
        self.repo = repo
        self.queue: asyncio.Queue[WriteTask | None] = asyncio.Queue(maxsize=maxsize)
        self.retry_limit = retry_limit
        self.batch_size = batch_size
        self.flush_interval_s = flush_interval_s
        self._workers: list[asyncio.Task[Any]] = []
        self.logger = logging.getLogger("async_write_queue")

    async def start(self, workers: int = 2) -> None:
        for i in range(workers):
            self._workers.append(asyncio.create_task(self._run_worker(i), name=f"write-worker-{i}"))

    async def stop(self) -> None:
        for _ in self._workers:
            await self.queue.put(None)
        await asyncio.gather(*self._workers, return_exceptions=True)
        self._workers.clear()

    async def enqueue(self, collection: str, doc_id: str, payload: dict[str, Any], timeout_s: float = 0.05) -> bool:
        task = WriteTask(collection=collection, doc_id=doc_id, payload=payload)
        try:
            await asyncio.wait_for(self.queue.put(task), timeout=timeout_s)
            return True
        except asyncio.TimeoutError:
            self.logger.warning("enqueue_timeout", extra={"collection": collection, "doc_id": doc_id})
            return False

    async def _run_worker(self, worker_id: int) -> None:
        while True:
            task = await self.queue.get()
            if task is None:
                self.queue.task_done()
                break

            buffer = [task]
            deadline = time.perf_counter() + self.flush_interval_s
            while len(buffer) < self.batch_size:
                remaining = deadline - time.perf_counter()
                if remaining <= 0:
                    break
                try:
                    nxt = await asyncio.wait_for(self.queue.get(), timeout=remaining)
                    if nxt is None:
                        self.queue.task_done()
                        await self.queue.put(None)
                        break
                    buffer.append(nxt)
                except asyncio.TimeoutError:
                    break

            await self._commit_buffer(buffer, worker_id)
            for _ in buffer:
                self.queue.task_done()

    async def _commit_buffer(self, buffer: list[WriteTask], worker_id: int) -> None:
        rows = [(task.collection, task.doc_id, task.payload) for task in buffer]
        try:
            await asyncio.to_thread(self.repo.bulk_upsert_mixed, rows)
        except Exception as exc:  # noqa: BLE001
            self.logger.error("batch_write_failed", extra={"size": len(buffer), "worker": worker_id, "error": str(exc)})
            await self._retry_tasks(buffer)

    async def _retry_tasks(self, tasks: list[WriteTask]) -> None:
        for task in tasks:
            if task.retries >= self.retry_limit:
                self.logger.error(
                    "write_failed",
                    extra={"collection": task.collection, "doc_id": task.doc_id, "retries": task.retries},
                )
                continue

            backoff = min(1.5, 0.05 * (2 ** task.retries))
            backoff += random.uniform(0, 0.05)
            await asyncio.sleep(backoff)
            task.retries += 1
            try:
                self.queue.put_nowait(task)
            except asyncio.QueueFull:
                self.logger.error(
                    "retry_queue_full",
                    extra={"collection": task.collection, "doc_id": task.doc_id, "retries": task.retries},
                )
