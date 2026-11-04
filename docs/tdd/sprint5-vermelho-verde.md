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
