# CHANGELOG — Dashboard de Progresso

## [2026-11-05] - Inicialização do Dashboard
### Adicionado
- Implementado `scripts/progress/progress.py` com subcomandos `build` e `set`.
- Implementado wrapper `scripts/progress/done.sh`.
- Implementados hooks do Git `scripts/hooks/post-commit`, `scripts/hooks/post-merge` e instalador idempotente `scripts/hooks/install.sh`.
- Implementado gerador de HTML responsivo e autônomo com suporte a tema claro/escuro via `prefers-color-scheme` e alternador manual persistido em `localStorage`.
- Visão geral pré-renderizada no servidor para funcionamento completo mesmo com JavaScript desabilitado.
- Suíte completa de testes unitários TDD em `tests/progress/test_progress.py` com 29 testes cobrindo validação, cálculos, serialização de linha única, extração de STATUS/hipóteses/git, segurança contra injeção e hooks.
- Documentação inicial em `docs/progress/README.md`.

### Corrigido (Revisão Independente Sonnet)
- Validação estrita de `--date` e validação completa pré-gravação em `cmd_set` (exit 2 e 1, arquivo intacto).
- Preservação de chaves desconhecidas em todos os níveis no serializador mantendo diff de 1 linha.
- Escape HTML de `state` em `getBadge` no dashboard.
- Redução de timeout dos hooks Git para 1 segundo.
- Suíte expandida para 34 testes unitários.
