# Sprint 5 — evidência vermelho → verde

## 5.1 Navegação (S1)
- Vermelho: `NavigatorTest` (5 testes) adicionado antes da implementação; o build falhou com `Unresolved reference 'Navigator'` / `'AppDestination'` (compileTestKotlin de `core-domain`).
- Verde: `Navigator` + `AppDestination` mínimos em `core-domain`; `:core-domain:test --tests '*NavigatorTest*'` passa.
- Refatoração (fora do ciclo): `HomeDestination` (ui) substituído por `AppDestination`; `AppRoot` usa `Navigator`. `AppRootTest` e `HomeScreenTest` existentes seguem verdes.
- Gate: `clean check :android-app:assembleDebug` verde na branch `sprint-5/navegacao`.

## 5.2 Telemetria (ramo `sprint-5/telemetria`)
Vermelho = falha de compilação (símbolo ausente) ou asserção; verde = implementação mínima.
| Ciclo | Seam | Vermelho | Verde |
|---|---|---|---|
| Navigator imutável (ressalvas Sonnet 5.1: `open(Home)` limpa, saver, pilha ≥ 2 níveis) | S1 | `Unresolved reference 'current' on receiver of type 'Unit'` | `NavigatorTest` 9/9; `AppRootTest` recriação (StateRestorationTester) |
| `TelemetryMetrics` (V×I, Wh/km) | S1 | `No parameter with name 'voltageV'`, `Unresolved reference 'TelemetryMetrics'` | 54 V×10 A=540 W; 540÷27=20 Wh/km |
| `TripTracker` | S1 | idem (`TripTracker`) | 0,5 km/2 min → média 15, máx. 30 |
| Botão principal com bloqueio desconhecido → Desbloquear | S4-lógica | 2 `AssertionError` em `HomeUiMapperTest` | `locked != false` no mapper; em movimento segue desabilitado |
| Fake com V/I/temperaturas | S2 | `NullPointerException` (campos nulos) | 54 V, 38/40 °C, 25 km/h→10 A |
| `TelemetryUiMapper` | S1/ui | `Unresolved reference 'TelemetryUiMapper'` | 7 testes (valores de `design/telemetry.py`) |
| `HomeViewModel.telemetryState`/`onZeroTrip` | S3 | `Unresolved reference 'telemetryState'` | zerar não envia comando (`sent` vazio) |
| `TelemetryScreen`/`SpeedGauge` | S4 | `Unresolved reference 'TelemetryScreen'` | 8 testes (menu, diálogo, escala 2,0) |
| Navegação para a tela real | S4 | 2 `AssertionError` em `AppRootTest` | `AppRoot` usa `TelemetryScreen` |
Observação honesta: em alguns ciclos (mapper, tela) escrevi o conjunto de testes do componente de uma vez antes da implementação, em vez de um teste por vez.
Gate: `clean check :android-app:assembleDebug` verde. Pendente para a 5.6: S5 (screenshots vs `Telemetria-*.png`), barras "últimos 30 s" do `PowerCard` (não implementadas), altimetria fora (`[HIPÓTESE]`).

### 5.2 — correções da revisão Sonnet
- Vermelho: `TripTrackerTest` lacuna (10 min a 25 km/h somava 4 km) e amostra sem velocidade (`AssertionError`); verde: lacuna > `STALE_AFTER_MS` não soma, tempo regressivo ignorado, amostra nula reancora. Testes antigos passaram a usar amostras de 1 s (mesma conta à mão).
- Diálogo de zerar sem a promessa de salvar no histórico (até a 5.3).
- Gate: `clean check :android-app:assembleDebug` verde.

