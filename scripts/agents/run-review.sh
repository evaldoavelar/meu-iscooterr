#!/usr/bin/env bash
# revisão só leitura por agy. uso: run-review.sh TASK-X <rodada> <review-prompt.md>  (rodar na raiz do worktree/repo)
set -u; T=$1; R=$2; P=$3; ROOT=${REPO_ROOT:-/home/evaldo/Documents/ix7}; mkdir -p "$ROOT/.omc/reviews"
. /home/evaldo/Documents/ix7/scripts/agents/_mon.sh; mon_register "$T-r$R" agy-review "$ROOT/.omc/reviews/$T-r$R.md" "$ROOT"
agy --mode plan --sandbox -p "$(cat "$P")" < /dev/null > "$ROOT/.omc/reviews/$T-r$R.md" 2>&1; echo "EXIT $?" >> "$ROOT/.omc/reviews/$T-r$R.md"
