# RESUME (fim da sessão 01)
## Estado
Fase 1 estática concluída. Commits-chave: b17da5a estrutura · 3554a14 coordenação · 7362974 ferramentas · 11ab78f plano · 8309e29 T1 · 9ede21a/41b0a5e T3-T7 · 8ee7555 T9+ADR0002 · 2a5ddf1 T2+T12 · 3a72ce7 correções T11-r1 · (últimos: H8 e ajuste r2).
Achados centrais: GATT FF60/FF61/FF62; quadro `1A A1 CMD TIPO LEN DADOS CRC16 1F F1`; LOCK 0x32 + PIN 4 dígitos (modo 0 unlock, 1 lock, 2 temporário); ACK TIPO 0x05, erro de PIN TIPO 0x89; estado real em Meter1 byte[6] bit5; app original recusa lock com velocidade>0; lock sem token de servidor no quadro (ADR 0002 proposto).
## Lições
- Revisor Codex achou 6 erros reais em texto do coordenador: sempre revisão cruzada antes de dar como fato.
- Constantes inlinadas: seguir por `q3.a`. EXIT do script de background mascara o código do JADX (foi 3).
- Respostas: `byte[3]` é tipo (0x05 ACK, 0x89 erro).
## Dívidas / bloqueios (precisam do usuário)
1. Aprovar `docs/research/test-plan.md` e fornecer patinete parado no cavalete para a Sprint 2 (captura HCI do app original). Nada feito no patinete ainda.
2. Decidir se a H8 inclui tentativa com PIN errado (risco de bloqueio desconhecido).
3. Identificação no scan (nome/UUID do anúncio) `[DESCONHECIDO]`.
4. Depois: Fase 2 (FigCraft CLI/MCP ainda não instalado).

## Atualização (sessão 01, após aprovação do proprietário)
- Plano de testes v2.1 aprovado pelo proprietário com condições (sem PIN errado, sem comandos próprios, H7A/H7B, tempos, sem bruto no git) e H11; Codex T12 r4 APROVADO. Lição: r2 inicial foi inválida (diff vazio por commit antes); sempre fixar o intervalo `git diff A..B`.
- Achado: modo 1 do lock DEFINE PIN (grava após ACK); sem PIN só modo 2 (temporário). Não enviar modo 1/3.
- Haiku usado para edição mecânica (scripts/agents/run-haiku.sh); o líder verificou o diff.
- Bloqueio atual: telefone não aparece no adb; reconectar e autorizar depuração USB, depois seguir docs/research/captura-roteiro.md.

## Backlog de funções pedido pelo proprietário (2026-11-03)
Copiar do app original, **depois** de lock/unlock estável (REGRA 4): ligar/desligar farol (CMD 0x33), trocar limite de velocidade (CMD 0x62/Meter1 byte[12], a confirmar), alternar km/milhas (CMD 0x3C). Protocolo ainda `[HIPÓTESE]`/`[ESTÁTICO]`; nenhum comando próprio nesta sprint. Se a captura passiva cobrir estas ações (passo opcional 8 do roteiro), vira evidência.

## Sprint 2 — captura concluída (2026-11-03)
- Resultados: `docs/research/parts/S2-captura-resultados.md`. 12/12 bloqueios confirmados pelo estado real; LOCK modo 2/0 sem PIN (patinete sem PIN, `lock_has_pwd=0`); ACK mediana 51 ms, estado mediana 419 ms (p95 681 ms).
- Método: app original reempacotado com IX7TAP (`scripts/repack/`), porque HCI snoop do Samsung é inacessível sem root. O app modificado **continua instalado** no telefone (assinatura própria); restaurar o original exige desinstalar (ver `scripts/repack/README.md`).
- Pendências: revisão Codex do `S2-captura-resultados.md` e do commit `0c1e53f` (Codex sem cota até 2026-11-04 09:27); decidir restaurar o app original; atualizar H1–H11 no `test-plan.md` e ADR 0002; CRC das respostas `[DESCONHECIDO]`; t_scan/t_conn não medidos; desligamento automático ~10 min `[HIPÓTESE]`.
- Lembrete: o proprietário aceitou risco residual de PIN no logger; como o patinete não tem PIN, nada a trocar — confirmar com ele.

