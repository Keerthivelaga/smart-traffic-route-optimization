from __future__ import annotations

import base64
import hashlib
import hmac
import logging
import math
import time
from collections import defaultdict, deque
from dataclasses import dataclass

from backend_platform.config import settings
from backend_platform.observability import ABUSE_EVENTS


LOGGER = logging.getLogger("security")


@dataclass
class AuthContext:
    user_id: str
    roles: set[str]
    trust_score: float


class TokenBucketThrottle:
    def __init__(self, max_requests_per_minute: int) -> None:
        self.refill_rate = max_requests_per_minute / 60.0
        self.capacity = float(max_requests_per_minute)
        self.tokens: dict[str, float] = defaultdict(lambda: self.capacity)
        self.updated_at: dict[str, float] = defaultdict(time.time)

    def allow(self, key: str, cost: float = 1.0) -> bool:
        now = time.time()
        elapsed = max(0.0, now - self.updated_at[key])
        self.updated_at[key] = now
        self.tokens[key] = min(self.capacity, self.tokens[key] + elapsed * self.refill_rate)

        if self.tokens[key] < cost:
            return False
        self.tokens[key] -= cost
        return True


class ReplayProtector:
    def __init__(self, ttl_seconds: int = 300, max_entries: int = 500_000) -> None:
        self.ttl_seconds = ttl_seconds
        self.max_entries = max_entries
        self.nonces: dict[str, float] = {}

    def seen(self, key: str) -> bool:
        now = time.time()
        expiry = self.nonces.get(key)
        if expiry and expiry > now:
            return True
        self.nonces[key] = now + self.ttl_seconds
        if len(self.nonces) > self.max_entries:
            self._cleanup(now)
        return False

    def _cleanup(self, now: float) -> None:
        expired = [k for k, v in self.nonces.items() if v < now]
        for k in expired:
            self.nonces.pop(k, None)


class AnomalyDetector:
    def __init__(self, threshold: float) -> None:
        self.threshold = threshold
        self.series: dict[str, deque[float]] = defaultdict(lambda: deque(maxlen=240))

    def observe(self, actor: str, value: float) -> bool:
        series = self.series[actor]
        series.append(float(value))
        if len(series) < 24:
            return False
        mean = sum(series) / len(series)
        variance = sum((x - mean) ** 2 for x in series) / len(series)
        std = math.sqrt(variance) if variance > 0 else 0.0
        if std == 0:
            return False
        z = (value - mean) / std
        return z > self.threshold


class AbuseDetector:
    def __init__(self, quarantine_seconds: int = 1800) -> None:
        self.invalid_signatures: dict[str, int] = defaultdict(int)
        self.schema_failures: dict[str, int] = defaultdict(int)
        self.quarantine_until: dict[str, float] = defaultdict(float)
        self.quarantine_seconds = quarantine_seconds

    def record_invalid_signature(self, user_id: str) -> None:
        self.invalid_signatures[user_id] += 1
        ABUSE_EVENTS.labels(type="invalid_signature").inc()
        self._maybe_quarantine(user_id)

    def record_schema_failure(self, user_id: str) -> None:
        self.schema_failures[user_id] += 1
        ABUSE_EVENTS.labels(type="schema_failure").inc()
        self._maybe_quarantine(user_id)

    def _maybe_quarantine(self, user_id: str) -> None:
        score = self.invalid_signatures[user_id] * 2 + self.schema_failures[user_id]
        if score >= 10:
            self.quarantine_until[user_id] = time.time() + self.quarantine_seconds
            ABUSE_EVENTS.labels(type="user_quarantined").inc()

    def is_quarantined(self, user_id: str) -> bool:
        return self.quarantine_until[user_id] > time.time()


class TrustScoringEngine:
    def update(
        self,
        previous: float,
        accepted_reports: int,
        rejected_reports: int,
        abuse_events: int,
        decay: float = 0.995,
    ) -> float:
        quality = (accepted_reports + 1) / (accepted_reports + rejected_reports + 2)
        penalty = min(0.5, abuse_events * 0.05)
        score = previous * decay + (1 - decay) * quality - penalty
        return min(1.0, max(0.0, score))


class SecurityEngine:
    def __init__(self) -> None:
        self.throttle = TokenBucketThrottle(settings.max_requests_per_minute)
        self.anomaly = AnomalyDetector(settings.abuse_zscore_threshold)
        self.abuse = AbuseDetector()
        self.trust = TrustScoringEngine()
        self.replay = ReplayProtector()

    @staticmethod
    def has_role(auth: AuthContext, allowed: set[str]) -> bool:
        return bool(auth.roles.intersection(allowed))

    @staticmethod
    def _compute_signature(body: bytes, timestamp: str) -> str:
        message = f"{timestamp}.".encode("utf-8") + body
        digest = hmac.new(settings.hmac_secret.encode("utf-8"), message, hashlib.sha256).digest()
        return base64.b64encode(digest).decode("utf-8")

    def verify_signature(
        self,
        body: bytes,
        signature_header: str | None,
        timestamp: str | None,
        signature_id: str | None,
    ) -> bool:
        if not signature_header or not timestamp:
            return False
        try:
            ts = int(timestamp)
        except ValueError:
            return False
        if abs(time.time() - ts) > 300:
            return False

        expected = self._compute_signature(body, timestamp)
        if not hmac.compare_digest(expected, signature_header):
            return False

        if signature_id:
            replay_key = f"{signature_id}:{timestamp}:{signature_header[:16]}"
            if self.replay.seen(replay_key):
                ABUSE_EVENTS.labels(type="replay_detected").inc()
                return False
        return True

    def authorize_request(
        self,
        auth: AuthContext,
        raw_body: bytes,
        signature: str | None,
        timestamp: str | None,
        signature_id: str | None = None,
    ) -> tuple[bool, str]:
        if self.abuse.is_quarantined(auth.user_id):
            return False, "quarantined"

        if not self.verify_signature(raw_body, signature, timestamp, signature_id):
            self.abuse.record_invalid_signature(auth.user_id)
            return False, "invalid_signature"

        if not self.throttle.allow(auth.user_id):
            ABUSE_EVENTS.labels(type="throttle_exceeded").inc()
            return False, "throttle_exceeded"

        if self.anomaly.observe(auth.user_id, 1.0):
            ABUSE_EVENTS.labels(type="rate_anomaly").inc()
            LOGGER.warning("rate_anomaly", extra={"user_id": auth.user_id})

        return True, "ok"


security_engine = SecurityEngine()
