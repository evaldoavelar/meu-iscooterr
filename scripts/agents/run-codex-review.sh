#!/usr/bin/env bash
# revisão só leitura por Codex. uso: run-codex-review.sh TASK-X <rodada> <prompt.md> [modelo]
set -u; T=$1; R=$2; P=$3; M=${4:-}; ROOT=/home/evaldo/Documents/ix7; mkdir -p "$ROOT/.omc/reviews"
OUT=$ROOT/.omc/reviews/$T-r$R.md; ARGS=(-C "$ROOT" -s read-only -c model_reasoning_effort=high --output-last-message "$OUT")
[ -n "$M" ] && ARGS+=(-m "$M")
codex exec "${ARGS[@]}" "$(cat "$P")" < /dev/null > "$ROOT/.omc/logs/$T-r$R-codex.log" 2>&1; echo "EXIT $?" >> "$ROOT/.omc/logs/$T-r$R-codex.log"
