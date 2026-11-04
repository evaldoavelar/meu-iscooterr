# HANDOFF — stale (Meter1 velho na UI; P2 nº 2 do Codex r1)
Worktree `../wt/stale`, branch `task/stale`. Nada commitado, sem BLE real/adb. Log TDD: `docs/tdd/stale.md`. Cópia da revisão apagada.

## Comandos executados
- Ciclos: `flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon -q :core-domain:test` / `:ui:testDebugUnitTest --tests ...'`.
- Aceite: `flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon clean check :android-app:assembleDebug; echo EXIT $?'` => BUILD SUCCESSFUL, **EXIT 0** (5m30s).

## Entregue (com `meter1Stale` = true)
- Home: bateria `--`. Telemetria: bateria `--` (`TelemetryUiMapper.batteryStale`, ligado em `HomeViewModel`).
- `HomeReducer.availability`: `SetLocked(true)` desabilitado, motivo `TelemetryStale` ("Dados desatualizados — aguardando o patinete"); botão principal mostra Bloquear desabilitado com esse motivo (estado já existente). Nenhum comando foi habilitado; desbloqueio e farol inalterados.
- Fake não fica stale em uso normal (teste `Fake em uso normal...`); screenshots S5 verdes sem `--update-snapshots`.

## Para o proprietário revisar
Nenhum screenshot novo/alterado. Estados novos (só texto de estados existentes): Home e Telemetria com bateria `--` + Bloquear desabilitado com "Dados desatualizados" quando só o Meter1 envelhece. Não há tela de gold para esse estado.

## Decisões / pendências
- Bloqueio "desconhecido" (oferecer Desbloquear) foi descartado: quebraria o comportamento de telemetria velha existente. Só o Bloquear é desabilitado.
- Motores/modo/limite/cruise seguem regidos só pelo timestamp geral (não está comprovado de qual grupo vêm; `[DESCONHECIDO]`).
- Limiar 2 s herdado de `STALE_AFTER_MS` (`[HIPÓTESE]`). Fixtures de 4 arquivos de teste ganharam `meter1AtMs`.
