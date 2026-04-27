from __future__ import annotations

from typing import Any

from backend_platform.schemas import SchemaValidationEngine


class ValidatorEngine:
    def __init__(self) -> None:
        self._engine = SchemaValidationEngine()

    def validate_payload(self, collection: str, payload: dict[str, Any]) -> dict[str, Any]:
        doc = self._engine.validate(collection, payload)
        return self._engine.serialize(doc)


validator_engine = ValidatorEngine()
