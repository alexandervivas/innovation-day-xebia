#!/usr/bin/env bash
# Launches the core-banking-mcp stdio server. Nothing but the server's own MCP JSON-RPC output may
# reach stdout: sbt's own build output is redirected to stderr, and .env (if present) is sourced
# before the server starts so CORE_ENV and DATABASE_URL are available to it.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

if [ -f .env ]; then
  set -a
  source .env
  set +a
fi

STAGE_SCRIPT="target/universal/stage/bin/core-banking-mcp"

if [ ! -x "$STAGE_SCRIPT" ]; then
  sbt -batch stage >&2
fi

exec "$STAGE_SCRIPT" "$@"
