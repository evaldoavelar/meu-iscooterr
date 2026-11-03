#!/usr/bin/env bash
# revisão só leitura por agy. uso: run-review.sh TASK-X <rodada> <review-prompt.md>  (rodar na raiz do worktree/repo)
set -u; T=$1; R=$2; P=$3; ROOT=${REPO_ROOT:-/home/evaldo/Documents/ix7}; mkdir -p "$ROOT/.omc/reviews"
agy --mode plan --sandbox -p "$(cat "$P")" < /dev/null > "$ROOT/.omc/reviews/$T-r$R.md" 2>&1; echo "EXIT $?" >> "$ROOT/.omc/reviews/$T-r$R.md"
