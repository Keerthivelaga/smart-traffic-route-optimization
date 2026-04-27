from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import torch
import torch.nn as nn
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from torch.utils.data import DataLoader, Dataset

from ml_platform.config import settings
from ml_platform.data_engineering import DatasetBundle
from ml_platform.models.hybrid_model import HybridTrafficModel
from ml_platform.models.meta_learner import GradientBoostingMetaLearner
from ml_platform.training.dataset_versioning import DatasetVersioning
from ml_platform.training.experiment_tracker import ExperimentTracker
from ml_platform.training.hyperopt import BayesianHyperparameterSearch
from ml_platform.utils import pick_device, set_seed, write_json


LOGGER = logging.getLogger("trainer")


class SequenceDataset(Dataset):
    def __init__(self, x_seq: np.ndarray, y: np.ndarray, node_idx: np.ndarray) -> None:
        self.x_seq = torch.from_numpy(x_seq).float()
        self.y = torch.from_numpy(y).float()
        self.node_idx = torch.from_numpy(node_idx).long()

    def __len__(self) -> int:
        return len(self.y)

    def __getitem__(self, idx: int):
        return self.x_seq[idx], self.y[idx], self.node_idx[idx]


@dataclass
class TrainingArtifacts:
    deep_model_path: Path
    meta_model_path: Path
    metrics_path: Path


