#!/usr/bin/env bash
# revisão só leitura por Codex. uso: run-codex-review.sh TASK-X <rodada> <prompt.md> [modelo]
# Economia de cota (sessão 12): revisor continua sendo o Sol (decisão do proprietário), com esforço medium por padrão;
# CODEX_EFFORT=high só no fechamento de sprint; Luna (gpt-6-luna, mais barato) opcional no 4º argumento em revisão rotineira. Prompt enxuto: docs/process/codex-review-prompt-modelo.md.
set -u; T=$1; R=$2; P=$3; M=${4:-gpt-6.1-sol}; E=${CODEX_EFFORT:-medium}; ROOT=/home/evaldo/Documents/ix7; mkdir -p "$ROOT/.omc/reviews"
OUT=$ROOT/.omc/reviews/$T-r$R.md; ARGS=(-C "$ROOT" -s read-only -c model_reasoning_effort=$E --output-last-message "$OUT")
. /home/evaldo/Documents/ix7/scripts/agents/_mon.sh; case "$M" in *sol*) L=Sol;; *luna*) L=Luna;; *) L=Codex;; esac; mon_register "$T-r$R" "$L · codex-review" "$ROOT/.omc/logs/$T-r$R-codex.log" "$ROOT" "$P" "$OUT"
[ -n "$M" ] && ARGS+=(-m "$M")
codex exec "${ARGS[@]}" "$(cat "$P")" < /dev/null > "$ROOT/.omc/logs/$T-r$R-codex.log" 2>&1; echo "EXIT $?" >> "$ROOT/.omc/logs/$T-r$R-codex.log"