## Próximo passo (2026-11-03, fim da sessão 3)
1. Construir o protótipo conforme `docs/sprints/fase-2-plano.md` com `figcraft-cli run` (scripts em `design/scripts/`), 1 worker designer em worktree; revisor ≠ autor (Codex só após 2026-11-04 09:27; antes, agy/Haiku).
2. Pendências da Fase 1: mesclar `docs/research/parts/*` em `protocol-analysis.md`; revisão Codex de S2-captura-resultados e do logger (0c1e53f); atualizar H1–H11 em test-plan e ADR 0002 (aceitar: sem nuvem, sem PIN neste patinete); decidir restaurar app original no telefone (hoje: app reempacotado instalado, assinatura própria; restaurar exige desinstalar e novo login).
3. Nenhum comando ao patinete até a Fase 4 com aprovação.

## Sessão 4 — fim (2026-11-03)
- **Estado:** main = `docs` + `README.md`/`LICENSE` (MIT, 342c9dd; ficha TASK-D1/D2 e `docs/design/ux-direcao.md` em 744ffaa). Protótipo na branch **`task/TASK-D3-home-coordenador` @ 6ba4701** (ainda não mesclada; mesclar só com autorização). Ramos `task/TASK-D1-prototipo` e `task/TASK-D2-prototipo-v2` (trabalho do agy, descartado pelo proprietário) podem ser apagados.
- **Como gerar:** `cd design && ./run.sh` (usa `tools/figcraft/...` do repo principal, `python3 build.py` → `scripts/home.json` → `home.figcraft`, PNGs em `design/out/{escuro,claro}`). Código: `lib.py` (tokens, ícones; `measure_icons.py` mede o desvio do traçado → `icons_dim.json`), `home.py`, `telemetry.py`, `history.py`, `screens.py`.
- **Decisões do proprietário:** botão de bloqueio retangular largo no rodapé; nome do app **"Meu iScooter"** (constante `APP_NAME`; é marca de terceiros no nome — rever antes de distribuir); 4 modos Pedestre/Eco/Race/Sport (mapeamento nome↔valor 0–3 do CMD 0x35 é `[HIPÓTESE]`); menu ⋮ no topo da Telemetria (zerar viagem salva no histórico); sem versões de comunicação/controlador.
- **Pendências de decisão do proprietário:** (1) comportamento dos controles com patinete em movimento (hoje só o bloqueio é desabilitado); (2) fonte do mapa do histórico (OSM/offline/SDK); (3) nome do app; (4) selo "dados de exemplo" na UI (hoje não há); (5) textos "mais economia/autonomia" e "reconectar sozinho" sem evidência.
- **Revisão Haiku r1 (D3):** sem PIN, sem jargão, sem "concluído" fora do confirmado; achados aplicados: datas do histórico. Não aplicados (decisões acima). Falta revisão visual cruzada por agente (agy/Haiku não vê imagens bem) ou pelo próprio proprietário.
- **Lições:** FigCraft soma o mínimo NÃO escalado do traçado a x/y em `vector.create` (medir com `node.get`); `text.create` não aceita height (usar `node.set` com `textAutoResize:"none"`, `textAlignVertical:"middle"`); `pkill -f agy` mata o próprio shell (usar `kill <pid>`); `page.select` aceita `index`/`id`; `proto.interaction.add` com delay precisa `dest` dentro de `action`; export de várias páginas exige pastas distintas.
- **Pendências da Fase 1 (inalteradas):** mesclar `docs/research/parts/*` em protocol-analysis.md; revisão Codex do S2 (cota volta 2026-11-04 09:27); H1–H11 e ADR 0002; decidir restaurar o app original no telefone.

### Decisões do proprietário no fim da sessão 4 (branch TASK-D3 @ 2d739b9)
Controles (exceto bloqueio) habilitados em movimento `[HIPÓTESE]`; mapa = OpenStreetMap (com atribuição); nome "Meu iScooter" mantido; sem selo de exemplo; textos de tração/onboarding reescritos. Restam: validação formal do protótipo pelo proprietário e as pendências da Fase 1.

