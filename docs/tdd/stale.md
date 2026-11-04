# TDD — stale (Meter1 velho na UI) — vermelho/verde
Comando: `flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon -q :<modulo>:test... --tests ...'`. Esperados à mão: Meter1 em t=1000, agora=6000 => 5000 ms > STALE_AFTER_MS (2000) (plano Fase 3, decisão 3).

| # | Seam | Teste | Vermelho observado | Verde |
|---|------|-------|--------------------|-------|
| 1 | S1 reducer | `HomeGroupAgeTest.lockCommandIsDisabledAsStaleWhenMeter1IsStale...` | `expected:<Disabled(reason=TelemetryStale)> but was:<Enabled>` | `availability`: `SetLocked` + `meter1Stale` => `TelemetryStale` (desbloqueio de `SetLocked(false)` mantém regra própria; nada foi habilitado) |
| 2 | S3 mapper | `HomeUiMapperTest.Meter1 velho ... bateria sem leitura` | `expected:<[--]> but was:<[82%]>` | `battery` exige `!meter1Stale` |
| 3 | S3 mapper | `HomeUiMapperTest.Meter1 velho desabilita Bloquear com o motivo...` | sem vermelho: já verde pelo ciclo 1 (reducer); declarado. Uma 1ª versão do teste (bloqueio "desconhecido" => Desbloquear) ficou vermelha (`expected:<Unlock> but was:<Lock>`) e foi descartada: quebrava o teste existente "bloqueio com telemetria velha fica desabilitado" | — |
| 4 | S3 ViewModel | `HomeViewModelTest.Meter1 velho com Meter2 vivo mostra bateria sem leitura na Home e na Telemetria` | `expected:<[--]> but was:<[82%]>` (batteryText da Telemetria) | `TelemetryUiMapper.batteryStale`, ligado no ViewModel |
| 5 | S3 ViewModel | `HomeViewModelTest.Fake em uso normal nao fica com Meter1 velho` | sem vermelho (guarda de regressão; declarado) | — |

Ciclos 2 e 3 foram escritos juntos (dois vermelhos numa execução). Fixtures de `HomeReducerSafetyTest`, `HomeUiMapperTest`, `HomeScreenTest`, `HomeScreenshotTest` passaram a informar `meter1AtMs = receivedAtMs` (Meter1 fresco, como no fluxo real); sem isso "nunca visto" = velho. S4 (Compose): sem teste novo; a tela só renderiza `HomeUiState`/`TelemetryUiState` (mesmos "--" e motivo de telemetria velha já cobertos). Screenshots S5 sem `--update-snapshots`, verdes.
