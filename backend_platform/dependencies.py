from __future__ import annotations

import time
from dataclasses import dataclass

from fastapi import HTTPException, Request, status
from firebase_admin import auth

from backend_platform.config import settings
from backend_platform.security import AuthContext


@dataclass
class CachedAuth:
    ctx: AuthContext
    expires_at: float


class FirebaseAuthVerifier:
    def __init__(self, cache_ttl_seconds: int = 120) -> None:
        self.cache_ttl_seconds = cache_ttl_seconds
        self.cache: dict[str, CachedAuth] = {}

    def verify_request(self, request: Request) -> AuthContext:
        authorization = request.headers.get("authorization", "")
        if authorization.lower().startswith("bearer "):
            token = authorization.split(" ", 1)[1].strip()
            return self._verify_bearer_token(token)

        if settings.env != "prod" and request.headers.get("x-dev-auth") == "true":
            # Dev-only fallback to simplify local e2e without exposing it in production.
            user_id = request.headers.get("x-dev-user-id", "dev-user")
            roles = {r.strip() for r in request.headers.get("x-dev-roles", "admin,service").split(",") if r.strip()}
            trust = float(request.headers.get("x-dev-trust-score", "0.9"))
            return AuthContext(user_id=user_id, roles=roles, trust_score=max(0.0, min(1.0, trust)))

        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="missing_bearer_token")

    def _verify_bearer_token(self, token: str) -> AuthContext:
        now = time.time()
        cached = self.cache.get(token)
        if cached and cached.expires_at > now:
            return cached.ctx

        try:
            decoded = auth.verify_id_token(token, check_revoked=True)
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_token") from exc

        uid = str(decoded.get("uid") or decoded.get("sub") or "")
        if not uid:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_token_uid")

        roles_claim = decoded.get("roles", [])
        roles: set[str]
        if isinstance(roles_claim, str):
            roles = {roles_claim}
        elif isinstance(roles_claim, list):
            roles = {str(role) for role in roles_claim}
        else:
            roles = set()

        if decoded.get("admin") is True:
            roles.add("admin")

        trust = float(decoded.get("trust_score", 0.5))
        ctx = AuthContext(user_id=uid, roles=roles or {"user"}, trust_score=max(0.0, min(1.0, trust)))

        self.cache[token] = CachedAuth(ctx=ctx, expires_at=now + self.cache_ttl_seconds)
        return ctx


auth_verifier = FirebaseAuthVerifier(cache_ttl_seconds=settings.auth_cache_ttl_seconds)


def get_auth_context(request: Request) -> AuthContext:
    return auth_verifier.verify_request(request)
