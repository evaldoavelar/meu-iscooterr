#!/usr/bin/env bash
# Abre uma nova sessão do Claude Code em um konsole com --remote-control.
# uso: launch-claude-session.sh <.omc/prompts/sessao-NN-assunto.md> [--dry-run]
set -euo pipefail
ROOT=/home/evaldo/Documents/ix7
FILE=${1:-}; MODE=${2:-}
[ -n "$FILE" ] || { echo "uso: $0 <arquivo-prompt> [--dry-run]" >&2; exit 2; }
[ -f "$FILE" ] || { echo "ERRO: prompt não encontrado: $FILE" >&2; exit 2; }
FILE=$(readlink -f "$FILE")
[ -s "$FILE" ] || { echo "ERRO: prompt vazio" >&2; exit 2; }
command -v claude >/dev/null || { echo "ERRO: claude não está no PATH" >&2; exit 3; }
command -v konsole >/dev/null || { echo "ERRO: konsole não está no PATH" >&2; exit 3; }
claude --help 2>&1 | grep -q -- '--remote-control' || { echo "ERRO: claude sem --remote-control" >&2; exit 3; }
NAME="iscooter-$(basename "$FILE" .md)"
# Sem isto, a sessão herda CLAUDE_CODE_CHILD_SESSION=1 (injetado nos subprocessos do Claude Code)
# e NÃO grava transcrito -> some do /resume. FORCE_SESSION_PERSISTENCE=1 mantém o histórico.
INNER="unset CLAUDE_CODE_CHILD_SESSION; export CLAUDE_CODE_FORCE_SESSION_PERSISTENCE=1; cd '$ROOT' && claude --remote-control '$NAME' \"\$(cat '$FILE')\"; exec \$SHELL"
echo "arquivo : $FILE ($(wc -c < "$FILE") bytes)"
echo "sessão  : $NAME"
echo "workdir : $ROOT"
echo "comando : konsole --workdir $ROOT -e bash -lc \"$INNER\""
if [ "$MODE" = "--dry-run" ]; then echo "DRY-RUN OK (nada foi aberto)"; exit 0; fi
setsid nohup konsole --workdir "$ROOT" -e bash -lc "$INNER" >/dev/null 2>&1 < /dev/null &
echo "ABERTO: konsole com sessão $NAME"
