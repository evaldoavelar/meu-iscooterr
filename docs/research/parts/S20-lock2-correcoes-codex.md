# S20 — correções de LOCK modo 2 e do gate de Unlock

Data: 2026-11-05. Autoria: Codex. Base: `9ee31b5`, branch `sprint-20/lock-mode2`, worktree `.wc/lock2`. Sem commit, merge, push, adb ou BLE real. Este registro não é revisão nem aprovação.

## Fontes e implementação

- [ESTÁTICO] Achados recebidos no pedido e lidos em `/home/evaldo/Documents/ix7/.omc/reviews/S20-speed-r1.md`, `S20-lock2-r2.md` e `S20-lock2-agy-r2.md`. Fontes normativas: `docs/sprints/s20-lock-mode2.md` e ADR 0004. Escopo: os quatro itens do pedido de correção.
- [ESTÁTICO] Os dois comandos recusam `PendingReception` dentro de `writeLock` quando a fronteira RX está à frente da última sequência inteiramente consumida. Essa recusa não constrói/escreve o quadro nem arma uma confirmação. Depois de consumir Meter1 de movimento, a recusa é `Moving`. A UI informa que é preciso aguardar a leitura; não há retry automático. Referências: `BleScooterTransport.unlock/lock/onNotification`, `Protocol.LockRefusal/UnlockRefusal`, `HomeUiMapper.connection/lockRefusalText`; decisão no ADR 0004, emenda S20.
- [ESTÁTICO] Sem `pinUnlock`, falhas de lock não selecionam o fluxo PIN quando o Meter1 posterior diz bloqueado. O erro permanece no chip e o botão usa o desbloqueio legado. O rótulo confirmado exige `LockResult.Locked` e idade menor que 2000 ms, inclusive quando ainda não houve `Tick` para limpar o resultado. Referências: `HomeUiMapper.usesLockMode2Button/lockConfirmationVisible/pinMainButton`; janela em `HomeRules.CONFIRMED_VISIBLE_MS`.
- [ESTÁTICO] O Fake guarda no snapshot o carimbo da última emissão. `lock()` e `unlock()` recusam com `TelemetryStale` a partir de 2000 ms, antes da velocidade e do estado de bloqueio, sem alterar o estado. Nesta base, ao contrário da premissa do pedido, `unlock()` ainda não verificava idade; a correção aplica o mesmo critério aos dois comandos. Referências: `FakeScooterTransport.emitNow/lock/unlock`, limite do real em `BleScooterTransport.lockRefusal/unlockRefusal` e sprint S20.
- [ESTÁTICO] `lockRefusal` exige `locked == false`, mantendo a prioridade enlace → frescor → movimento da roda → velocidade → `AlreadyLocked`. O Fake também exige `locked == false`. O teste de estado desconhecido injeta apenas o acumulador por reflexão: o decoder atual sempre produz Boolean em Meter1 válido; não se inventou quadro para gerar null nem se ampliou a API de produção. Referências: `BleScooterTransport.lockRefusal`, `FakeScooterTransport.lock`, `TelemetryDecoder.meter1`, `BleScooterTransportLockTest.unknownLockStateRefusesAfterFreshnessMotionAndSpeedChecks`.

## TDD: expectativas antes/depois (não executadas)

Os testes principais foram escritos antes da correção funcional correspondente; sem execução não há vermelho/verde observado. Após acrescentar `PendingReception` ao contrato, as regressões abaixo devem falhar por comportamento na base antiga e passar com as correções:

