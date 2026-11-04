# HANDOFF — TASK-BLE-40 (Fase 4, etapas 4.0 e 4.1)
Worker Sonnet, worktree `../wt/ble40`, branch `task/ble40`. **Nada commitado.** Nenhum Bluetooth real, nenhum `adb`.

## Comandos realmente executados
Todos via `flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon ...'`:
- `-q :core-protocol:test` (vermelho por compilação → verde): parser RX; depois decodificador (vermelho → verde).
- `-q :core-protocol:check` (primeiro falhou o gate de cobertura 0,79; corrigido com `ProtocolTest`; verde).
- `-q :data:test` (vermelho → verde) para `FakeGattClient`; depois `BleScooterTransportTest` (vermelho; 2 testes com expectativa minha errada corrigidos — ver abaixo); `ScooterDiscoveryTest` (vermelho → verde); `RealBleSelectionTest`.
- `-q :data:check`, `-q :android-app:assembleDebug`, `-q :android-app:lintDebug`.
- `clean check :android-app:assembleDebug`: 1ª execução falhou no `lintDebug` (MissingPermission/WrongConstant em `AndroidGattClient`); corrigido; resultado da 2ª execução: ver última linha deste arquivo.

Desvio de processo: o TDD foi por grupo de comportamento (vários testes vermelhos por ciclo), não um teste por vez; em `RealBleSelection` teste e implementação foram escritos juntos (sem vermelho observado).
Dois testes de `BleScooterTransportTest` falharam por expectativa errada minha (tempo de `at()` relativo; `firstTelemetryMs` definido desde o INÍCIO da inscrição, 250 e não 150), não por bug do código.

## O que foi entregue
- `core-protocol`: `RxFrame.kt` (`RxFrameParser`, `RxError`, `Crc16Modbus`; `crcMatches` informativo, CRC de RX nunca exigido), `TelemetryDecoder.kt` (Meter1: lock = dado[1] bit5, bateria = dado[3], limite = dado[7]; Meter2 só reconhece; `Telemetry.overlay` acumula os dois), testes com vetores reais de `s2d`. `build.gradle.kts` ganhou JUnit + gate JaCoCo 80 % (cópia do de `:data`). `Protocol.kt` intocado.
- `data`: `GattClient`/`FakeGattClient`, `BleScooterTransport` (+ `BleConfig`, `ConnectFailure`, `ConnectionTimings`, `RxStats`, `observe()` de 60 s), `ScooterScanner`/`FakeScooterScanner`/`ScooterDiscovery`/`NameFilter`, `RealBleSelection`/`GattProperties`.
- `android-app` (escolhido em vez de módulo `:ble`: sem novo projeto Gradle, e é o único consumidor; código de framework fora da cobertura, que só mede `data`/`core-*`): `ble/AndroidGattClient` (write lança: escrita fisicamente desabilitada), `ble/AndroidBleScanner` (não ligado em lugar nenhum), manifest com `BLUETOOTH_SCAN` (`neverForLocation`) e `BLUETOOTH_CONNECT` (+ legadas ≤30), `MainActivity` com chave `--ez ble_real true --es ble_address MAC` só em build depurável; padrão continua `FakeScooterTransport`.

## Decisões a revisar
- Timeouts por passo (10 s: conexão, serviços, CCCD) e janela de varredura são escolha minha, sem medição.
- 1ª telemetria = 1º quadro Meter1/Meter2 decodificável (ACK/lixo não contam); quadro que chega antes do CCCD terminar vale.
- Meter2 produz `Telemetry` só com `receivedAtMs`; o transporte emite o acumulado (overlay), não o quadro cru.
- Modos de velocidade: o transporte real devolve catálogo vazio (nomes são `[HIPÓTESE]` só do Fake).
- Permissão em tempo de execução: `MainActivity` só pede `BLUETOOTH_CONNECT` no modo real; falha de permissão vira `ConnectFailure.Gatt(PermissionDenied)` e a UI atual não a exibe.

## [DESCONHECIDO] encontrados
1. Nome anunciado do patinete (e UUID/manufacturer do anúncio): nenhuma captura/estático o registra; `NameFilter` vazio por padrão, proprietário escolhe na lista.
2. CRC de RX: segue desconhecido; todos os 3559 quadros RX da S2 têm `crcMatches=false`.
3. Reagrupamento de notificações fragmentadas: nenhuma evidência (todos os quadros RX da S2 completos numa notificação); fragmento vira erro tipado (`Truncated`/`TooShort`/`BadHeader`).
4. Se a telemetria chega sem `CONNECT 0x30`/heartbeat: só a etapa 4.2 responde; o código não envia nada.
5. Serviço `FF60` (hoje `[ESTÁTICO]`): exigido, mas só confirmável em 4.2.
6. Campos de Meter1/Meter2 além de bateria, limite e lock (velocidade, V, I, temperaturas, odômetros): continuam `null`.
7. Tempos t_scan/t_conn reais: não medidos (S2 sem HCI); `ConnectionTimings` os medirá em 4.2.
8. Se o `neverForLocation` impede ver o nome via `scanRecord` em algum aparelho: não testado.

**Resultado final:** `clean check :android-app:assembleDebug` (comando de aceite completo) = BUILD SUCCESSFUL, exit 0, 3 min 35 s; 2ª execução, após o ajuste de lint.
