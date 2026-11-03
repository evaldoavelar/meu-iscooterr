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
