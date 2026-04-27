from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import optuna


@dataclass
class HyperParams:
    hidden_dim: int
    dropout: float
    learning_rate: float
    weight_decay: float


class BayesianHyperparameterSearch:
    def __init__(self, trials: int = 20, seed: int = 42) -> None:
        self.trials = trials
        self.seed = seed

    def optimize(self, objective_fn) -> HyperParams:
        sampler = optuna.samplers.TPESampler(seed=self.seed)
        study = optuna.create_study(direction="minimize", sampler=sampler)

        def wrapped(trial: optuna.Trial) -> float:
            params = {
                "hidden_dim": trial.suggest_int("hidden_dim", 32, 128, step=16),
                "dropout": trial.suggest_float("dropout", 0.05, 0.3),
                "learning_rate": trial.suggest_float("learning_rate", 1e-4, 3e-3, log=True),
                "weight_decay": trial.suggest_float("weight_decay", 1e-6, 1e-3, log=True),
            }
            return float(objective_fn(params))

        study.optimize(wrapped, n_trials=self.trials)
        best = study.best_params

        return HyperParams(
            hidden_dim=int(best["hidden_dim"]),
            dropout=float(best["dropout"]),
            learning_rate=float(best["learning_rate"]),
            weight_decay=float(best["weight_decay"]),
        )