| Classe / teste novo | Antes | Depois |
| --- | --- | --- |
| `BleScooterTransportUnlockTest.aMovingMeter1PendingDeliveryRefusesWithoutWritingThenRefusesAsMoving` | Unlock escreve 0x32 usando o Meter1 parado antigo e não recusa imediatamente | `PendingReception`, zero escritas; liberar entrega → `Moving`, zero escritas |
| `BleScooterTransportLockTest.aMovingMeter1PendingDeliveryRefusesWithoutWritingThenRefusesAsMoving` | Lock escreve 0x32 usando o Meter1 parado antigo | Mesmas recusas e zero escritas |
| `BleScooterTransportUnlockTest.receptionWhileWaitingForTheWriteLockIsCheckedBeforeUnlockWrites` | Após aguardar heartbeat, escreve apesar de RX pendente | Recusa `PendingReception` ao obter o mutex; depois `Moving` |
| `BleScooterTransportLockTest.receptionWhileWaitingForTheWriteLockIsCheckedBeforeLockWrites` | Mesmo defeito para lock | Mesma proteção dentro do mutex |
| `HomeLockModeTwoUiTest.lockFailuresWithoutPinUnlockKeepLegacyUnlockAfterALaterLockedMeter1` | Timeout, demais causas de indeterminação, `Rejected` e recusas deixam o botão desabilitado pedindo PIN | Botão legado habilitado, sem PIN; chip de erro |
| `HomeLockModeTwoUiTest.theLockedLabelExpiresAtTwoSecondsEvenWithoutAReducerTick` | Resultado confirmado permanece visível com `Finished` retido | Visível em 1999 ms; em 2000/2001 ms volta a Lock/Unlock conforme telemetria, com e sem PIN |
| `FakeScooterTransportLockTest.aStaleSnapshotRefusesLockWithoutChangingStateUntilFreshTelemetryArrives` | Depois de desbloquear/congelar/avançar 2001 ms, bloqueia | `TelemetryStale`, mesma telemetria/estado; nova emissão permite bloquear |
| `FakeScooterTransportLockTest.lockTelemetryAgeMustBeStrictlyLessThanTwoSeconds` | Aceita também idade de 2000 ms | Aceita 1999 ms, recusa 2000 ms |
| `FakeScooterTransportPinTest.aStaleSnapshotRefusesUnlockWithoutChangingStateUntilFreshTelemetryArrives` | Desbloqueia com snapshot de 2001 ms | `TelemetryStale`, estado inalterado; nova emissão permite desbloquear |
| `BleScooterTransportLockTest.unknownLockStateRefusesAfterFreshnessMotionAndSpeedChecks` | Null permite escrever | Null recusa `AlreadyLocked` sem escrita; frescor/movimento/velocidade mantêm precedência |
| `HomeLockModeTwoUiTest.pendingReceptionAsksTheUserToWaitAndAllowsAnotherTap` | Sem texto específico para Unlock (novo enum exige também ramo no texto de Lock) | Textos de espera e nova ação disponível nos dois fluxos |

[ESTÁTICO] Também foram adaptados testes antigos incompatíveis com a nova recusa anterior à escrita: `anUnlockedMeter1BufferedBeforeTheWriteRefusesWithoutWriting`, `aLockedMeter1ReceivedBeforeTheWriteRefusesWithoutWriting`, `aPinRejectionBufferedFromAttemptACannotRejectAttemptBButANewOneCan` e `aPinRejectionPausedBetweenClockAndSequenceCannotRejectAttemptB`. Agora exigem zero novas escritas enquanto há RX pendente e nova ação explícita depois de drenar o RX. O teste de Meter1 durante a escrita continua cobrindo a fronteira posterior. Referências: `BleScooterTransportUnlockTest`, `BleScooterTransportLockTest`.

## Verificação e próximos passos do coordenador

