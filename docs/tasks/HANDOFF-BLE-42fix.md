# HANDOFF — BLE-42fix (worktree wt/ble42fix, branch task/ble42fix, sem commit)

## Feito (TDD; vermelhos observados em `docs/tdd/ble42fix.md`)
- P1: `BleScooterTransport.connect()` registra `sess.invokeOnCompletion { terminate(ep, null) }`; cancelar o escopo após Connected fecha o GATT e marca Disconnected.
- P3: `AgedTelemetry.receive` (grupo Meter1) zera locked/bateria/limite que vieram `null`; nunca mantém valor velho com idade renovada. Meter2 não limpa campos do Meter1.
- P4: `TelemetryDecoder.groupOf/decode` exigem payload de 18 B (S2); `isMalformedTelemetry` faz o transporte contar em `stats.invalid` (não vira telemetria nem confirma conexão). Fixtures sintéticos de 8 B em `TelemetryDecoderTest` foram ajustados para 18 B.
- M-1: `AndroidGattClient` chama `mtu.onClosed()` na desconexão (sem teste JVM; validado por compilação).
- B-1: `MtuNegotiator`: sucesso < 30 durante a espera não encerra o pedido; se nada melhor vier em `graceMs`, ele é a resposta (-> MtuTooSmall). r2: se o sucesso pequeno chegar DEPOIS da graça, ele completa o pedido na hora (`graceOver`), sem esperar o timeout externo.

## Pendência (NÃO feita, regra 2)
- P2 `meter1Stale` não é consumido na UI (`HomeViewModel`/`HomeUiMapper`): tratar na 4.3 junto do protótipo.

## Comando executado
`flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon clean check :android-app:assembleDebug'` -> EXIT 0 (BUILD SUCCESSFUL).
Nenhum BLE real, escrita BLE, commit, push ou adb.
