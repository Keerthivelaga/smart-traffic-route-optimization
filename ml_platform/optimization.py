from __future__ import annotations

import json
import logging
import time
import zipfile
from pathlib import Path

import joblib
import numpy as np
import torch
import torch.nn.utils.prune as prune

from ml_platform.config import settings
from ml_platform.models.hybrid_model import HybridTrafficModel
from ml_platform.utils import pick_device, write_json


LOGGER = logging.getLogger("optimization")


class OnnxHybridWrapper(torch.nn.Module):
    def __init__(self, base: HybridTrafficModel, node_features: torch.Tensor, edge_index: torch.Tensor, edge_weight: torch.Tensor) -> None:
        super().__init__()
        self.base = base
        self.register_buffer("node_features", node_features)
        self.register_buffer("edge_index", edge_index)
        self.register_buffer("edge_weight", edge_weight)

    def forward(self, x_seq: torch.Tensor, node_idx: torch.Tensor) -> torch.Tensor:
        pred, _ = self.base(x_seq, self.node_features, self.edge_index, self.edge_weight, node_idx)
        return pred


class ModelOptimizer:
    def __init__(self, artifacts_dir: str | Path | None = None) -> None:
        self.artifacts = Path(artifacts_dir or settings.artifacts)
        self.device = pick_device()

    def optimize(self) -> dict[str, float]:
        ckpt_path = self.artifacts / "deep_hybrid.pt"
        aux_path = self.artifacts / "training_aux.npz"
        metrics_path = self.artifacts / "model_metrics.json"
        runtime_model_path = self.artifacts / "runtime_fallback.joblib"

        if not ckpt_path.exists() or not aux_path.exists():
            raise FileNotFoundError("Missing training artifacts for optimization")

        checkpoint = torch.load(ckpt_path, map_location="cpu")
        aux = np.load(aux_path)
        node_features = torch.from_numpy(aux["node_features"]).float()
        edge_index = torch.from_numpy(aux["edge_index"]).long()
        edge_weight = torch.from_numpy(aux["edge_weight"]).float()

        model = HybridTrafficModel(
            feature_dim=int(checkpoint["feature_dim"]),
            node_feat_dim=int(checkpoint["node_feat_dim"]),
            hidden_dim=int(checkpoint["hidden_dim"]),
        )
        model.load_state_dict(checkpoint["state_dict"])
        model.eval()

        self._prune_model(model)
        quantized = torch.quantization.quantize_dynamic(model, {torch.nn.Linear, torch.nn.LSTM}, dtype=torch.qint8)

        feature_dim = int(checkpoint["feature_dim"])
        benchmark_ms = self._benchmark(
            quantized,
            node_features,
            edge_index,
            edge_weight,
            sequence_length=int(checkpoint["sequence_length"]),
            feature_dim=feature_dim,
        )
        onnx_path = self._export_onnx(
            quantized,
            node_features,
            edge_index,
            edge_weight,
            sequence_length=int(checkpoint["sequence_length"]),
            feature_dim=feature_dim,
        )
        tflite_path = self._export_tflite_surrogate(runtime_model_path)

        compressed_path = self.artifacts / "model_bundle.zip"
        self._compress([onnx_path, tflite_path, runtime_model_path, ckpt_path, metrics_path], compressed_path)

        metrics = {
            "optimization_benchmark_latency_ms": float(benchmark_ms),
            "onnx_size_mb": float(onnx_path.stat().st_size / (1024 * 1024)),
            "tflite_size_mb": float(tflite_path.stat().st_size / (1024 * 1024)),
            "bundle_size_mb": float(compressed_path.stat().st_size / (1024 * 1024)),
        }

        existing = {}
        if metrics_path.exists():
            existing = json.loads(metrics_path.read_text(encoding="utf-8"))
        existing.update(metrics)
        write_json(metrics_path, existing)

        return metrics

    @staticmethod
    def _prune_model(model: torch.nn.Module, amount: float = 0.2) -> None:
        parameters_to_prune = []
        for module in model.modules():
            if isinstance(module, torch.nn.Linear):
                parameters_to_prune.append((module, "weight"))
        if parameters_to_prune:
            prune.global_unstructured(parameters_to_prune, pruning_method=prune.L1Unstructured, amount=amount)
            for mod, _ in parameters_to_prune:
                prune.remove(mod, "weight")

    def _benchmark(
        self,
        model: torch.nn.Module,
        node_features: torch.Tensor,
        edge_index: torch.Tensor,
        edge_weight: torch.Tensor,
        sequence_length: int,
        feature_dim: int,
    ) -> float:
        model.eval()
        wrapper = OnnxHybridWrapper(model, node_features, edge_index, edge_weight).to(self.device)

        x = torch.randn(128, sequence_length, feature_dim, device=self.device)
        idx = torch.randint(0, node_features.shape[0], (128,), device=self.device)

        with torch.no_grad():
            for _ in range(8):
                _ = wrapper(x, idx)

        start = time.perf_counter()
        with torch.no_grad():
            for _ in range(32):
                _ = wrapper(x, idx)
        elapsed = time.perf_counter() - start
        per_batch_ms = (elapsed / 32) * 1000
        return per_batch_ms

    def _export_onnx(
        self,
        model: torch.nn.Module,
        node_features: torch.Tensor,
        edge_index: torch.Tensor,
        edge_weight: torch.Tensor,
        sequence_length: int,
        feature_dim: int,
    ) -> Path:
        model.eval()
        wrapper = OnnxHybridWrapper(model, node_features, edge_index, edge_weight)
        onnx_path = self.artifacts / "model.onnx"

        dummy_x = torch.randn(4, sequence_length, feature_dim)
        dummy_idx = torch.randint(0, node_features.shape[0], (4,))

        torch.onnx.export(
            wrapper,
            (dummy_x, dummy_idx),
            onnx_path.as_posix(),
            input_names=["x_seq", "node_idx"],
            output_names=["prediction"],
            dynamic_axes={
                "x_seq": {0: "batch"},
                "node_idx": {0: "batch"},
                "prediction": {0: "batch"},
            },
            opset_version=17,
        )
        return onnx_path

    def _export_tflite_surrogate(self, runtime_model_path: Path) -> Path:
        runtime_model = joblib.load(runtime_model_path)
        x = np.load("data/processed/features_runtime.npy")
        y = runtime_model.predict(x)

        import tensorflow as tf

        tf.random.set_seed(settings.seed)
        model = tf.keras.Sequential(
            [
                tf.keras.layers.Input(shape=(x.shape[1],)),
                tf.keras.layers.Dense(128, activation="relu"),
                tf.keras.layers.Dense(64, activation="relu"),
                tf.keras.layers.Dense(1),
            ]
        )
        model.compile(optimizer=tf.keras.optimizers.Adam(1e-3), loss="mse")
        model.fit(x, y, epochs=5, batch_size=1024, verbose=0)

        converter = tf.lite.TFLiteConverter.from_keras_model(model)
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        tflite_model = converter.convert()

        path = self.artifacts / "model.tflite"
        path.write_bytes(tflite_model)
        return path

    @staticmethod
    def _compress(files: list[Path], dest_zip: Path) -> None:
        with zipfile.ZipFile(dest_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
            for file in files:
                if file.exists():
                    zf.write(file, arcname=file.name)


def optimize_models() -> dict[str, float]:
    return ModelOptimizer().optimize()