## 5.3 Histórico + detalhe + mapa (ramo `sprint-5/historico`)
| Ciclo | Seam | Vermelho | Verde |
|---|---|---|---|
| `Trip`, `TripRepository`, `InMemoryTripRepository`, `HistorySummary` (30 dias) | S1 | `Unresolved reference 'Trip'/'InMemoryTripRepository'` | 4,8+3,1 = 7,9 km; 35 min; item de 40 dias fora |
| `TripTracker`: consumo médio e bateria | S1 | `NullPointerException` (avgWhPerKm nulo) | 600 W a 30 km/h → 20 Wh/km |
| `HistoryUiMapper` | S1/ui | `Unresolved reference 'HistoryUiMapper'` | datas "Hoje, 18:42"/"Sáb 31…", seções por semana, resumo "2 h 19" |
| `TileMath`, `OsmTileSource`, `TileCachePolicy` (30 dias), `RouteProjection` | S1 | `Unresolved reference 'TileId'/'TileMath'` | Greenwich z10 = 512/340; URL oficial; despejo por expiração e LRU |
| `Navigator.openTrip` / saver com id | S1 | `Unresolved reference 'openTrip'` | pilha Home→Histórico→Detalhe e volta |
| `HomeViewModel` salva viagem ao zerar + `historyState` | S3 | `Argument type mismatch` / referência ausente | viagem salva com bateria 82%, nenhum comando enviado |
| `HistoryScreen`, `TripDetailScreen`, `RouteMap` | S4 | `Unresolved reference 'history'` | 7 testes (vazio, seções, detalhe, fonte 2,0) |
| `SampleTrips` (dados simulados) | S2 | `Unresolved reference 'SampleTrips'` | 5 viagens do protótipo, com trajeto |
Observações: a projeção do teste foi corrigida ainda no vermelho (escala igual nos dois eixos). O teste de recriação do `AppRootTest` foi ajustado porque Histórico deixou de ser stub. Diálogo de zerar voltou a mencionar o histórico (a viagem agora é salva).
Pendente: tiles OSM reais (rede + rota GPS) ficam para a Fase 5; S5 (screenshots) na 5.6; `OsmTileSource` não é usado por nenhuma tela ainda (sem tiles exibidos → sem atribuição na tela).

### 5.3 — correção da revisão Sonnet
- Vermelho: `zerar com uma unica amostra…` (`AssertionError`: salvava viagem 0 km/0 min). Verde: só salva com distância e duração > 0; `now` capturado antes do `launch`.
- Gate: `clean check :android-app:assembleDebug` verde; revisor Sonnet: APROVADO (itens baixos em `docs/sprints/sprint5-plano.md`).

## 5.4 Preferências (ramo `sprint-5/preferencias`)
| Ciclo | Seam | Vermelho | Verde |
|---|---|---|---|
| `Units`, `Preferences`, `PreferencesStore` | S1 | `Unresolved reference 'Units'` | 100 km = 62,1371 mi; 60 km/h = 37,28 mph; padrões do protótipo |
| `Units.perDistance` + unidades nos mapeadores Home/Telemetria/Histórico | S1/ui | `Unresolved reference 'perDistance'`, `Too many arguments for 'fun map'` | 60 km/h→37 mph; 1248,4 km→776 mi; 25,6 Wh/km→41,2 Wh/mi |
| ViewModel: unidades, tema, reconexão, trajeto, apagar histórico, esquecer patinete | S3 | `Unresolved reference 'onUnits'/'preferences'` | nenhum comando enviado; Search após esquecer |
| `SettingsScreen` | S4 | `Unresolved reference 'settings'` | 7 testes (grupos, segmentados, switches, diálogos, fonte 2,0) |
| Navegação e tema | S4 | `Unresolved reference 'resolveDark'` | Preferências reais no `AppRoot`; unidade muda a Telemetria; tema no `MainActivity` |
Observação: os testes de propagação de unidades foram escritos em bloco antes da implementação. Limitações registradas: o limite de velocidade (slider/comando) continua em km/h `[HIPÓTESE]`; `PreferencesStore` é só em memória (DataStore na Fase 4); "Guardar trajeto" e "Reconectar" ainda não têm efeito (GPS/BLE reais são fases 4–5); "Esquecer patinete" só desconecta (pareamento real na Fase 4).
Gate: `clean check :android-app:assembleDebug` verde.