## Sessão 5 — fim (2026-11-03)
- **Estado:** `main` @ 054bdc3 inclui `design/` (protótipo validado). Nada em branches/worktrees. Nenhum comando ao patinete.
- **Próximo:** Fase 3 conforme `docs/sprints/fase-3-plano.md` (Compose com `FakeScooterTransport`); só começar com o plano aprovado pelo proprietário. Regenerar PNGs: `cd design && ./run.sh`.
- **Pendências da Fase 1 (inalteradas):** mesclar `docs/research/parts/*` em protocol-analysis.md; revisão Codex do S2 (a partir de 2026-11-04 09:27); H1–H11 e ADR 0002; decidir restaurar o app original no telefone.
- **Lições:** `Edit` exige Read prévio; `git branch -d` recusa branches não mescladas (D1/D2 apagadas com `-D` por decisão do proprietário); o mapa ANDROID-MAPA já reflete o menu ⋮.
- **Fim da sessão 5 (atualização):** Fase 3 aprovada; prompt da sessão 06 em `.omc/prompts/sessao-06-fase3-sprint4.md` (dry-run OK). Próximo: Sprint 4 conforme o plano.

## Sessão 06 — fim (2026-11-03)
- **Estado:** `main` @ 66041f8 intacta; trabalho na branch `sprint-4/esqueleto-home` @ 42544e7 (+ commit de docs desta nota). Nada rodando, sem worktrees. Nenhum comando ao patinete.
- **Feito (TDD, vermelho→verde registrado):** S1 reducer/máquina de estados (20 testes), S2 Fake com relógio virtual (9), S3 controller+Fake (8). Seams S1–S5 aprovados pelo proprietário; meta 80 % de linhas (JaCoCo `coverageGate` em `check`) em domínio, data e lógica da UI.
- **Decisões de design tomadas (a validar com o proprietário):** desbloqueio com regra própria — exige último estado "bloqueado" conhecido, tolera telemetria velha/velocidade desconhecida, nunca em movimento `[HIPÓTESE]`; farol sempre acessível se conectado; timeout de pendência 3 s, "confirmado" visível 2 s, telemetria velha > 2 s `[HIPÓTESE]`; rótulos de modo só em `FakeScooterTransport.MODES` (`[HIPÓTESE]`).
- **Próximo:** `ui` (tokens `ColorScheme`+`ExtendedColors`, `HomeUiMapper`, `HomeViewModel`, `HomeScreen`), Robolectric/Compose, screenshots, identidade (nome de trabalho em constante única; propor 2–3 nomes e checar disponibilidade), revisão (Codex a partir de 2026-11-04 09:27), entrega com diferenças vs FigCraft.
- **Lições:** sem `unzip` (usar Python); `.gitignore` precisava de `tools/dl|gradle|android-sdk|gradle-home`; Gradle 9 exige diretório existente para cada módulo do `settings`; `Edit` exige Read prévio (usar Python/heredoc para anexar); lambda de `Rig` com extensão sem tipo causa "recursive problem" no Kotlin.

## Sessão 07 — fim (2026-11-03)
- **Estado:** branch `sprint-4/esqueleto-home` @ 455edfb + commit de docs; `main` @ 66041f8 intacta. Nada rodando. Nenhum comando ao patinete.
- **Feito:** ver `docs/sprints/sprint4-entrega.md` (resultados, diferenças vs FigCraft, `[HIPÓTESE]`s) e `docs/tdd/sprint4-ui-vermelho-verde.md`. Screenshots enviados ao proprietário (3 de 20+; todos em `app/ui/build/screenshots/`, regeneráveis por `./gradlew :ui:testDebugUnitTest`).
- **Falta:** (1) propor 2–3 nomes e verificar disponibilidade (nada publicado), aplicar após escolha; (2) revisão cruzada Codex; (3) decisões do proprietário: desconectado mantém último estado? botão "Procurar" cinza ou primário? hipóteses de desbloqueio/timeouts; (4) merge só com autorização.
- **Lições:** Compose 1.12.1 exige `compileSdk 37`; JaCoCo + Robolectric precisa `isIncludeNoLocationClasses`; `runTest` com ViewModel e loop de tick trava — cancelar `viewModelScope` no corpo do teste; nó mesclado no Compose test mede o container (usar `useUnmergedTree`); `Surface(onClick=…)` tem `onClick` como 1º parâmetro.

