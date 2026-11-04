# HANDOFF — BLE-42pre (A1, M1, M2 da revisão Sonnet r1 do BLE-41)
Worker Sonnet, worktree `../wt/ble42pre`, branch `task/ble42pre`. **Nada commitado.** Nenhum BLE real, nenhum `adb`, nenhuma escrita além do CCCD, sem CONNECT 0x30/heartbeat/PIN. Log TDD: `docs/tdd/ble42pre.md`.

## Comandos realmente executados
- Por ciclo: `flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon -q :data:test --tests ...'` (e `:core-domain:test`, `:core-domain:test --tests ...`).
- Aceite: `flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon clean check :android-app:assembleDebug; echo EXIT $?'` => **BUILD SUCCESSFUL, EXIT 0** (3m56s).
- Apagado: `docs/tasks/REVIEW-ble41-r1.copy.md` (cópia da revisão).

## Entregue
1. **A1.** `data/MtuNegotiator` (puro, testado): o passo MTU tem deferred próprio, separado de `pending`. `onMtuChanged` espontâneo só anota o MTU observado e nunca completa connect/discover/CCCD. Se o `requestMtu` não gerar callback (MTU já negociado), após `graceMs=1500` vale o MTU observado desde que >= 30 (`MIN_USABLE_MTU`); abaixo disso continua esperando (o timeout de 10 s do transporte segue fatal). Callback com status de erro => `MtuFailed`; `close()` => `Disconnected`; nova sessão `reset()`. `AndroidGattClient` usa o negociador (sem `mtuResult`); continua sem teste JVM, validado por compilação/lint.
2. **M1.** `ScooterTransport.agedTelemetry: Flow<AgedTelemetry>` (padrão deriva de `telemetry`, serve ao Fake); `BleScooterTransport` sobrescreve com a idade real por grupo. `HomeController` coleta e despacha `HomeEvent.GroupAgesReceived`; `HomeState.meter1AtMs/meter2AtMs` e `HomeState.meter1Stale` (conectado + telemetria existente + Meter1 nunca visto ou mais velho que `HomeRules.STALE_AFTER_MS`). Teste: Meter1 parado + Meter2 ativo.
3. **M2.** `lostLinkDuringTheMtuStepIsTypedAndNothingIsSubscribed`: queda em 500 ms com `mtuDelayMs=10_000` => `LinkLost`, sem `subscribe`, sem escrita.

## Pendente / decisão (NÃO feito de propósito)
- **UI não consome `meter1Stale`.** Mostrar bateria/bloqueio como desconhecido (ou aviso) quando velhos muda a tela => regra 2 (protótipo `.figcraft` antes). Só o domínio expõe o dado; nenhuma regra de disponibilidade de comandos mudou (`restrictedAvailability`/`unlockAvailability` intactas). Pré-requisito da 4.3/4.4: decidir no protótipo e então ligar `HomeUiMapper`.
- O limiar de idade do Meter1 reutiliza `STALE_AFTER_MS = 2 s` (`[HIPÓTESE]`, vale 12 abaixo).

## Desvios (TDD)
Ciclos 3, 4, 6, 9 sem vermelho (caracterização; no 6 o código já estava correto, como previu a revisão). Detalhe no log.

## [DESCONHECIDO] atualizados
9. `[HIPÓTESE]` MTU negociado é necessário (inalterado).
10. `[DESCONHECIDO]` MTU máximo do patinete / se aceita `requestMtu` (inalterado, não medido).
11. `[DESCONHECIDO]` se `onMtuChanged` chega sempre: **mitigado em código** (A1: espontâneo não quebra outras operações; MTU já >= 30 vale após 1,5 s sem callback), mas `[HIPÓTESE]` o comportamento real do stack (Android 14+ negociando 517 sozinho) segue **não medido**; 1500 ms de espera é escolha sem medição.
12. `[DESCONHECIDO]` idade máxima aceitável de bateria/lock (inalterado; o domínio agora usa 2 s provisório, e a UI não consome).
13. `[DESCONHECIDO]` se `AndroidGattClient` recebe `onMtuChanged` de status de erro espontâneo sem pedido em curso: hoje é ignorado (só anotado se sucesso).
