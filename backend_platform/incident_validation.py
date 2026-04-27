from __future__ import annotations

import logging
import math
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone


LOGGER = logging.getLogger("incident_validation")


@dataclass
class ReportVote:
    report_id: str
    user_id: str
    segment_id: str
    support: bool
    trust_score: float
    reputation: float
    timestamp: datetime


@dataclass
class IncidentDecision:
    report_id: str
    posterior_credibility: float
    consensus_score: float
    status: str
    adversarial_risk: float


class IncidentValidationEngine:
    def __init__(self, prior_alpha: float = 2.0, prior_beta: float = 2.0) -> None:
        self.prior_alpha = prior_alpha
        self.prior_beta = prior_beta

    def evaluate(self, report_id: str, votes: list[ReportVote], now: datetime | None = None) -> IncidentDecision:
        now = now or datetime.now(timezone.utc)
        if not votes:
            return IncidentDecision(report_id, 0.5, 0.0, "pending", 0.0)

        deduped = self._dedupe_latest(votes)

        weighted_support = 0.0
        weighted_total = 0.0
        suspicious_mass = 0.0

        for vote in deduped:
            age_minutes = max(0.0, (now - vote.timestamp).total_seconds() / 60)
            temporal_weight = math.exp(-age_minutes / 30)
            voter_weight = 0.7 * vote.trust_score + 0.3 * (vote.reputation / 100)
            weight = temporal_weight * voter_weight
            weighted_total += weight
            weighted_support += weight if vote.support else 0

            if voter_weight < 0.25:
                suspicious_mass += weight

        alpha = self.prior_alpha + weighted_support
        beta = self.prior_beta + (weighted_total - weighted_support)
        posterior = alpha / (alpha + beta)

        consensus = weighted_support / weighted_total if weighted_total > 0 else 0.0
        adversarial_risk = self._adversarial_risk(deduped, suspicious_mass, weighted_total)

        status = "pending"
        if posterior >= 0.75 and consensus >= 0.6 and adversarial_risk < 0.4:
            status = "verified"
        elif posterior < 0.35 or adversarial_risk >= 0.6:
            status = "rejected"

        return IncidentDecision(
            report_id=report_id,
            posterior_credibility=float(posterior),
            consensus_score=float(consensus),
            status=status,
            adversarial_risk=float(adversarial_risk),
        )

    @staticmethod
    def _dedupe_latest(votes: list[ReportVote]) -> list[ReportVote]:
        latest: dict[str, ReportVote] = {}
        for vote in votes:
            prev = latest.get(vote.user_id)
            if prev is None or vote.timestamp > prev.timestamp:
                latest[vote.user_id] = vote
        return list(latest.values())

    @staticmethod
    def _adversarial_risk(votes: list[ReportVote], suspicious_mass: float, weighted_total: float) -> float:
        if not votes or weighted_total <= 0:
            return 0.0

        recent = [v for v in votes if (datetime.now(timezone.utc) - v.timestamp).total_seconds() <= 300]
        support_counter = Counter(v.support for v in recent)
        burst_factor = 0.0
        if recent:
            dominance = max(support_counter.values()) / len(recent)
            burst_factor = max(0.0, dominance - 0.8)

        suspicious_ratio = min(1.0, suspicious_mass / max(weighted_total, 1e-9))
        return float(min(1.0, suspicious_ratio * 0.7 + burst_factor * 0.3))