### Sessão 07 — atualização final
Sprint 4 mesclada na `main` @ 5e10bea (sem push); branch `sprint-4/esqueleto-home` pode ser apagada. Nome mantido ("Meu Patinete"; checar disponibilidade antes de distribuir). Restam: revisão Codex de `git diff 115e029..f28a616`, itens não tratados da revisão Sonnet (lista em sprint4-entrega.md), Sprint 5.

## Sessão 08 — fim (2026-11-03)
- **Estado:** branch `sprint-4/followups-sonnet-r1` @ a138f9e + plano 58ba98f + commit de docs; `main` @ dd28a30 intacta. Nada rodando, sem worktrees extras. Nenhum comando ao patinete.
- **Feito:** `clean check :android-app:assembleDebug` verde na base e na branch. Correções TDD (evidência em `docs/tdd/sprint4-ui-vermelho-verde.md`): `connect()` idempotente (item 6), slider reverte ao valor confirmado se o limite falha (5), `HomeUiState.notice` independe de pendências (4), `onMainButton` lê o estado do controlador (7, refatoração). Plano da Sprint 5 em `docs/sprints/sprint5-plano.md` (fatias 5.1–5.6).
- **Não feito / bloqueado:** revisão Codex (cota só volta 2026-11-04 09:27; `scripts/agents/run-codex-review.sh`, ficha `docs/tasks/REVIEW-sprint4-ui-codex.md`, `git diff 115e029..f28a616`); código da Sprint 5; itens 8 (falhas persistem + chip "Sem resposta" após recusa — decisão do proprietário) e 21 (Fake sem flavor — Fase 4).
- **Aguardando o proprietário:** autorizar merge de `sprint-4/followups-sonnet-r1` na `main`; decidir item 8; revisar hipóteses de desbloqueio/timeouts.
- **Lições:** rodar só `:módulo:test --tests` dá vermelho em ~25 s; `ProgressBarRangeInfo` do Slider contínuo tem `steps=0`; `remember` do rascunho do slider precisa da chave de status para reverter.

### Sessão 08 — atualização final
- `main` @ 5ad9360 (merge autorizado pelo proprietário; sem push). Branch de follow-ups apagada. Hipóteses aprovadas: timeouts (3 s / 2 s / 2 s); desbloqueio sem estado conhecido, nunca em movimento (`StateUnknown` não é mais usado pelo reducer).
- Aberto: com bloqueio desconhecido o botão principal ainda mostra "Bloquear" (não oferece Desbloquear) — decidir UI; falhas persistem até limpar por telemetria/reconexão/nova tentativa (sem expiração por tempo, opcional).

