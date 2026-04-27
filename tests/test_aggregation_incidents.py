from __future__ import annotations

from datetime import datetime, timedelta, timezone

from backend_platform.aggregation import TrafficAggregationEngine
from backend_platform.incident_validation import IncidentValidationEngine, ReportVote


def test_aggregation_engine_outputs_stats():
    engine = TrafficAggregationEngine(window_minutes=5)
    now = datetime.now(timezone.utc)
    for i in range(80):
        engine.add_sample(
            {
                "segment_id": "seg_001",
                "timestamp": (now - timedelta(seconds=10 * i)).isoformat(),
                "latitude": 37.77 + i * 1e-5,
                "longitude": -122.41 - i * 1e-5,
                "speed_kph": 30 + (i % 7),
            }
        )

    stats = engine.aggregate("seg_001", speed_limit=50)
    assert stats is not None
    assert 0 <= stats.congestion_score <= 1
    assert stats.avg_speed_kph > 0


def test_incident_validation_engine():
    engine = IncidentValidationEngine()
    now = datetime.now(timezone.utc)

    votes = [
        ReportVote("r1", "u1", "s1", True, 0.9, 80, now),
        ReportVote("r1", "u2", "s1", True, 0.85, 70, now),
        ReportVote("r1", "u3", "s1", False, 0.2, 20, now),
    ]
    decision = engine.evaluate("r1", votes, now=now)
    assert decision.status in {"verified", "pending", "rejected"}
    assert 0 <= decision.posterior_credibility <= 1
