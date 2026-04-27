from __future__ import annotations

import logging

from ml_platform.optimization import optimize_models


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    metrics = optimize_models()
    print(metrics)


if __name__ == "__main__":
    main()