### 5.4 — correções da revisão Sonnet (1ª revisão: REPROVADO por falta de teste 360×780 com fonte 1,5/2,0)
- Novos testes: 360×780 dp com fonte 1,5 e 2,0 (switch, "Esquecer", "Apagar" alcançáveis e diálogo visível) — já passavam; tocar no texto do switch alterna e texto do diálogo "Esquecer" — vermelhos (`AssertionError`), verdes após `toggleable(role = Switch)` e texto "O app desconecta deste patinete. Nada é enviado a ele.".
- `run {}` residual removido do `MainActivity`. Gate: `clean check :android-app:assembleDebug` verde.

## 5.5 Primeiro uso + Recuperação (ramo `sprint-5/primeiro-uso`)
| Ciclo | Seam | Vermelho | Verde |
|---|---|---|---|
| `OnboardingFlow` (Searching → Found → Connected → Ready; Cancelled) | S1 | `Unresolved reference 'OnboardingFlow'/'OnboardingStep'` | só conecta após confirmar; cancelar só antes de confirmar; eventos fora de ordem ignorados |
| `RecoveryReason.from` + `BluetoothState` (prioridade permissão > BT > fora de alcance > reconectando) | S1 | `Unresolved reference 'BluetoothState'/'RecoveryReason'` | "Tentativa 2 de 5"; 9 testes de prioridade |
| `AppDestination.Recovery`, `Preferences.onboardingDone` | S1 | `Unresolved reference 'Recovery'`; `onboardingDone` | saver da pilha preserva Recuperação; padrão `false` |
| `SimulatedBluetoothEnvironment` | S2 | `Unresolved reference 'SimulatedBluetoothEnvironment'` | Ativar/Permitir ligam os fluxos; Abrir configurações só conta a chamada |
| ViewModel: primeiro uso (busca 2 s virtual, confirmar, concluir, cancelar, esquecer) | S3 | compilação: `bluetoothEnvironment`/`startOnboarding` inexistentes | conecta só ao confirmar; nenhum comando enviado; esquecer volta ao primeiro uso |
| ViewModel: recuperação (5 tentativas de 5 s, cancelar, BT/permissão) | S3 | idem | 1 → 5 em 0/5/10/15/20 s; no 25º s "fora de alcance"; "Tentar agora" recomeça na 1 |
| `RecoveryUiMapper` | S1/ui | (ver observação) | 4 estados com textos exatos de `design/screens.py` |
| `StatusScreen`/`RecoveryScreen` e `OnboardingScreen` | S4 | `Unresolved reference 'onboarding'/'recovery'`; depois `Semantic Node has no parent layout with a Scroll` (teste: botão secundário é fixo, não rola) | 360×780 dp, fonte 1,0/1,5/2,0: textos visíveis, botões `Role.Button` ≥ 48 dp, cliques chamam callbacks |
| `AppRoot`: primeiro uso na 1ª abertura, Procurar → Recuperação, Cancelar/Ativar/Permitir, Esquecer → primeiro uso | S4 | 10 falhas (`AssertionError`, `ComposeTimeoutException`) | 9 testes novos; diálogo de "Esquecer" passou a dizer "…e volta ao primeiro uso" |

