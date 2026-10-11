# syntax=docker/dockerfile:1
FROM python:3.12-slim
WORKDIR /app
COPY requirements.lock .
RUN --mount=type=secret,id=ca_bundle --mount=type=bind,source=.,target=/build-context,ro \
    if [ -d /build-context/.wheelhouse ]; then \
      pip install --no-cache-dir --no-index --find-links=/build-context/.wheelhouse -r requirements.lock; \
    elif [ -f /run/secrets/ca_bundle ]; then \
      PIP_CERT=/run/secrets/ca_bundle pip install --no-cache-dir -r requirements.lock; \
    else pip install --no-cache-dir -r requirements.lock; fi
RUN useradd --uid 10001 --create-home assistant && mkdir /data && chown assistant:assistant /data
COPY --chown=assistant:assistant backend ./backend
USER assistant
ENV ASSISTANT_DB=/data/assistant.db PYTHONUNBUFFERED=1
EXPOSE 8000
CMD ["uvicorn", "backend.app:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-access-log", "--timeout-graceful-shutdown", "120"]
