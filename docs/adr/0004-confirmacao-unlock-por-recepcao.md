# ADR 0004 — Ordenar a confirmação do Unlock pela recepção

Data: 2026-11-05. Escopo: correção de autoria da etapa 4.4, revisão independente r1 de `ea04bc5`; sem aprovação da própria alteração e sem ensaio BLE.

## Contexto

[ESTÁTICO] Atribuir frescor no consumo permite que um Meter1 desbloqueado já no buffer confirme uma tentativa posterior. Cancelar a coroutine também pode abandonar uma escrita no stack. Referências: revisão `S19-unlock-r1.md`, P1-1/P1-2; regressões em `BleScooterTransportUnlockTest.anUnlockedMeter1BufferedBeforeTheWriteCannotConfirmIt` e `callerCancellationDuringWriteOrConfirmationClosesBeforeRethrowingAndRefusesAnotherUnlock`.

## Decisão

- [ESTÁTICO] `GattNotification.receivedSequence` é atribuído no callback Android ou em `FakeGattClient.emit`, antes do buffer. O contador é atômico e não reinicia entre conexões. Ao concluir `gatt.write`, `BleScooterTransport.unlock` lê `GattClient.receivedSequence`; só um Meter1 desbloqueado com sequência estritamente maior confirma a tentativa. ACK sozinho continua insuficiente. Referências: `GattClient.kt`, `AndroidGattClient.SessionCallback`, `BleScooterTransport.unlock/onNotification`.
- [ESTÁTICO] A fronteira escolhida é a conclusão da escrita, permitida pelo contrato do proprietário. Quadros recebidos durante a escrita são descartados para confirmação, mesmo se o coletor só os consumir depois. O prazo total continua contado do início do envio. Referência: `BleScooterTransport.unlock`.
- [ESTÁTICO] Cancelamento após iniciar o envio encerra a sessão como `WriteTimedOut`, em `NonCancellable`, antes de relançar `CancellationException`; aguardar o mutex sem iniciar envio não fecha o enlace. Referência: `BleScooterTransport.unlock`.
- [ESTÁTICO] A montagem usa um único payload temporário e o frame final, sem concatenações que deixem cópias dos dígitos. Payload, frame enviado e cópia Android são apagados em `finally`, inclusive em erro/cancelamento. A lista fechada de comandos e `Pin.wipe()` permanecem. Referências: `Pin.unlockPayload`, `TxFrameBuilder.build`, `BleScooterTransport.unlock`, `AndroidGattClient.write`, `TxFrameTest`.

## Limites

[DESCONHECIDO] Comportamento no equipamento após estas correções: não ensaiado. Os testes JVM verificam o contrato do software, sem confirmar protocolo ou atuação física. Cópias internas do framework/stack não são controláveis por esses buffers; não se afirma eliminação de todas as cópias em memória.