## Sessão 09 — fim (2026-11-03)
- **Estado:** `main` @ c3de996, árvore limpa, sem worktrees, nada rodando. Sem push. Nenhum BLE/comando ao patinete (só `FakeScooterTransport`). Evidência TDD: `docs/tdd/sprint5-vermelho-verde.md`; pendências das revisões: `docs/sprints/sprint5-plano.md`.
- **Feito (hashes):** 5.1 navegação `1d61f81`/merge `dc9e750`; 5.2 Telemetria `8f456e9`…`133d06c`/merge `051a89d` (inclui botão Desbloquear com bloqueio desconhecido, TripTracker robusto a lacunas); 5.3 Histórico/Detalhe/`RouteMap`/`MapTileSource`+`OsmTileSource`+`TileCachePolicy` 30 dias `a6d7333`…`7ed7b9d`/merge `36a8efe`; 5.4 Preferências (unidades propagadas, tema, apagar histórico, esquecer=desconectar) `03388cd`…`901b3b2`/merge `c3de996`.
- **Decisões do proprietário:** telemetria extra só no Fake (ok); cache de tiles 30 dias; falhas sem expiração por tempo; Desbloquear com estado desconhecido; **merge local sem pedir autorização quando o revisor aprova** (memória `merge-autonomy`); "não vamos instalar no celular agora, primeiro testar em emulador".
- **Não feito:** 5.5 (Primeiro uso + Recuperação: `OnboardingFlow`, `RecoveryReason`, `BluetoothEnvironment` simulado, `OnboardingScreen` 3 passos, `StatusScreen`), 5.6 (S5 screenshots das telas novas vs `design/out/`, entrega `docs/sprints/sprint5-entrega.md`, lista de diferenças), revisão Codex (cota só ≥ 2026-11-04 09:27), emulador Android (não existe em `tools/`; avaliar instalar system-image x86_64 + `emulator` se couber em disco/RAM, ou Robolectric/screenshots como substituto e avisar o proprietário).
- **Lições:** reviewer Sonnet reprovou 1× (5.4) por teste 360×780/fonte 1,5–2,0 ausente — incluir em toda tela nova; `SwitchRow` deve ser `toggleable` na linha; `Switch(onCheckedChange = null)`; testTag na mesma cadeia de `toggleable` mantém `assertIsOn`; teste vermelho no meio de um `clean check` em background derruba o baseline (não editar a árvore durante o build); `data` agora depende de `core-domain` (SampleTrips); gap de telemetria > `STALE_AFTER_MS` não soma distância.
- **Pendências baixas das revisões:** ver `docs/sprints/sprint5-plano.md` (tick diário do histórico, limite km/h com mi/mph, telemetria velha na tela, barras "últimos 30 s", rotação com diálogo aberto).

## Sessão 10 — fim (2026-11-03)
- **Estado:** `main` com 5.5 (`7f5dc3e`; commits `0998fc6`…`4c6f314`) e 5.6 (`80773a4`; `455d894`, `7e9d88f`, `567b44c`) + docs desta sessão. Sem push, sem worktrees, nada rodando (emulador desligado). Nenhum BLE/comando ao patinete.
- **Feito:** 5.5 (OnboardingFlow, RecoveryReason, BluetoothEnvironment simulado, OnboardingScreen, StatusScreen/RecoveryScreen, Esquecer volta ao primeiro uso); 5.6 (ScreensScreenshotTest 18 frames, telemetria velha, h:mm:ss, limite em mi/mph só no rótulo, "< 1 min", entrega). Cobertura: core-domain 99,0 %, data 100 %, ui lógica 96,8 %, composables 99,1 %. Emulador: `scripts/android-emulator.sh`; AVD `ix7` em `tools/android-sdk/avd`.
- **Não feito:** revisão Codex (cota só ≥ 2026-11-04 09:27: `scripts/agents/run-codex-review.sh`, ficha `docs/tasks/REVIEW-sprint4-ui-codex.md`, `git diff 115e029..f28a616`; também `git diff c3de996~..main`); BAIXOs de revisão listados em `sprint5-entrega.md` (cancelRecovery com Connecting ainda não publicado, piscada de Home ao Esquecer, ordem dos botões de Permissão vs FigCraft, S5 mede pouco: sem teto por frame nem altura); Fase 4 (BLE real) depende de aprovação do proprietário.
- **Lições:** `pgrep -af` imprime PIDs de sessões `claude` alheias (não confundir com commits); em zsh variável com espaço não vira comando (usar função); `avdmanager create avd` falha com `-d` (devices.xml) — omitir; workers que ficam "esperando timers" devem ser parados com TaskStop; AVD padrão é 320×640 dp (`wm size/density` para realismo).
- **Lembrete (1×):** rotacionar o PIN (memória `pin-leak-hardening`) — o patinete não tem PIN, só confirmar com o proprietário.

## Sessão 11 — fim (2026-11-03)
- **Estado:** `main` @ 7841fdd (merge de `sprint-5/followups`; commits `986184a`, `e104cd3`). Sem push, sem worktrees, nada rodando. Nenhum BLE/comando ao patinete.
- **Feito:** itens (a)–(e) com TDD (registro em `docs/tdd/sprint5-vermelho-verde.md`, seção Sessão 11). MAD de `Recuperacao-Permissao` caiu de ~0,075 para ~0,048 após reordenar os botões.
- **Não feito:** revisão Codex (hora ainda não chegou: `date` = 2026-11-03 noite; vale a partir de 2026-11-04 09:27); piscada de Home ao Esquecer; tick diário usa `uptimeMillis` (reemitir em ON_RESUME é melhoria futura); Fase 4 (aprovação do proprietário).
- **Lembrete (1×):** o patinete não tem PIN (S2); confirmar com o proprietário e encerrar o item `pin-leak-hardening`.

