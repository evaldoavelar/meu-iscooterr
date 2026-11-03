#!/usr/bin/env bash
# uso: run-codex.sh TASK-X <worktree> <prompt.md> [modelo]
set -u; T=$1; WT=$2; P=$3; M=${4:-}; ROOT=${REPO_ROOT:-/home/evaldo/Documents/ix7}
LOG=$ROOT/.omc/logs/$T-codex.log; mkdir -p "$ROOT/.omc/logs"
ARGS=(-C "$WT" -s workspace-write -c model_reasoning_effort=high --output-last-message "$ROOT/.omc/logs/$T-codex.last")
[ -n "$M" ] && ARGS+=(-m "$M")
codex exec "${ARGS[@]}" "$(cat "$P")" < /dev/null > "$LOG" 2>&1; echo "EXIT $?" >> "$LOG"