Observações honestas:
- Vários ciclos (domínio, ViewModel, telas) tiveram o conjunto de testes do componente escrito de uma vez antes da implementação, não um teste por vez.
- `RecoveryUiMapper`: a implementação foi escrita junto com o teste, sem execução vermelha observada; o teste passou de primeira (valores esperados copiados de `design/screens.py`, não do código).
- Mudança de projeto durante a fatia: `onEnableBluetooth()`/`onGrantPermission()` deixaram de chamar `startRecovery()` (no primeiro uso nada pode conectar sem a confirmação do usuário); a tela de Recuperação chama os dois em sequência. Teste `Ativar e Permitir so mexem no ambiente` ficou vermelho antes da mudança.
- Testes do `AppRoot` existentes passaram a criar o ViewModel com `onboardingDone = true`.
- Decisões: busca do primeiro uso = 2000 ms (`afterDelay` de `design/build.py`); cada tentativa de reconexão espera até 5 s `[HIPÓTESE]` (sem backoff; tempos reais na Fase 4); "Sinal forte · bem perto" do protótipo foi trocado por "Encontrado agora" (o app não mede sinal; seria dado de exemplo); ondas animadas do passo 1 viraram anéis estáticos (animação infinita trava testes Compose).
- `[HIPÓTESE]`: permissões BLE reais (`BLUETOOTH_SCAN/CONNECT`), intent de ativar Bluetooth e "Abrir configurações do Android" só existem simulados (`SimulatedBluetoothEnvironment`); `PreferencesStore` segue em memória, então o primeiro uso reaparece a cada processo novo até o DataStore (Fase 4). Back na tela de Recuperação cancela a reconexão (sem teste automatizado do botão físico).

### 5.5 — correções da revisão
| Ciclo | Seam | Vermelho | Verde |
|---|---|---|---|
| ALTO 1: `onForget` não reiniciava o navigator | S4 | `Esquecer nas Preferencias e concluir o primeiro uso cai na Home` (`AssertionError`: Home não aparecia, ficava em Preferências) | `onForget = { onForgetScooter(); navigator = Navigator() }` |
| BAIXO 1: `cancelRecovery` deixava o transporte em Connecting | S3 | `cancelar durante Connecting desconecta o transporte` (`expected:<1> but was:<0>`; usa `ConnectingSpy`, que realmente passa por Connecting) | `cancelRecovery` chama `controller.disconnect()` se a recuperação está ativa e o estado é Connecting |
| BAIXO 2: tentativas 2..5 ignoradas (disconnect assíncrono) | S3 | `as tentativas 2 a 5 chamam connect mesmo com o transporte em Connecting` (`expected:<5> but was:<3>`) | após `disconnect()` o laço aguarda o estado Disconnected (limite de 5 s) antes da próxima tentativa |
| ALTO 2: corrida `recovery` (stateIn) × `LaunchedEffect(null)→back` | S3 | `startRecovery e cancelRecovery atualizam o motivo de forma sincrona` (vm com `StandardTestDispatcher`, sem `runCurrent`: `recovery.value` era `null`) | `recovery` passou a `MutableStateFlow` atualizado de forma síncrona a cada mudança de tentativa/fora de alcance/ambiente; teste de Bluetooth desligado já passava (guarda de regressão) |

Observações: os testes de BAIXO 1 e BAIXO 2 foram escritos juntos e vistos vermelhos na mesma execução. A corrida do ALTO 2 é provada no nível do ViewModel (dispatcher que não executa eager); não há teste de AppRoot que a reproduza, porque o Robolectric usa o Main real.

