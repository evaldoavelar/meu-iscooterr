# Fase 2 — Protótipo FigCraft (Sprint 3)

Entrada: Fase 1 validada em captura real (`docs/research/parts/S2-captura-resultados.md`). Regra 2: sem Compose antes da validação formal do protótipo pelo proprietário.

## Fatos do protocolo que moldam o design `[CONFIRMADO]`
- Este patinete **não tem PIN** → bloqueio/desbloqueio sem digitar nada, um toque. Se `lock_has_pwd=1` no futuro, tela de cadastro único do PIN (Keystore), nunca pedir de novo.
- "Concluído" só quando a telemetria (Meter1, ~300 ms) mostrar `lock_status` novo; ACK em ~51 ms é só "recebido". Estado confirmado em ~420 ms (p95 ~680 ms).
- O patinete desliga sozinho ~10 min sem comandos `[HIPÓTESE]` → estado "patinete desligado/fora de alcance" com reconexão automática.
- Funções extras observadas: farol, km/milhas, modo de condução, cruise, 1 ou 2 motores, partida do zero, limite de velocidade (20–50 km/h).

## Telas (5) e estados
1. **Início / Bloqueio** (principal): conectando · conectado-desbloqueado · conectado-bloqueado · enviando (aguardando confirmação do patinete) · falha/sem confirmação. Botão grande único; bateria e velocidade máx. visíveis.
2. **Primeiro uso / Cadastro**: procurar patinete · encontrado (confirmar) · cadastrado. Uma vez só.
3. **Recuperação de conexão**: patinete desligado/fora de alcance · Bluetooth desligado · permissão negada · reconectando.
4. **Controles**: farol, unidade, limite de velocidade, cruise, partida do zero, motores, modo de condução (só com patinete parado e confirmado).
5. **Diagnóstico**: estado da conexão, bateria, última confirmação, versão; log curto.

## Componentes e variáveis
Botão (primário/tonal/desabilitado/carregando), cartão de estado, interruptor, slider, chip de bateria, banner de erro. Variáveis de cor/espaçamento/raio com modos **Claro** e **Escuro** (Material 3). Estados nunca mostram "sucesso" sem confirmação.

## Entregas e aceitação
`design/iscooter-connect.figcraft` (editável) + `design/scripts/*.json` + PNGs em `design/out/` + protótipo navegável (interações) das 5 telas. Aceitação: abre no FigCraft e o proprietário valida o fluxo (Sprint 3 em `docs/sprints/README.md`).

## Papéis (revisão cruzada)
Designer (worker, worktree própria) ≠ revisor. Codex só a partir de 2026-11-04 09:27; antes disso, revisão por agy/Haiku conforme `docs/process/coordenacao-agentes.md`.
