# TDD — BLE-42fix (revisão Codex r1: P1, P3, P4; extras Sonnet M-1, B-1) — vermelho/verde
Comando: `flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon -q :<modulo>:test --tests ...'`. Esperados escritos à mão a partir de `docs/research/parts/S2-captura-resultados.md` (LEN 18) e da revisão.

| # | Teste | Vermelho observado | Verde |
|---|-------|--------------------|-------|
| 1 | `TelemetryDecoderTest.meter2WithEmptyPayloadIsNotTelemetry` (P4) | `expected null, but was:<Telemetry(receivedAtMs=0, ...)>` | `groupOf` exige payload de 18 B; `decode` usa `groupOf` |
| 1b | fixtures sintéticos `lockHasPasswordBit...`/`implausibleBattery...` | falharam após o ciclo 1 (payload de 8 B violava S2) | fixtures ajustados para 18 B (justificado: S2 só registra LEN 18) |
| 2 | `BleScooterTransportTest.meter2WithWrongPayloadSizeIsInvalidAndNeverConfirmsTheConnection` (P4, `1aa121030000001ff1`) | `expected RxStats(valid=0, telemetry=0, invalid=3) but was RxStats(valid=3, telemetry=0, invalid=0)` | `isMalformedTelemetry` conta em `invalid` |
| 3 | `AgedTelemetryTest.invalidBatteryInANewMeter1DoesNotKeepTheOldValueAsFresh` (P3) | `expected:<null> but was:<100>` | `receive` do grupo Meter1 zera locked/bateria/limite que vieram `null` |
| 3b | `AgedTelemetryTest.meter2DoesNotClearMeter1Fields` | sem vermelho (caracterização, escrito junto do ciclo 3; declarado) | — |
| 4 | `BleScooterTransportTest.cancellingTheTransportScopeAfterConnectedClosesTheGatt` (P1) | `expected:<1> but was:<0>` (closeCalls) | `sess.invokeOnCompletion { terminate(ep, null) }` |
| 5 | `MtuNegotiatorTest.smallSuccessWhileWaitingDoesNotCloseTheRequest...` (B-1) | `expected:<247> but was:<23>` | sucesso < 30 durante a espera só vale após a janela de graça |
| 5b | `MtuNegotiatorTest.smallSuccessWithNothingBetterAfterTheGraceWindowIsTheAnswer` | sem vermelho (caracterização do ciclo 5; declarado) | — |
| 6 | M-1 `mtu.onClosed()` no ramo de desconexão do `AndroidGattClient` | sem teste JVM (framework Android); validado por compilação | — |