## 5.6 Fechamento (ramo `sprint-5/fechamento`)
| Ciclo | Seam | Vermelho | Verde |
|---|---|---|---|
| Telemetria velha/conexão caída na tela de Telemetria | S1/ui, S3 | `Unresolved reference 'staleNote'`/`No parameter with name 'stale'` (compilação); com a assinatura esboçada (sem comportamento): `telemetria velha vira traco e avisa desatualizado` e `telemetria da tela vira traco e avisa quando a telemetria envelhece` falharam (`AssertionError`) | velocidade, bateria, consumo, corrente, tensão, temperaturas e odômetro total viram "--" e `staleNote = "Dados desatualizados"`; viagem preservada; `HomeViewModel` calcula `stale` com `STALE_AFTER_MS` (ou conexão ≠ Conectado) |
| Duração ≥ 60 min | S1/ui | `duracao de 60 min ou mais usa h mm ss…` (`expected:<1:00:00> but was:<60:00>`) | `h:mm:ss` a partir de 3600 s (59:59 → 1:00:00). **Decisão:** "h:mm:ss" em vez de "h:mm" porque "1:05" seria ambíguo com "mm:ss" |
| Viagem < 30 s | S1/ui | `viagem de menos de 30 s mostra menos de 1 min…` (`expected:<… < 1 min> but was:<… 0 min>`) | `< 1 min` abaixo de 30 s; 30 s arredonda para "1 min" |
| Rótulo do limite de velocidade em mph | S1/ui + S4 | `Unresolved reference 'valueText'/'minText'/'maxText'`; com esboço: `limite de velocidade em mph so converte o rotulo…` e `limite em mph mostra rotulo convertido mas o slider entrega km por h` (`AssertionError`) | rótulos "16 mph (25 km/h)", "12 mph", "31 mph"; slider e `onLimit` continuam em km/h (teste: arrastar a 40 chama `onLimit(40)`) |
| Aviso de telemetria velha na tela (`stale-note`) | S4 | **sem vermelho observado**: os 2 testes do `TelemetryScreenTest` foram escritos depois do composable (o mapeador já estava coberto pelo vermelho acima) | passam |
| Testes 360×780 dp com fonte 1,5/2,0 para Telemetria, Histórico e Detalhe (lacuna de D) | S4 | Telemetria: `AssertionError` ao abrir o menu "Zerar viagem" depois de rolar (o cabeçalho rola com o conteúdo); Histórico/Detalhe: já passavam | teste reordenado (menu antes de rolar). **Achado:** em fonte grande, o menu ⋮ só está acessível com a tela no topo |
| S5 das telas novas (`ScreensScreenshotTest`) | S5 | (referência de PNG ausente seria erro de leitura; não houve vermelho de lógica) | 18 frames × 2 temas dentro do teto 0,12; CSV `report-telas.csv` |
Observações honestas: o vermelho de compilação e o vermelho de comportamento foram separados esboçando só as assinaturas; a métrica S5 não reprova diferenças de layout menores que o teto (ver `docs/sprints/sprint5-entrega.md` para a lista das diferenças reais).

## Sessão 11 — follow-ups (ramo `sprint-5/followups`)
| Ciclo | Seam | Vermelho | Verde |
|---|---|---|---|
| Permissão: botão extra acima do principal (FigCraft `screens.py::status`: extra y=626, principal y=702) | S4 | `permissao - botao extra fica acima do principal…` (`AssertionError: extra deve ficar acima do principal`) | ordem invertida em `StatusScreen`; MAD de `Recuperacao-Permissao` caiu de 0,0745/0,0778 para 0,0471/0,0497 (confirma a causa) |
| Telemetria com cabeçalho fixo (menu ⋮ e Voltar alcançáveis com fonte 2,0 depois de rolar) | S4 | `360x780 fonte 2_0 cabecalho fixo…` (`AssertionError`: menu fora da tela) | cabeçalho fora do `verticalScroll`; só o conteúdo rola |
| `cancelRecovery` sempre desconecta com tentativa ativa | S3 | `cancelar com tentativa em andamento desconecta…` (`expected:<1> but was:<0>`; 1ª versão do teste com `ConnectingSpy` **passou** sem vermelho e foi substituída por um cenário em que o estado não é `Connecting`) | condição reduzida a `recoveryJob?.isActive` |
| Tick diário do histórico | S3 | compilação (`No parameter with name 'zone'`); depois, com a assinatura, `Ontem, 22:00` não aparecia na virada do dia | `dayTick` (emite agora e a cada meia-noite local) combinado em `historyState`; `zone` injetável |
| S5: teto de MAD por frame (observado + 0,015) e altura da captura | S5 | **sem vermelho observado** (guarda de regressão: capturas já tinham 1560 px e MAD abaixo do teto) | 36 comparações passam |


## Revisão Codex r1 (ramo `sprint-5/codex-r1`)
Origem: `.omc/reviews/REVIEW-sprint4-ui-r1.md`, feita sobre `f28a616`; reverificada contra a `main` em `bbc2e3c`. Todos os achados são `[ESTÁTICO]` na origem; abaixo, o veredito sobre a `main` atual e o ciclo.

