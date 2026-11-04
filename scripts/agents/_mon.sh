# registra o processo atual ($$) no monitor e garante o console aberto. uso: mon_register NOME TIPO LOG [WORKTREE]
mon_register() { # NOME TIPO LOG [WORKTREE [PROMPT [RESULTADO]]]
  local M=/home/evaldo/Documents/ix7/scripts/agents/agent-monitor.py
  python3 -I "$M" --register "$1" "$2" "$3" "$$" "${4:-}" "${5:-}" "${6:-}" 2>/dev/null && python3 -I "$M" --ensure 2>/dev/null
  return 0
}
