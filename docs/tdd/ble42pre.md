# TDD — BLE-42pre (A1, M1, M2) — vermelho/verde
Comando de cada ciclo: `flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon -q :<modulo>:test --tests ...'`. Esperados escritos à mão (Meter1 em t=1000/Meter2 em t=6000 => 5000 ms; MTU 247/517/23; limiar 30).

| # | Teste | Vermelho observado | Verde |
|---|-------|--------------------|-------|
| 1 | `MtuNegotiatorTest.callbackAfterTheRequestIsTheResult` | `expected:<247> but was:<0>` (stub de `request` devolvia 0) | `request` espera o deferred de MTU |
| 2 | `...spontaneousChangeBeforeTheRequestIsRememberedAndUsedWhenNoCallbackFollows` | `UncompletedCoroutinesError` (sem callback, `request` esperava para sempre) | `withTimeoutOrNull(graceMs)` e fallback para o MTU observado >= 30 |
| 3 | `...smallObservedMtuIsNotEnoughSoItKeepsWaiting...` | sem vermelho (caracterização, escrito junto do ciclo 2) | — |
| 4 | `refusedCallback...`, `stackRefusing...`, `resetForgets...`, `spontaneousChangeWhileNobodyWaits...` | sem vermelho (caracterização do código do ciclo 1-2; declarado) | — |
| 5 | `closingTheLinkFailsAPendingRequestWithDisconnected` | `o pedido pendente deve falhar ao fechar` (stub `onClosed` vazio) | `onClosed` completa o deferred com `Disconnected` |
| 6 | `BleScooterTransportTest.lostLinkDuringTheMtuStepIsTypedAndNothingIsSubscribed` (M2) | sem vermelho (caracterização: o código já estava correto, como previu a revisão); declarado | — |
| 7 | `HomeGroupAgeTest.staleMeter1IsReported...` | `AssertionError` (`meter1Stale` stub `false`) | `GroupAgesReceived` guarda as idades; `meter1Stale` por limite |
| 8 | `HomeGroupAgeTest.neverSeenMeter1IsStale...` | `AssertionError` (nunca visto devolvia não velho) | nunca visto + telemetria existente = velho; só conectado |
| 9 | `HomeGroupAgeTest.freshMeter1...`, `nothingIsStaleBefore...` | sem vermelho (caracterização, escritos após o ciclo 8) | — |
| 10 | `HomeControllerTest.idade por grupo do transporte chega ao estado...` | `expected:<1000> but was:<null>` (interface com `agedTelemetry` padrão; controlador não coletava) | `HomeController` coleta `agedTelemetry` |
| 11 | `BleScooterTransportTest.agedTelemetryThroughTheInterfaceKeepsTheStaleMeter1Time` | `expected:<10> but was:<5010>` (padrão da interface derivado de `telemetry` mascarava o Meter1) | `BleScooterTransport.agedTelemetry` = `_aged.filterNotNull()` |

Sem vermelho observado (declarado): ciclos 3, 4, 6, 9. `AndroidGattClient` continua sem teste JVM (framework): a regra (deferred de MTU separado, MTU já negociado, queda) foi extraída para `MtuNegotiator` e testada; a ligação é validada por compilação/lint.
