#!/usr/bin/env bash
# tarefa mecânica com Claude Haiku. uso: run-haiku.sh TASK-X <prompt.md>   (cwd = raiz do repo)
set -u; T=$1; P=$2; ROOT=/home/evaldo/Documents/ix7; LOG=$ROOT/.omc/logs/$T-haiku.log
. /home/evaldo/Documents/ix7/scripts/agents/_mon.sh; mon_register "$T" haiku "$LOG" "$ROOT"
cd "$ROOT" && claude -p --model claude-haiku-5-5 --permission-mode acceptEdits "$(cat "$P")" < /dev/null > "$LOG" 2>&1; echo "EXIT $?" >> "$LOG"
