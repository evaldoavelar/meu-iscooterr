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

## 2026-11-05 — entrega
Mesclado local na `main` (merge `92eaaee`; commits 435a705 e 27b25f9, autor agy; revisor Sonnet: r1 REPROVADO → corrigido → r2 APROVADO; 34 testes OK). Hooks instalados no checkout principal; dashboard gerado com dados reais.

## 2026-11-05 — revisão Codex
Codex (Sol medium) r1 REPROVADO (4 P2) → corrigido (5db04d7) → r2 APROVADO; merge local e2cb338; 38 testes OK. Correções: ETA indeterminado se alguma janela de ritmo for 0; `state=done` exige `done`; peso NaN/inf rejeitado; `--today` inválido não grava o JSON.

## 2026-11-06 — sessão 22
Mesclados na `main` (local, sem push; `clean check` EXIT 0; revisores independentes Sonnet/agy): chip de conexão reconecta na Home + vigia de "Conectando" (F4.13), comandos genéricos desabilitados com motivo claro no BLE real (F4.13), preferências persistentes (F5.5), menores de segurança (F4.14). F4.9 (LOCK modo 2) marcado concluído (S21); F5.3 em andamento; bloqueio B2 removido.
