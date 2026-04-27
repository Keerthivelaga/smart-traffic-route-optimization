from __future__ import annotations

import json

import numpy as np
import torch

from ml_platform.graph_builder import build_road_graph
from ml_platform.models.hybrid_model import HybridTrafficModel


def test_model_forward_correctness(synthetic_csv):
    artifacts = build_road_graph(synthetic_csv)
    node_features = torch.from_numpy(np.load(artifacts.node_features_path)).float()
    edge_index = torch.from_numpy(np.load(artifacts.edge_index_path)).long()
    edge_weight = torch.from_numpy(np.load(artifacts.edge_weight_path)).float()

    feature_dim = 24
    model = HybridTrafficModel(feature_dim=feature_dim, node_feat_dim=node_features.shape[1], hidden_dim=64)

    x_seq = torch.randn(16, 12, feature_dim)
    metadata = json.loads(artifacts.metadata_path.read_text(encoding="utf-8"))
    n_nodes = max(int(metadata["nodes"]), 1)
    node_idx = torch.randint(0, n_nodes, (16,))

    pred, fused = model(x_seq, node_features, edge_index, edge_weight, node_idx)

    assert pred.shape == (16,)
    assert fused.shape[0] == 16
    assert torch.isfinite(pred).all()
