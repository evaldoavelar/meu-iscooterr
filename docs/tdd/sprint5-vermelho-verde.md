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