| # | Procede na `main` atual? | Vermelho | Verde |
|---|---|---|---|
| 1 Retry pós-timeout enviava a ação oposta | **Parcial.** `Outcome.Failed/TimedOut` já guardam `command` e o reducer limpa o erro se a telemetria o reflete com `receivedAtMs >= atMs` (`HomeReducer.kt:123`). Mas `HomeViewModel.onMainButton` ainda usava `SetLocked(!locked)` (`HomeViewModel.kt:245` antes), e `HomeUiMapper.mainButton` avaliava a disponibilidade desse comando invertido. Com telemetria carimbada antes do timeout o erro fica e o Retry inverte `[ESTÁTICO]` | `Tentar de novo apos timeout repete o desbloqueio…` (`expected:<[SetLocked(false), SetLocked(false)]> but was:<[SetLocked(false), SetLocked(true)]>`, transporte com telemetria injetável); mapper: `expected:<SetLocked(locked=false)> but was:<null>` e `disponibilidade do Tentar de novo avalia o comando original` (`expected:<true> but was:<false>`) | `HomeUiMapper.retryCommand` devolve o comando do `Failed/TimedOut`; ViewModel e disponibilidade do botão usam-no (fallback: inverter o último estado só quando não há comando) |
| 2 Gate JaCoCo passa sem `.exec` | **Procede** nos 3 módulos (`ui/build.gradle.kts` `doFirst`; `core-domain` e `data` sem checagem nenhuma). `JacocoReportBase` tem `onlyIf` que pula a tarefa sem `.exec` `[ESTÁTICO]`, **provado**: | Gate antigo de `core-domain` com `test.exec` apagado: `> Task :core-domain:coverageGate SKIPPED`, `BUILD SUCCESSFUL` | tarefa `verifyCoverageInputs` (`doLast`: `.exec` existe e há classes) da qual `coverageGate` depende, nos 3 módulos. Com o `.exec` apagado: `BUILD FAILED … coverageGate: …/test.exec ausente`, igual em `ui` e `data` |
| 3 Texto "Bloqueado/Desbloqueado às" misturava telemetria atual | **Procede** (`HomeUiMapper.kt`, ramo `Outcome.Confirmed` usava `locked` da telemetria) | `expected:<[B]loqueado às 14:32> but was:<[Desb]loqueado às 14:32>`; reducer: `expected:<Confirmed(atMs=1420, command=SetLocked(locked=false))> but was:<Confirmed(atMs=1420, command=null)>` | `Outcome.Confirmed(atMs, command)` preenchido pelo reducer; texto vem do alvo; sem alvo conhecido: "Concluído às" |
| 4 `onMainButton` repetido agenda vários `connect()` | **Procede** (`HomeController.connect` só checava o estado, que muda depois) | `varios toques em Procurar…` (`expected:<1> but was:<3>`, Spy contando `connect`) | `HomeController` guarda `connectJob`; `disconnect()` o cancela (uma 1ª versão sem isso quebrou `as tentativas 2 a 5 chamam connect…`, `expected:<5>`, corrigida no mesmo ciclo) |
| 5 Slider conservava rascunho recusado | **Procede** (`remember(limit.kmh, status)`) | `slider volta ao valor confirmado quando o pedido e recusado por movimento` (`ProgressBarRangeInfo(current=25.0…)` falhou) | chave do `remember` inclui `control.enabled`: ao desabilitar, o rascunho volta ao valor confirmado |
| 6 Banner some com `Mode` pendente | **Já resolvido** na `main`: `HomeUiMapper.notice` ignora pendências (`pending = emptyMap()`); teste de mapper `aviso de movimento permanece mesmo com o modo pendente` já cobria | **sem vermelho observado** (guarda): `aviso de movimento continua visivel com o modo pendente` em `HomeScreenTest` (banner na tela) passou de imediato | nenhuma mudança de produção |

Observação: o cenário do achado 1 só ocorre com amostra carimbada antes do timeout (a telemetria real usa o relógio do app na chegada); o conserto é defensivo e barato, e o teste usa um relay de telemetria para forçá-lo.
