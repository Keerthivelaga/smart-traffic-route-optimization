from __future__ import annotations

import base64
import hashlib
import hmac
import time

from backend_platform.config import settings
from backend_platform.security import AuthContext, security_engine


def sign(body: bytes, timestamp: str) -> str:
    message = f"{timestamp}.".encode("utf-8") + body
    digest = hmac.new(settings.hmac_secret.encode("utf-8"), message, hashlib.sha256).digest()
    return base64.b64encode(digest).decode("utf-8")


def test_signature_and_throttle():
    body = b'{"hello":"world"}'
    ts = str(int(time.time()))
    sig = sign(body, ts)
    auth = AuthContext(user_id="u1", roles={"user"}, trust_score=0.5)

    ok, reason = security_engine.authorize_request(auth, body, sig, ts)
    assert ok
    assert reason == "ok"


def test_invalid_signature_rejected():
    auth = AuthContext(user_id="u2", roles={"user"}, trust_score=0.5)
    ok, reason = security_engine.authorize_request(auth, b"{}", "bad", "1")
    assert not ok
    assert reason == "invalid_signature"
