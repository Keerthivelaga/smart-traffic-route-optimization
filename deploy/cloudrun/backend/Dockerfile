FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY backend_platform/requirements.backend.txt /tmp/requirements.txt
RUN pip install --upgrade pip && pip install -r /tmp/requirements.txt

COPY backend_platform /app/backend_platform
COPY config /app/config
COPY artifacts /app/artifacts
COPY data /app/data
COPY firebase.json /app/firebase.json

RUN useradd -u 10001 -m appuser && chown -R appuser:appuser /app
USER appuser

ENV ENV=prod \
    PORT=8080 \
    PYTHONPATH=/app

CMD ["sh", "-c", "uvicorn backend_platform.app:app --host 0.0.0.0 --port ${PORT:-8080}"]
