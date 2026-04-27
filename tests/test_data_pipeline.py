from __future__ import annotations

import numpy as np

from ml_platform.data_engineering import TrafficFeaturePipeline


def test_data_pipeline_correctness(synthetic_csv):
    pipeline = TrafficFeaturePipeline()
    bundle = pipeline.fit_transform(synthetic_csv)

    assert bundle.features.ndim == 2
    assert bundle.features.shape[0] == bundle.targets.shape[0]
    assert bundle.features.shape[1] > 12
    assert not np.isnan(bundle.features).any()
    assert np.all(bundle.targets >= 0)
    assert np.all(bundle.targets <= 1)
