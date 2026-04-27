from __future__ import annotations

from backend_platform.congestion_engine import CongestionEngine


def _sample(speed: float, lat: float, lon: float, ts: str) -> dict:
    return {
        "timestamp": ts,
        "latitude": lat,
        "longitude": lon,
        "speed_kph": speed,
        "heading_deg": 0.0,
        "accuracy_m": 5.0,
    }


def test_congestion_engine_process_segment_returns_bounded_metrics() -> None:
    engine = CongestionEngine(cache_ttl_s=1)
    samples = [
        _sample(52.0, 37.770001, -122.420001, "2026-02-21T08:00:01Z"),
        _sample(48.0, 37.770101, -122.420101, "2026-02-21T08:00:02Z"),
        _sample(41.0, 37.770201, -122.420201, "2026-02-21T08:00:03Z"),
        _sample(35.0, 37.770301, -122.420301, "2026-02-21T08:00:04Z"),
        _sample(27.0, 37.770401, -122.420401, "2026-02-21T08:00:05Z"),
    ]

    metrics = engine.process_segment("seg_test", samples, speed_limit_kph=60.0)

    assert 0.0 <= metrics.congestion_probability <= 1.0
    assert 0.0 <= metrics.confidence_score <= 1.0
    assert 0.0 <= metrics.severity_index <= 1.0
    assert "structural" in metrics.components


def test_congestion_engine_batch_processing_and_cache() -> None:
    engine = CongestionEngine(cache_ttl_s=10)
    samples_a = [
        _sample(18.0, 37.780001, -122.410001, "2026-02-21T09:00:01Z"),
        _sample(17.0, 37.780101, -122.410101, "2026-02-21T09:00:02Z"),
        _sample(16.0, 37.780201, -122.410201, "2026-02-21T09:00:03Z"),
        _sample(14.0, 37.780301, -122.410301, "2026-02-21T09:00:04Z"),
    ]
    samples_b = [
        _sample(64.0, 37.760001, -122.430001, "2026-02-21T09:05:01Z"),
        _sample(61.0, 37.760101, -122.430101, "2026-02-21T09:05:02Z"),
        _sample(59.0, 37.760201, -122.430201, "2026-02-21T09:05:03Z"),
        _sample(58.0, 37.760301, -122.430301, "2026-02-21T09:05:04Z"),
    ]

    out1 = engine.process_batch({"seg_a": samples_a, "seg_b": samples_b})
    out2 = engine.process_batch({"seg_a": samples_a})

    assert set(out1.keys()) == {"seg_a", "seg_b"}
    assert "seg_a" in out2
    assert engine.metrics.processed_batches >= 2
    assert engine.metrics.cache_hits >= 1


def test_congestion_engine_missing_data_fallback() -> None:
    engine = CongestionEngine()
    out = engine.process_segment("seg_empty", [], speed_limit_kph=60.0)

    assert out.components.get("fallback") == 1.0
    assert out.confidence_score <= 0.2
