FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

COPY ml_platform/requirements.inference.txt /tmp/requirements.txt
RUN pip install --upgrade pip && pip install -r /tmp/requirements.txt

COPY ml_platform /app/ml_platform
COPY artifacts /app/artifacts

RUN useradd -u 10001 -m appuser && chown -R appuser:appuser /app
USER appuser

ENV PORT=8090 \
    PYTHONPATH=/app

CMD ["sh", "-c", "uvicorn ml_platform.inference_server:app --host 0.0.0.0 --port ${PORT:-8090}"]
