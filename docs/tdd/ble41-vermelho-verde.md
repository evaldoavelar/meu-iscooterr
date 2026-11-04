# TDD — TASK-BLE-41 (vermelho/verde)
Comando de cada ciclo: `flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon -q :data:test --tests ...'` (ou `:core-protocol:test`). Esperados escritos à mão (spec ATT: payload = MTU-3; vetor Meter1 de 27 B da S2; idades = diferença de tempos inventados no teste).

| # | Teste | Vermelho observado | Verde |
|---|-------|--------------------|-------|
| 1 | `mtuIsRequestedBetweenDiscoveryAndSubscribe` (ordem connect, discover, mtu:247, subscribe) | calls sem `mtu:247` (stub `requestMtu` na interface/Fake para compilar) | transporte chama `requestMtu(247)` entre discover e CCCD; 2 testes antigos tiveram a lista de chamadas atualizada (mudança pretendida) |
| 2 | `mtuStepTimesOutAndClosesTheGatt` | Fake ignorava `mtuDelayMs` (sem timeout) | `requestMtu` do Fake usa `op(delay)`; `ConnectFailure.Timeout(Mtu)` |
| 3 | `refusedMtuIsFatalAndTyped` | `mtuError` ignorado, conexão seguia até `NoTelemetry` | Fake aplica `mtuError`; mapeamento existente dá `Gatt(MtuFailed)` |
| 4 | `FakeGattClientTest.defaultMtuTruncatesA27ByteFrameTo20Bytes` | quadro chegou com 27 B | `simulateTruncation` corta em `mtu-3`; parser dá `Truncated` |
| 5 | `requestMtuReturnsWhatThePeerGrantsAndFramesFitAfterwards` | `requestMtu(247)` devolveu 247 com teto 100 | `min(pedido, peerMaxMtu)` e atualiza `mtu` |
| 6 | `frameIsIntactAfterMtuNegotiationEvenWhenTruncationIsSimulated` | já verde (caracterização da integração; não houve vermelho) | — |
| 7 | `grantedMtuTooSmallForA27ByteFrameIsFatal...` | `MtuTooSmall` ainda não existia na lógica (falhava no ciclo, tipo adicionado junto ao teste) | `granted < 30` → `ConnectFailure.MtuTooSmall` |
| 8 | `happyPath...` atualizado com `mtuMs = 50` | (campo `mtuMs` escrito junto com a lógica no ciclo 7: sem vermelho isolado) | verde |
| 9 | `CallbackGateTest.acceptsOnlyTheAdoptedSession` | stub `accepts=false` | `current === incoming` |
| 10 | `CallbackGateTest` ×3 (callback antes de adotar/esperar; janela `expectConnect`; sessão fechada não readotada) — escritos juntos (desvio) | 2 de 5 vermelhos por comportamento (1º erro foi de compilação, resolvido com stubs `expectConnect/close` vazios); o teste "close sem sessão" e "antes de tudo" passam trivialmente | `CallbackGate` completo (adoção na janela, `retired` por identidade fraca) |
| 11 | `AgedTelemetryTest.staleMeter1StaysOld...` (Meter1 há 5000 ms + Meter2 fresco) | stub `ageMs=null` | `ageMs`/`receive` por grupo |
| 12 | `staleMeansOlderThanTheLimit...` | stub `isStale=false` | `isStale` (nunca visto = velho; limite exclusivo) |
| 13 | `receivingOneGroupKeepsTheOtherGroup...` | já verde (caracterização) | — |
| 14 | `TelemetryDecoderTest.frameGroupFollowsTheCmd` | stub `groupOf=null` | `groupOf` por CMD/tipo |
| 15 | `fieldAgesSurviveAMeter2OnlyStream` (transporte) | `aged` stub sempre `null` | transporte acumula via `AgedTelemetry.receive` |

Sem vermelho observado (declarado): ciclos 6, 8, 13. `AndroidGattClient` (`requestMtu`, uso do `CallbackGate`) é código de framework: sem teste JVM, validado só por compilação/lint.
