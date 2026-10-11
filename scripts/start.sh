#!/usr/bin/env bash
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
# Never print the token. Keep it stable across local restarts.
if [[ -z "${ASSISTANT_API_TOKEN:-}" ]]; then
  mkdir -p data
  chmod 700 data
  if [[ ! -f data/api-token ]]; then
    (umask 077; .venv/bin/python -c 'import secrets; print(secrets.token_urlsafe(32))' > data/api-token)
  fi
  ASSISTANT_API_TOKEN="$(cat data/api-token)"
  export ASSISTANT_API_TOKEN
fi
# Bind to loopback; production HTTPS is provided by a reverse proxy.
exec .venv/bin/uvicorn backend.app:create_app --factory --host 127.0.0.1 --port "${ASSISTANT_PORT:-8000}" --workers 1 --no-access-log --timeout-graceful-shutdown 120
