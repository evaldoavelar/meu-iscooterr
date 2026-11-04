# HANDOFF — TASK-BLE-41 (follow-up das etapas 4.0/4.1)
Worker Sonnet, worktree `../wt/ble41`, branch `task/ble41`. **Nada commitado.** Nenhum Bluetooth real, nenhum `adb`. Log TDD: `docs/tdd/ble41-vermelho-verde.md`.

## Comandos realmente executados
Via `flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon -q ...'`:
- `:data:test --tests ...` e `:core-protocol:test --tests ...` por ciclo (vermelho/verde, ver log TDD); `:data:test` e `:core-protocol:test` completos.
- Aceite final: `clean check :android-app:assembleDebug` — resultado na última linha deste arquivo.

## O que foi entregue
1. **MTU.** `GattClient.requestMtu(mtu): Int`; `GattError.MtuFailed`; `ConnectStep.Mtu`; `BleConfig.mtuTimeoutMs=10 s` e `requestedMtu=247`; `ConnectionTimings.mtuMs`; `ConnectFailure.MtuTooSmall(granted)`. Ordem: connect, discover, **requestMtu(247)**, CCCD. **Decisão: MTU falho é FATAL** (erro, recusa, timeout, ou concedido < 30 = maior quadro RX 27 B + 3 B de cabeçalho ATT): falha tipada, GATT fechado, CCCD nunca gravado. `FakeGattClient`: `mtu` (23), `peerMaxMtu` (247), `simulateTruncation` (corta em `mtu-3`), `mtuDelayMs`, `mtuError`. Testado: 27 B truncado a 20 B com MTU 23 (parser: `Truncated`, nenhuma telemetria), íntegro após negociar 247, ordem, timeout, recusa, MTU pequeno. `AndroidGattClient.requestMtu` usa `g.requestMtu` + `onMtuChanged` pelo mesmo `pending` (MTU em `mtuResult`).
2. **Callback de GATT velho.** `data/CallbackGate<T>` (puro, testado): identidade da sessão atual; `expectConnect()` abre janela em que o 1º objeto nunca visto é adotado (callback pode chegar antes de `connectGatt` retornar); `adopt()` (retorno) não troca sessão já adotada; `close()`/novo `expectConnect()` aposentam o objeto (fraco, por identidade) e ele nunca é readotado. `AndroidGattClient`: todos os 6 callbacks passam por `gate.accepts(g)`; `close()` chama `gate.close()`.
3. **Idade por grupo.** `core-protocol`: `FieldGroup {Meter1, Meter2}`, `AgedTelemetry(telemetry, meter1AtMs, meter2AtMs)` com `receive(newer, group)`, `ageMs`, `isStale` (nunca visto = velho; limite exclusivo), `TelemetryDecoder.groupOf(frame)`. `Telemetry` e `overlay` ficaram INTACTOS (compatibilidade: core-domain/UI/Fake e testes existentes não mudaram). `BleScooterTransport.aged: StateFlow<AgedTelemetry?>`; `telemetry` continua emitindo o mesmo valor acumulado. Bateria/bloqueio/limite = grupo Meter1. UI não alterada; a etapa 4.4 deve consumir `aged`, não `receivedAtMs`.

## Desvios
- Ciclos 6, 8, 13 sem vermelho; ciclo 10 com 3 testes de uma vez; `AndroidGattClient` sem teste JVM (framework). Ver log.
- Dois testes antigos de `BleScooterTransportTest` e o `happyPath` tiveram expectativas atualizadas (chamada `mtu:247` e tempos com `mtuDelayMs=50`).

## Decisões a revisar
- MTU pedido 247 (não 517) e limiar fatal 30; timeout 10 s: escolhas minhas, sem medição.
- Granularidade por GRUPO: Meter1 com bateria implausível (`null`) ainda renova o grupo Meter1.
- `CallbackGate`: janela `expectConnect` assume um `connectGatt` por vez (garantido pelo `await` único em `AndroidGattClient`).

## [DESCONHECIDO] restantes
Herdados do HANDOFF-BLE-40 (1–8: nome anunciado, CRC de RX, reagrupamento de fragmentos, telemetria sem CONNECT 0x30, serviço FF60, campos além de bateria/limite/lock, t_scan/t_conn, `neverForLocation`). Novos:
9. `[HIPÓTESE]` MTU negociado é necessário (T3-T7 cita `setMtu` no original; a S2 não registrou MTU).
10. `[DESCONHECIDO]` MTU máximo que o patinete aceita e se aceita `requestMtu` (o Android pode ainda ter negociado automaticamente; não medido).
11. `[DESCONHECIDO]` se `onMtuChanged` chega sempre (alguns stacks omitem em MTU já igual); o timeout de 10 s cobre, mas seria falha fatal falsa.
12. `[DESCONHECIDO]` idade máxima aceitável de bateria/lock para a UI (4.4); `isStale` recebe o limite do chamador.

**Resultado final:** `clean check :android-app:assembleDebug` (flock + android-env.sh, `-q`) = sem FAILED/FAILURE na saída (apenas as linhas de relatório do lint), ~3 min+; código de saída do gradle não capturado diretamente (o `echo` do pipeline saiu vazio), conclusão pela ausência de erro.

## Revisão Sonnet r1 (APROVADO, `.omc/reviews/REVIEW-ble41-r1.md`) — pendências a tratar ANTES da 4.2
- **A1 [ALTO condicional]** `AndroidGattClient.onMtuChanged` chama `finish()`, que completa qualquer `pending`. Um `onMtuChanged` espontâneo (Android 14+ negocia 517 sozinho) durante `connect`/`discoverServices` encerraria a operação errada (ServiceMissing falso); e se o MTU já estiver negociado, `requestMtu` pode não gerar callback (timeout vira falha fatal falsa). Corrigir com um deferred de MTU separado e política "MTU já ≥ 30 vale". `[HIPÓTESE]` sobre o comportamento do stack.
- **M1** `aged` só existe em `BleScooterTransport`; `ScooterTransport` continua emitindo o overlay (bateria/lock velhos parecem frescos). Pré-requisito da 4.4 (e da 4.3 se a UI mostrar bateria/lock): expor idade por grupo pela interface/estado de domínio.
- **M2** falta teste de queda de enlace no meio do passo MTU.