- [ESTÁTICO] Executados nesta sessão: leituras com `cat`/`sed`/`rg`, `git status --short`, `git branch --show-current`, `git rev-parse --short HEAD`, `git diff`, `git diff --stat` e `git diff --check` (sem erros de whitespace). Não foram executados Gradle, compilador Kotlin, testes ou JaCoCo.
- [ESTÁTICO] O diff não altera o builder de quadros nem a lista fechada de comandos. `BleScooterTransport.send()` continua rejeitando tudo; nenhuma leitura de `lockWithPin` foi adicionada. Exclusão entre lock/unlock, tentativa única e confirmação exclusivamente por Meter1 posterior à escrita permanecem no transporte. Referências: `TxFrameBuilder`, `TxCommand`, `BleScooterTransport.send/unlock/lock/onNotification` e diff contra `9ee31b5`.
- [DESCONHECIDO] Compilação, execução dos testes e cobertura desta correção: pendentes. O coordenador deve executar as classes alteradas e as suítes existentes, incluindo exclusão lock×unlock, cancelamento, 3000/3001 ms e proibição dos demais quadros. Comandos sugeridos, NÃO executados (a partir de `app/`): `./gradlew :core-protocol:test :data:test :ui:testDebugUnitTest` e, para aceitação completa, `./gradlew clean check` com os gates JaCoCo de 80%. Revisão independente continua necessária; autor não aprova.
- [DESCONHECIDO] Efeito no dispositivo físico: não ensaiado nesta sessão.
- [ESTÁTICO] No `git status` final também apareceu `app/.kotlin/` não rastreado (`sessions/kotlin-compiler-4910315506300567378.salive`). Nenhum compilador foi iniciado por esta sessão; esse diretório não foi alterado nem removido pela autoria.

## Correção r4 — lacuna de recepção (base `179215f`)

- [ESTÁTICO] `AndroidGattClient.SessionCallback.onCharacteristicChanged` carimba antes de `tryEmit`. Um descarte pode
  deixar a fronteira RX novamente drenada após consumir N+1, preservando o Meter1 parado de antes de N. A regressão
  `BleScooterTransportUnlockTest.aDroppedMovingMeter1RefusesUntilANewStoppedMeter1Arrives` reproduziu uma escrita
  indevida (total passou de 2 para 3) antes da correção. Log: `/tmp/s20-lock2-r4/unlock-red.log`.
- [ESTÁTICO] `BleScooterTransport.onNotification` agora invalida `meter1AtMs` antes do parsing ao detectar sequência
  diferente da última + 1. Meter2, ACK e inválido não restauram o frescor. Outro Meter1 válido o restaura. A primeira
  recepção de cada conexão ancora a comparação; `GattReception` e o gate `PendingReception` permanecem intactos.
  Decisão: [ADR 0004, adendo S20 r4](../../adr/0004-carimbo-recepcao-na-chegada.md).
- [ESTÁTICO] `FakeGattClient.emit(dropAfterReception = true)` consome o carimbo sem entregar o quadro. Os testes de
  unlock e lock usam o Meter1 de movimento literal S19:1063, exigem `TelemetryStale`, zero escritas e recuperação
  confirmada somente após Meter1 novo. Referências: testes `aDroppedMovingMeter1RefusesUntilANewStoppedMeter1Arrives`
  nas duas classes; não se derivam expectativas do código testado.
- [ESTÁTICO] `RxStats.droppedFrames` conta números ausentes e participa de `minus`/`observe()`. O teste
  `BleScooterTransportTest.droppedFramesCountsOnlyGapsWithinEachConnectionAndObservationWindow` cobre múltiplas
  perdas, diferença da janela, nova conexão sem reiniciar sequência e perda posterior à reconexão. Vermelho observado:
  esperava 2 perdas, obteve 0 (`/tmp/s20-lock2-r4/stats-red.log`).
- [DESCONHECIDO] Validação Gradle/JaCoCo e BLE físico desta rodada: não executados. O relatório de autoria e os
  resultados de execução direta Kotlin/JUnit ficam em `.omc/reviews/S20-lock2-r4.md`; não constituem revisão independente.
- [ESTÁTICO] Execução direta Kotlin 2.4.21/JUnit 4.13.2, sem Gradle: três testes novos verdes; os dois de comando
  também falharam contra `BleScooterTransport` original de `179215f`, ambos por escrita indevida (2 → 3).
  Suíte `data`: 224 testes, 218 passaram; seis de `TileStorageTest` falharam ao abrir socket local proibido pelo
  sandbox (`SocketException: Operation not permitted`). Todos os 124 testes de transporte BLE passaram.
  Referências: `/tmp/s20-lock2-r4/green.log`, `baseline-red.log` e `data-suite.log` no mesmo diretório.
