# TASK-D2 — Protótipo FigCraft v2 (substitui TASK-D1)
Spec: `docs/design/ux-direcao.md` (obrigatória; leia inteira) + `docs/tasks/TASK-D1.md` (ferramenta, regras, aceite — onde não conflitar). Recomece o design do zero seguindo a nova direção; pode reaproveitar a ideia do build.sh.
Arquivos permitidos: `design/**`. Não commitar. Nada de BT/ADB/rede.
Entregas extra: `design/COBERTURA.md` (matriz função × tela/estado) e `design/ANDROID-MAPA.md` (componente FigCraft → composable Compose previsto, sem escrever Kotlin).
Requisitos de build: `design/build.sh` com caminhos relativos (FIGCRAFT_BIN ou `git rev-parse --git-common-dir`/../tools/...), `mktemp`, exit 0 do zero. Sem emojis como ícones (use vetores/formas desenhadas); confira PNGs (texto cortado/sobreposto = reprovação). Interações: lock→enviando→confirmado (rotulado SIMULAÇÃO), falha→tentar de novo, atalhos para todos os estados e telas, abrir/fechar sheets, abas inferiores.
Cada controle (farol, tração, modo, cruise, partida 0, limite) precisa de variante "enviando" (laranja) distinta de "confirmado".
