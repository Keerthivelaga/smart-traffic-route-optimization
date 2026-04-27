from __future__ import annotations

import logging
from pathlib import Path

import numpy as np

from ml_platform.data_acquisition import acquire_dataset
from ml_platform.data_engineering import TrafficFeaturePipeline
from ml_platform.graph_builder import build_road_graph
from ml_platform.training.trainer import HybridTrainingSystem


def main() -> None:
    logging.basicConfig(level=logging.INFO)

    data_path = acquire_dataset()

    pipeline = TrafficFeaturePipeline()
    bundle = pipeline.fit_transform(data_path)
    pipeline.persist()

    Path("data/processed").mkdir(parents=True, exist_ok=True)
    np.save("data/processed/features_runtime.npy", bundle.features)
    np.save("data/processed/targets_runtime.npy", bundle.targets)

    graph = build_road_graph(data_path)

    trainer = HybridTrainingSystem(sequence_length=12)
    artifacts = trainer.train(
        bundle,
        graph_node_features_path=graph.node_features_path,
        graph_metadata_path=graph.metadata_path,
        edge_index_path=graph.edge_index_path,
        edge_weight_path=graph.edge_weight_path,
    )

    print(artifacts.deep_model_path)
    print(artifacts.meta_model_path)
    print(artifacts.metrics_path)


if __name__ == "__main__":
    main()