## Sessão 11 — fim real (2026-11-04)
- **Estado:** `main` com follow-ups (7841fdd), rename "Meu iScooter" (bbc2e3c), codex-r1 Sprint 4 (592ff33) e ficha/revisão Codex Sprint 5. Árvore limpa, sem worktrees, nada rodando; `clean check` verde. Sem push (repositório remoto ainda não existe).
- **Decisões do proprietário (2026-11-04):** Fase 4 aprovada (plano de teste com patinete parado ainda a escrever); instalar no celular (feito, só Fake, serial RQCX209XEWM); nome "Meu iScooter"; o app original tem bloqueio por PIN — proprietário testará e pedirá leitura do log (reabre a S2 "sem PIN" como `[HIPÓTESE]`).
- **Pendente:** 4 achados Codex da Sprint 5; 7 achados visuais do proprietário (prompt da sessão 12); plano da Fase 4; 4 BAIXOs do codex-r1 (premissa de `connect()`, teste de controller, nome do teste do slider).

## Sessão 12 — fim (2026-11-04)
- `main` @ ff7e7a6 (ver `git log`), limpa, sem worktrees. APK instalado no celular `RQCX209XEWM` (só Fake).
- Pendente do proprietário: (1) aprovar `docs/sprints/fase-4-plano.md` e estar presente para as etapas 4.2+; (2) decidir o mapa (A4): só faz sentido com trajeto real/GPS (Fase 5) ou implementar já a camada de tiles com trajetos simulados; (3) conferir no celular as telas alteradas e aprovar a mudança visual mínima do primeiro uso (aviso + "Tentar novamente"; regra 2); (4) teste do PIN no app original (reabre `[HIPÓTESE]`); (5) `service_tier = "priority"` no `~/.codex/config.toml` (é dele) e rotacionar o token Bearer ali exposto.
- Sem decisão do proprietário a sessão 13 só pode fazer a etapa 4.0 (GATT falso/parser com vetores de `s2d`), que também depende de aprovar o plano. Prompt pronto: `.omc/prompts/sessao-13-fase4-aprovacao.md` (NÃO aberto).
- Lições: o teste de igualdade de altura (A3) não reproduziu a quebra do celular no Robolectric; dois testes antigos codificavam defeitos (corrigidos). Análise de custo do Codex: ix7 ≈ 2 % do uso local; o resto vem de outros projetos.

## Sessão 14 — fim (2026-11-04)
- **Estado:** `main` @ f199c95 (merge de `task/ble41`; commits `24f091a`, `1dd044e`). Worktree `ble41` removido; `../wt/mapa` é da sessão 13 (não tocado). Nada rodando. Sem push; nenhum BLE/adb.
- **Feito (TDD, `docs/tdd/ble41-vermelho-verde.md`):** MTU `[HIPÓTESE]` (ordem connect→discover→requestMtu 247→CCCD; MTU 23 trunca quadro de 27 B, 247 íntegro), `CallbackGate`, `AgedTelemetry`. Revisor Sonnet independente: APROVADO.
- **Pendente antes de qualquer 4.2:** A1/M1/M2 (HANDOFF-BLE-41, fim) e `[DESCONHECIDO]` 1–12 dos dois handoffs. 4.2 só com o proprietário presente e confirmando; 4.4 bloqueada.
- **Lições:** worker não capturou `EXIT` do Gradle — o líder rodou `clean check` e confirmou; o worker repetiu desvios de TDD (3 ciclos sem vermelho): exigir log do vermelho no registro.
- **Sessão 15 não aberta:** sessão 13 ainda tem worktree `../wt/mapa` pendente; prompt pronto em `.omc/prompts/sessao-15-ble-a1-m1.md`.
