from __future__ import annotations

import numpy as np
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.metrics import mean_absolute_error, r2_score
from sklearn.model_selection import train_test_split

from ml_platform.data_engineering import TrafficFeaturePipeline


def test_accuracy_thresholds(synthetic_csv):
    pipeline = TrafficFeaturePipeline()
    bundle = pipeline.fit_transform(synthetic_csv)

    x_train, x_test, y_train, y_test = train_test_split(bundle.features, bundle.targets, test_size=0.2, random_state=42)

    model = HistGradientBoostingRegressor(random_state=42)
    model.fit(x_train, y_train)
    pred = model.predict(x_test)

    mae = mean_absolute_error(y_test, pred)
    r2 = r2_score(y_test, pred)
    print("MAE:", mae)
    print("R2 Score:", r2)

    assert mae < 0.2
    assert r2 > 0.5
