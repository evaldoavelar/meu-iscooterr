#!/usr/bin/env bash
set -eu

if [ $# -lt 1 ]; then
    echo "Uso: $0 <id> [estado=done]" >&2
    exit 2
fi

ID="$1"
STATE="${2:-done}"

ROOT="$(git rev-parse --show-toplevel)"
exec python3 "$ROOT/scripts/progress/progress.py" --root "$ROOT" set "$ID" "$STATE"
