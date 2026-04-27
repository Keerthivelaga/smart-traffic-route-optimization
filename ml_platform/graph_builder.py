from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path

import networkx as nx
import numpy as np
import pandas as pd
from scipy.sparse import csr_matrix, save_npz
from sklearn.neighbors import NearestNeighbors

from ml_platform.config import settings
from ml_platform.utils import ensure_dirs


LOGGER = logging.getLogger("graph_builder")


@dataclass
class GraphArtifacts:
    adjacency_path: Path
    metadata_path: Path
    node_features_path: Path
    edge_index_path: Path
    edge_weight_path: Path


class RoadGraphBuilder:
    def __init__(self, k_neighbors: int = 6) -> None:
        self.k_neighbors = k_neighbors

    def build(self, csv_path: str | Path) -> GraphArtifacts:
        ensure_dirs(settings.graph_dir)
        df = pd.read_csv(csv_path)

        summary = (
            df.groupby("segment_id")
            .agg(latitude=("latitude", "mean"), longitude=("longitude", "mean"), speed_kph=("speed_kph", "mean"))
            .reset_index()
        )
        summary["node_id"] = np.arange(len(summary))

        coords = summary[["latitude", "longitude"]].to_numpy(dtype=np.float32)
        nn = NearestNeighbors(n_neighbors=min(self.k_neighbors + 1, len(summary)), metric="euclidean")
        nn.fit(coords)
        distances, indices = nn.kneighbors(coords)

        graph = nx.Graph()
        for _, row in summary.iterrows():
            graph.add_node(int(row["node_id"]), segment_id=str(row["segment_id"]))

        for i in range(len(summary)):
            for dist, j in zip(distances[i][1:], indices[i][1:]):
                weight = float(np.exp(-dist * 200))
                graph.add_edge(i, int(j), weight=weight, distance=float(dist))

        adj = nx.to_scipy_sparse_array(graph, weight="weight", dtype=np.float32)
        adjacency = csr_matrix(adj)

        adjacency_path = settings.graph_dir / "adjacency.npz"
        node_features_path = settings.graph_dir / "node_features.npy"
        edge_index_path = settings.graph_dir / "edge_index.npy"
        edge_weight_path = settings.graph_dir / "edge_weight.npy"
        metadata_path = settings.graph_dir / "graph_metadata.json"

        save_npz(adjacency_path, adjacency)

        node_features = summary[["latitude", "longitude", "speed_kph"]].to_numpy(dtype=np.float32)
        np.save(node_features_path, node_features)

        edge_index, edge_weight = self._to_edge_index(graph)
        np.save(edge_index_path, edge_index)
        np.save(edge_weight_path, edge_weight)

        metadata = {
            "nodes": graph.number_of_nodes(),
            "edges": graph.number_of_edges(),
            "k_neighbors": self.k_neighbors,
            "connectivity_components": nx.number_connected_components(graph),
            "segment_to_node": dict(zip(summary["segment_id"].astype(str), summary["node_id"].astype(int))),
        }
        metadata_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")

        LOGGER.info("graph_built", extra={"nodes": metadata["nodes"], "edges": metadata["edges"]})
        return GraphArtifacts(
            adjacency_path=adjacency_path,
            metadata_path=metadata_path,
            node_features_path=node_features_path,
            edge_index_path=edge_index_path,
            edge_weight_path=edge_weight_path,
        )

    @staticmethod
    def _to_edge_index(graph: nx.Graph) -> tuple[np.ndarray, np.ndarray]:
        src = []
        dst = []
        weight = []
        for u, v, data in graph.edges(data=True):
            w = float(data.get("weight", 1.0))
            src.extend([u, v])
            dst.extend([v, u])
            weight.extend([w, w])

        edge_index = np.vstack([np.asarray(src, dtype=np.int64), np.asarray(dst, dtype=np.int64)])
        edge_weight = np.asarray(weight, dtype=np.float32)
        return edge_index, edge_weight


def build_road_graph(csv_path: str | Path) -> GraphArtifacts:
    return RoadGraphBuilder().build(csv_path)
