from __future__ import annotations

import time
from dataclasses import dataclass
from enum import Enum


class CircuitState(str, Enum):
    closed = "closed"
    open = "open"
    half_open = "half_open"


@dataclass
class CircuitConfig:
    failure_threshold: int = 5
    recovery_timeout_s: float = 10.0
    half_open_calls: int = 2


class CircuitBreaker:
    def __init__(self, config: CircuitConfig | None = None) -> None:
        self.config = config or CircuitConfig()
        self.state = CircuitState.closed
        self.failures = 0
        self.opened_at = 0.0
        self.current_half_open_calls = 0

    def allow(self) -> bool:
        now = time.time()
        if self.state == CircuitState.open:
            if now - self.opened_at >= self.config.recovery_timeout_s:
                self.state = CircuitState.half_open
                self.current_half_open_calls = 0
            else:
                return False

        if self.state == CircuitState.half_open:
            if self.current_half_open_calls >= self.config.half_open_calls:
                return False
            self.current_half_open_calls += 1
        return True

    def record_success(self) -> None:
        self.state = CircuitState.closed
        self.failures = 0
        self.current_half_open_calls = 0

    def record_failure(self) -> None:
        self.failures += 1
        if self.failures >= self.config.failure_threshold:
            self.state = CircuitState.open
            self.opened_at = time.time()
