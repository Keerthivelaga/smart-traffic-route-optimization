from __future__ import annotations

import asyncio
import random
import time
from dataclasses import dataclass
from enum import Enum
from typing import Awaitable, Callable, TypeVar


T = TypeVar("T")


class CircuitState(str, Enum):
    closed = "closed"
    open = "open"
    half_open = "half_open"


@dataclass
class CircuitBreakerConfig:
    failure_threshold: int = 5
    recovery_timeout_s: float = 15.0
    half_open_max_calls: int = 3


class CircuitBreaker:
    def __init__(self, config: CircuitBreakerConfig | None = None) -> None:
        self.config = config or CircuitBreakerConfig()
        self.state = CircuitState.closed
        self.failures = 0
        self.opened_at = 0.0
        self.half_open_calls = 0

    def allow(self) -> bool:
        now = time.time()
        if self.state == CircuitState.open:
            if now - self.opened_at >= self.config.recovery_timeout_s:
                self.state = CircuitState.half_open
                self.half_open_calls = 0
            else:
                return False

        if self.state == CircuitState.half_open:
            if self.half_open_calls >= self.config.half_open_max_calls:
                return False
            self.half_open_calls += 1
        return True

    def record_success(self) -> None:
        self.failures = 0
        self.half_open_calls = 0
        self.state = CircuitState.closed

    def record_failure(self) -> None:
        self.failures += 1
        if self.failures >= self.config.failure_threshold:
            self.state = CircuitState.open
            self.opened_at = time.time()


async def with_timeout(coro: Awaitable[T], timeout_s: float) -> T:
    return await asyncio.wait_for(coro, timeout=timeout_s)


async def retry_async(
    fn: Callable[[], Awaitable[T]],
    attempts: int = 3,
    base_delay_s: float = 0.05,
    max_delay_s: float = 1.0,
) -> T:
    last_exc: Exception | None = None
    for i in range(attempts):
        try:
            return await fn()
        except Exception as exc:  # noqa: BLE001
            last_exc = exc
            if i == attempts - 1:
                break
            delay = min(max_delay_s, base_delay_s * (2**i)) + random.uniform(0, base_delay_s)
            await asyncio.sleep(delay)
    assert last_exc is not None
    raise last_exc
