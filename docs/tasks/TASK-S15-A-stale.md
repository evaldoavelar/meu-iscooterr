# TASK-S15-A — testes/lógica de Meter1 velho (core-domain + ui)
Worktree `../wt/s15a`, branch `task/s15a`. Arquivos permitidos: `core-domain/src/**`, `ui/src/**` (exceto `history/RouteMap*`, `RouteMapTilesTest`). Proibido: `data/`, `android-app/`, `core-protocol/`, contratos (`ScooterTransport`), gradle wrapper/lockfiles, `docs/STATUS.md`/`RESUME.md`.
Itens (TDD estrito, vermelho observado e registrado em `docs/tdd/s15a.md`):
1. Teste: `SetLocked(false)` (desbloquear) com `meter1Stale` = true — comportamento hoje: desbloqueio permitido sem conhecer o estado (decisão do proprietário, sessão 08), nunca em movimento. Provar que continua permitido e o botão não some.
2. Teste isolado do mapper da Telemetria (`TelemetryUiMapper`): bateria `--` com `batteryStale`, valores nulos, formatação.
3. Flicker de `meter1Stale` na 1ª leitura (corrida entre fluxos `telemetry` e `agedTelemetry` no `HomeController`/`HomeReducer`): reproduzir com teste vermelho (Meter1 recém-chegado não pode aparecer como velho por 1 tick) e corrigir.
4. Motivo `Moving` deve ter precedência sobre `TelemetryStale` em `HomeReducer.availability` (em movimento + dados velhos = mostrar `Moving`). Teste vermelho antes.
Aceite: `flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon clean check :android-app:assembleDebug; echo EXIT $?'` em FOREGROUND (nunca background/monitor), reportar o EXIT. Sem BLE real/adb/commit.
