#!/usr/bin/env bash
# uso: run-agy.sh TASK-X <worktree> <prompt.md>   (rodar com run_in_background)
set -u; T=$1; WT=$2; P=$3; REPO=$(git -C "$WT" rev-parse --show-toplevel); ROOT=${REPO_ROOT:-/home/evaldo/Documents/ix7}
LOG=$ROOT/.omc/logs/$T-agy.log; mkdir -p "$ROOT/.omc/logs"
. /home/evaldo/Documents/ix7/scripts/agents/_mon.sh; mon_register "$T" agy "$LOG" "$WT" "$P"
cd "$WT" && agy --mode accept-edits --sandbox --add-dir "$ROOT/.git" -p "$(cat "$P")" < /dev/null > "$LOG" 2>&1; echo "EXIT $?" >> "$LOG"
