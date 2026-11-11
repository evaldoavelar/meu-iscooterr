# design/
Protótipo FigCraft do app. Fonte da verdade: `lib.py` (tokens/ícones), `home.py` (tela principal), `build.py` → `scripts/home.json` → `home.figcraft`. Rodar `./run.sh` (PNG em `out/escuro` e `out/claro`).
Direção: `docs/design/ux-direcao.md`. Estados que simulam a telemetria são só demonstração.

Mockups de telas já implementadas foram removidos de `design/mockups/` (S45, a pedido do proprietário); recuperáveis pelo histórico git (ex.: `git show 20b0499^:design/mockups/<arquivo>`). Referências a `design/mockups/*` em ADRs/STATUS/RESUME antigos apontam para esses arquivos removidos. Ficam em `design/mockups/` só os ícones do app e os mockups da S45 em implementação; `design/out/` (referência dos testes de screenshot) não foi tocado.