class HybridTrainingSystem:
    def __init__(self, sequence_length: int = 12) -> None:
        self.sequence_length = sequence_length
        self.device = pick_device()
        self.dataset_versioning = DatasetVersioning()
        self.tracker = ExperimentTracker()

    def train(
        self,
        bundle: DatasetBundle,
        graph_node_features_path: str | Path,
        graph_metadata_path: str | Path,
        edge_index_path: str | Path,
        edge_weight_path: str | Path,
    ) -> TrainingArtifacts:
        set_seed(settings.seed)
        Path(settings.artifacts).mkdir(parents=True, exist_ok=True)

        x_seq, y, node_idx, seq_times = self._build_sequences(bundle, graph_metadata_path)
        if len(y) < 100:
            raise ValueError("Not enough sequence samples to train")

        ds_ver = self.dataset_versioning.register("data/processed/features_runtime.npy", rows=len(y), cols=x_seq.shape[-1])

        node_features = torch.from_numpy(np.load(graph_node_features_path)).float().to(self.device)
        edge_index = torch.from_numpy(np.load(edge_index_path)).long().to(self.device)
        edge_weight = torch.from_numpy(np.load(edge_weight_path)).float().to(self.device)

        folds = self._time_series_folds(seq_times, n_splits=settings.n_splits)
        if len(folds) < 2:
            raise ValueError("Unable to build temporal folds")

        run = self.tracker.start_run(
            "hybrid_training",
            {
                "dataset_version": ds_ver.version_id,
                "rows": int(len(y)),
                "feature_dim": int(x_seq.shape[-1]),
                "seq_len": int(x_seq.shape[1]),
                "folds": len(folds),
            },
        )

        train_idx, val_idx = folds[0]
        hyper = BayesianHyperparameterSearch(trials=settings.bayes_trials, seed=settings.seed)
        best_params = hyper.optimize(
            lambda params: self._objective(
                params,
                x_seq[train_idx],
                y[train_idx],
                node_idx[train_idx],
                x_seq[val_idx],
                y[val_idx],
                node_idx[val_idx],
                node_features,
                edge_index,
                edge_weight,
            )
        )

        self.tracker.log_metrics(
            run.run_id,
            {
                "best_hidden_dim": float(best_params.hidden_dim),
                "best_learning_rate": float(best_params.learning_rate),
                "best_weight_decay": float(best_params.weight_decay),
            },
        )

        oof_fused = np.zeros((len(y), best_params.hidden_dim * 2), dtype=np.float32)
        oof_base = np.zeros((len(y),), dtype=np.float32)
        fold_metrics: list[dict[str, float]] = []

        for fold_no, (tr, va) in enumerate(folds, start=1):
            model, metrics, pred, fused = self._train_fold(
                x_seq[tr],
                y[tr],
                node_idx[tr],
                x_seq[va],
                y[va],
                node_idx[va],
                node_features,
                edge_index,
                edge_weight,
                hidden_dim=best_params.hidden_dim,
                lr=best_params.learning_rate,
                wd=best_params.weight_decay,
            )
            fold_metrics.append(metrics)
            oof_fused[va] = fused
            oof_base[va] = pred
            self.tracker.log_metrics(
                run.run_id,
                {
                    f"fold_{fold_no}_mae": metrics["mae"],
                    f"fold_{fold_no}_rmse": metrics["rmse"],
                    f"fold_{fold_no}_mape": metrics["mape"],
                    f"fold_{fold_no}_r2": metrics["r2"],
                    f"fold_{fold_no}_latency_ms": metrics["latency_ms"],
                },
                step=fold_no,
            )

        meta_X = np.hstack([oof_fused, oof_base[:, None]])
        meta = GradientBoostingMetaLearner()
        meta.fit(meta_X, y)

        final_model = self._train_full(
            x_seq,
            y,
            node_idx,
            node_features,
            edge_index,
            edge_weight,
            hidden_dim=best_params.hidden_dim,
            lr=best_params.learning_rate,
            wd=best_params.weight_decay,
        )

        deep_model_path = Path(settings.artifacts) / "deep_hybrid.pt"
        meta_model_path = Path(settings.artifacts) / "model_meta.joblib"
        metrics_path = Path(settings.artifacts) / "model_metrics.json"
        aux_path = Path(settings.artifacts) / "training_aux.npz"

        torch.save(
            {
                "state_dict": final_model.state_dict(),
                "feature_dim": x_seq.shape[-1],
                "node_feat_dim": node_features.shape[-1],
                "hidden_dim": best_params.hidden_dim,
                "sequence_length": self.sequence_length,
            },
            deep_model_path,
        )
        meta.save(meta_model_path)

        np.savez_compressed(
            aux_path,
            edge_index=edge_index.detach().cpu().numpy(),
            edge_weight=edge_weight.detach().cpu().numpy(),
            node_features=node_features.detach().cpu().numpy(),
        )

        aggregate_metrics = self._aggregate_fold_metrics(fold_metrics)
        write_json(metrics_path, aggregate_metrics)
        self.tracker.finish_run(run.run_id)

        # Runtime fallback model for backend prediction API.
        from sklearn.ensemble import HistGradientBoostingRegressor
        import joblib

        runtime_model = HistGradientBoostingRegressor(max_depth=8, random_state=settings.seed)
        runtime_model.fit(bundle.features, bundle.targets)
        joblib.dump(runtime_model, Path(settings.artifacts) / "runtime_fallback.joblib")

        return TrainingArtifacts(deep_model_path=deep_model_path, meta_model_path=meta_model_path, metrics_path=metrics_path)

    def _objective(
        self,
        params: dict[str, Any],
        x_train: np.ndarray,
        y_train: np.ndarray,
        n_train: np.ndarray,
        x_val: np.ndarray,
        y_val: np.ndarray,
        n_val: np.ndarray,
        node_features: torch.Tensor,
        edge_index: torch.Tensor,
        edge_weight: torch.Tensor,
    ) -> float:
        model = HybridTrafficModel(
            feature_dim=x_train.shape[-1],
            node_feat_dim=node_features.shape[-1],
            hidden_dim=int(params["hidden_dim"]),
        ).to(self.device)

        opt = torch.optim.AdamW(model.parameters(), lr=float(params["learning_rate"]), weight_decay=float(params["weight_decay"]))
        criterion = nn.MSELoss()

        tr_loader = DataLoader(SequenceDataset(x_train, y_train, n_train), batch_size=512, shuffle=True)
        val_loader = DataLoader(SequenceDataset(x_val, y_val, n_val), batch_size=512, shuffle=False)

        for _ in range(3):
            model.train()
            for xb, yb, nb in tr_loader:
                xb = xb.to(self.device)
                yb = yb.to(self.device)
                nb = nb.to(self.device)
                opt.zero_grad(set_to_none=True)
                pred, _ = model(xb, node_features, edge_index, edge_weight, nb)
                loss = criterion(pred, yb)
                loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
                opt.step()

        model.eval()
        preds = []
        trues = []
        with torch.no_grad():
            for xb, yb, nb in val_loader:
                xb = xb.to(self.device)
                nb = nb.to(self.device)
                pred, _ = model(xb, node_features, edge_index, edge_weight, nb)
                preds.append(pred.detach().cpu().numpy())
                trues.append(yb.numpy())

        y_pred = np.concatenate(preds)
        y_true = np.concatenate(trues)
        rmse = np.sqrt(mean_squared_error(y_true, y_pred))
        return float(rmse)

    def _train_fold(
        self,
        x_train: np.ndarray,
        y_train: np.ndarray,
        n_train: np.ndarray,
        x_val: np.ndarray,
        y_val: np.ndarray,
        n_val: np.ndarray,
        node_features: torch.Tensor,
        edge_index: torch.Tensor,
        edge_weight: torch.Tensor,
        hidden_dim: int,
        lr: float,
        wd: float,
    ):
        model = HybridTrafficModel(
            feature_dim=x_train.shape[-1],
            node_feat_dim=node_features.shape[-1],
            hidden_dim=hidden_dim,
        ).to(self.device)

        optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=wd)
        scheduler = torch.optim.lr_scheduler.ReduceLROnPlateau(optimizer, mode="min", factor=0.5, patience=2)
        criterion = nn.SmoothL1Loss()

        train_loader = DataLoader(SequenceDataset(x_train, y_train, n_train), batch_size=settings.batch_size, shuffle=True)
        val_loader = DataLoader(SequenceDataset(x_val, y_val, n_val), batch_size=settings.batch_size, shuffle=False)

        scaler = torch.cuda.amp.GradScaler(enabled=self.device.type == "cuda")

        best_rmse = float("inf")
        best_state = None
        no_improve = 0

        for _epoch in range(settings.epochs):
            model.train()
            for xb, yb, nb in train_loader:
                xb = xb.to(self.device)
                yb = yb.to(self.device)
                nb = nb.to(self.device)

                optimizer.zero_grad(set_to_none=True)
                with torch.autocast(device_type=self.device.type, dtype=torch.float16, enabled=self.device.type == "cuda"):
                    pred, _ = model(xb, node_features, edge_index, edge_weight, nb)
                    loss = criterion(pred, yb)

                scaler.scale(loss).backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
                scaler.step(optimizer)
                scaler.update()

            metrics, _, _ = self._evaluate(model, val_loader, node_features, edge_index, edge_weight)
            scheduler.step(metrics["rmse"])

            if metrics["rmse"] < best_rmse:
                best_rmse = metrics["rmse"]
                best_state = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
                no_improve = 0
            else:
                no_improve += 1
                if no_improve >= settings.patience:
                    break

        if best_state is not None:
            model.load_state_dict(best_state)

        metrics, preds, fused = self._evaluate(model, val_loader, node_features, edge_index, edge_weight)
        return model, metrics, preds, fused

    def _train_full(
        self,
        x: np.ndarray,
        y: np.ndarray,
        node_idx: np.ndarray,
        node_features: torch.Tensor,
        edge_index: torch.Tensor,
        edge_weight: torch.Tensor,
        hidden_dim: int,
        lr: float,
        wd: float,
    ) -> HybridTrafficModel:
        model = HybridTrafficModel(feature_dim=x.shape[-1], node_feat_dim=node_features.shape[-1], hidden_dim=hidden_dim).to(self.device)
        optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=wd)
        criterion = nn.SmoothL1Loss()
        loader = DataLoader(SequenceDataset(x, y, node_idx), batch_size=settings.batch_size, shuffle=True)

        scaler = torch.cuda.amp.GradScaler(enabled=self.device.type == "cuda")
        for _ in range(min(settings.epochs, 12)):
            model.train()
            for xb, yb, nb in loader:
                xb = xb.to(self.device)
                yb = yb.to(self.device)
                nb = nb.to(self.device)
                optimizer.zero_grad(set_to_none=True)
                with torch.autocast(device_type=self.device.type, dtype=torch.float16, enabled=self.device.type == "cuda"):
                    pred, _ = model(xb, node_features, edge_index, edge_weight, nb)
                    loss = criterion(pred, yb)
                scaler.scale(loss).backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
                scaler.step(optimizer)
                scaler.update()
        return model

    def _evaluate(
        self,
        model: HybridTrafficModel,
        loader: DataLoader,
        node_features: torch.Tensor,
        edge_index: torch.Tensor,
        edge_weight: torch.Tensor,
    ) -> tuple[dict[str, float], np.ndarray, np.ndarray]:
        model.eval()
        preds = []
        trues = []
        fused_all = []

        start = time.perf_counter()
        with torch.no_grad():
            for xb, yb, nb in loader:
                xb = xb.to(self.device)
                nb = nb.to(self.device)
                pred, fused = model(xb, node_features, edge_index, edge_weight, nb)
                preds.append(pred.detach().cpu().numpy())
                fused_all.append(fused.detach().cpu().numpy())
                trues.append(yb.numpy())
        duration = time.perf_counter() - start

        y_pred = np.concatenate(preds)
        y_true = np.concatenate(trues)
        fused = np.concatenate(fused_all)

        mae = mean_absolute_error(y_true, y_pred)
        rmse = np.sqrt(mean_squared_error(y_true, y_pred))
        mape = np.mean(np.abs((y_true - y_pred) / np.clip(y_true, 1e-5, None))) * 100
        r2 = r2_score(y_true, y_pred)
        latency_ms = (duration / max(len(y_true), 1)) * 1000

        metrics = {
            "mae": float(mae),
            "rmse": float(rmse),
            "mape": float(mape),
            "r2": float(r2),
            "latency_ms": float(latency_ms),
        }
        return metrics, y_pred, fused

    def _build_sequences(
        self,
        bundle: DatasetBundle,
        graph_metadata_path: str | Path,
    ) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
        metadata = json.loads(Path(graph_metadata_path).read_text(encoding="utf-8"))
        mapping = {str(k): int(v) for k, v in metadata.get("segment_to_node", {}).items()}

        features = bundle.features
        targets = bundle.targets
        segments = bundle.segment_index
        timestamps = bundle.timestamps

        per_segment: dict[str, list[int]] = {}
        for i, seg in enumerate(segments):
            per_segment.setdefault(str(seg), []).append(i)

        x_list = []
        y_list = []
        n_list = []
        t_list = []

        for seg, idxs in per_segment.items():
            if len(idxs) <= self.sequence_length:
                continue
            for pos in range(self.sequence_length, len(idxs)):
                seq_idx = idxs[pos - self.sequence_length : pos]
                target_idx = idxs[pos]
                x_list.append(features[seq_idx])
                y_list.append(targets[target_idx])
                n_list.append(mapping.get(seg, 0))
                t_list.append(timestamps[target_idx])

        x_seq = np.asarray(x_list, dtype=np.float32)
        y = np.asarray(y_list, dtype=np.float32)
        node_idx = np.asarray(n_list, dtype=np.int64)
        seq_times = np.asarray(t_list, dtype="datetime64[ns]")

        Path("data/processed").mkdir(parents=True, exist_ok=True)
        np.save("data/processed/features_runtime.npy", features)

        return x_seq, y, node_idx, seq_times

    @staticmethod
    def _time_series_folds(seq_times: np.ndarray, n_splits: int) -> list[tuple[np.ndarray, np.ndarray]]:
        order = np.argsort(seq_times)
        n = len(order)
        fold_points = np.linspace(0, n, num=n_splits + 2, dtype=int)

        folds: list[tuple[np.ndarray, np.ndarray]] = []
        for i in range(1, len(fold_points) - 1):
            train_end = fold_points[i]
            val_end = fold_points[i + 1]
            train_idx = order[:train_end]
            val_idx = order[train_end:val_end]
            if len(train_idx) == 0 or len(val_idx) == 0:
                continue
            folds.append((train_idx, val_idx))
        return folds

    @staticmethod
    def _aggregate_fold_metrics(fold_metrics: list[dict[str, float]]) -> dict[str, float]:
        keys = ["mae", "rmse", "mape", "r2", "latency_ms"]
        out: dict[str, float] = {}
        for key in keys:
            values = [m[key] for m in fold_metrics]
            out[f"{key}_mean"] = float(np.mean(values))
            out[f"{key}_std"] = float(np.std(values))
        return out


def train_hybrid_model(
    bundle: DatasetBundle,
    graph_node_features_path: str | Path,
    graph_metadata_path: str | Path,
    edge_index_path: str | Path,
    edge_weight_path: str | Path,
) -> TrainingArtifacts:
    system = HybridTrainingSystem()
    return system.train(bundle, graph_node_features_path, graph_metadata_path, edge_index_path, edge_weight_path)
