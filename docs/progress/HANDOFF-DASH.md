# HANDOFF — Dashboard de Progresso (TASK-DASH)

## Comandos Executados
1. `python3 -m unittest discover -s tests/progress` — 29 testes unitários TDD executados (todos verdes, 0.87s).
2. `python3 scripts/progress/progress.py build` — dashboard `docs/progress/dashboard.html` gerado com sucesso (< 1s, exit code 0).
3. `chmod +x scripts/progress/progress.py scripts/progress/done.sh scripts/hooks/*` — permissões de execução aplicadas aos scripts e hooks.

## Arquivos Entregues
- `scripts/progress/progress.py`: gerador completo (subcomandos `build` e `set`, funções puras de cálculo §6, validação, extração de STATUS/pesquisa/git, escape HTML/JSON e escrita atômica).
- `scripts/progress/done.sh`: wrapper bash `set -eu` para atualização em 1 comando.
- `scripts/hooks/post-commit`, `scripts/hooks/post-merge`: hooks assíncronos e silenciosos que ignoram worktrees `.wc/*` e sempre saem 0.
- `scripts/hooks/install.sh`: instalador/desinstalador idempotente dos hooks com proteção para não sobrescrever hooks alheios.
- `tests/progress/test_progress.py`: suíte com 29 testes (parse, regras de validação, cálculos, janelas de ritmo/ETA, diff de 1 linha no `set`, escape XSS, isolamento em worktrees).
- `docs/progress/README.md`: documentação operacional, formato do JSON, método do ETA e limitações.
- `docs/progress/CHANGELOG.md`: registro inicial da entrega.
- `docs/progress/dashboard.html`: HTML autônomo gerado e ignorado pelo `.gitignore`.

## Limitações
- ETA é uma projeção linear baseada no ritmo histórico de conclusão de pesos e não considera períodos de espera por aprovação do proprietário ou limitações de cota de serviços externos.
- Pesos representam estimativas do coordenador.
- Granularidade temporal do ritmo é diária (baseada no campo `done: AAAA-MM-DD`).
