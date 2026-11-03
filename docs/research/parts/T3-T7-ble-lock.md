# T3–T7 (parcial) — Camada BLE e comando de lock — base.apk 1.0.20
Autor: coordenador (passe de escrita). **Sem revisão cruzada ainda (T11 pendente).** Caminhos relativos a `research/decompiled/jadx/sources/`.

## Transporte [ESTÁTICO]
- BLE/GATT via biblioteca de terceiros ofuscada (`o0.a` = gerenciador estilo FastBle; `BleDevice`, `BleException`, callbacks `q0.*`). `com/iscooter/car/blue/Interfa/BleCService.java`.
- Constantes `com/iscooter/car/blue/BleConstants.java`:
  - Serviço `0000FF60-0000-1000-8000-00805F9B34FB`; notificação (leitura) `0000FF61-…`; escrita `0000FF62-…`; CCCD `00002902-…`. Também existe `0000ffe1-…` (`NOTITY_CHARACTER_UUID`), sem uso confirmado.
- `isXcDevice()` (`BleCService.java:239`): o app só opera se o dispositivo conectado expõe o serviço FF60 com a característica FF61.
- `startNotify()` assina FF61; falha → nova tentativa após 10 s. `writeData/writeDatas`: fila `LinkedBlockingQueue`, escrita em FF62; falha → reenvio após 50 ms (**risco de comando duplicado**, REGRA 5/critério "sem duplicados"). MTU ajustável (`setMtu`).
- Heartbeat: `Myhandler` (`BleCService.java:186`) envia `q3.a.t()` (cmd 0x10) periodicamente (1 s) enquanto conectado e sem OTA. `[HIPÓTESE]` o patinete exige heartbeat para manter a sessão.

## Formato do quadro [ESTÁTICO] (`q3/a.java:51-135`, classe ofuscada `q3.a`)
```
1A A1 | CMD | TIPO | LEN | DADOS[LEN] | CRC16 (2 bytes) | 1F F1
```
- Cabeçalho `0x1A 0xA1`, rodapé `0x1F 0xF1`. `TIPO`=2 para comandos de escrita, 1 para consultas (`BleConstants *_PARAM`). `LEN`=tamanho dos DADOS (0 em consultas).
- CRC: CRC-16/MODBUS (init 0xFFFF, poli 0xA001), calculado sobre `CMD,TIPO,LEN,DADOS`; bytes emitidos como `(crc&0xFF)<<8 | (crc>>8)` via `toHexString`. `[HIPÓTESE]` ordem de bytes e preenchimento (`utilss/d.d` completa com zeros à esquerda) a validar em captura real.
- Sem cifra/nonce visível neste caminho [ESTÁTICO]. Autenticação do enlace: **não identificada** nesta passada [DESCONHECIDO].

## Comandos (CMD) [ESTÁTICO — `BleConstants.java`]
HEARTBEAT 0x10 · CONNECT 0x30 · POWERON 0x31 · **LOCK 0x32** · LIGHT 0x33 · GAUGE_BRIGHTNESS 0x34 · ELECTRIC_GEAR 0x35 · SPEED_LIMIT_GEAR 0x36 · ELECTRONIC_BRAKES 0x37 · CRUISE_GEAR 0x38 · START_SPEED 0x39 · SET_SN 0x3A · DRIVE 0x3B · UNIT 0x3C · SINGLE_MILEAGE 0x3E · CURRENT_USER_CAR 0x41 · UNBIND_RESET 0x42 · NAV 0x45/0x46 · QUERY_CAR 0x60 · QUERY_SN 0x61 · QUERY_SPEED_LIMIT 0x62 · QUERY_LIGHT 0x63 · QUERY_PRO 0x66 · **QUERY_LOCK_PASSWORD 0x67** · OTA (0x91–0x93, **proibido**) · Meter1/2 = 0x20/0x21 (telemetria).
Comandos **fora do escopo e a não enviar**: SET_SN, UNBIND_RESET, OTA, SET_*.

## Lock/unlock [ESTÁTICO + HIPÓTESES]
- Construtor `q3.a.l(int modo, String pin)` (`q3/a.java:136-147`): CMD 0x32, TIPO 2, DADOS = `[modo] + [4 dígitos do PIN, cada um = char-'0']`; PIN só é incluído se tiver exatamente 4 caracteres, senão DADOS = `[modo]`.
- Chamadas em `uis/car/frag/DeviceCarControlFrag.java` (~1426, 1437, 2705, 3092-3104): `modo 0` com PIN = desbloquear; `modo 1` abre diálogo de PIN (bloquear); modos 2 e 3 existem (significado `[DESCONHECIDO]`). `[HIPÓTESE]` 0=desbloquear, 1=bloquear.
- O patinete tem estado "bloqueio" (`lockStatuss`) e "tem senha" (`lockHasPwds`) lidos por notificação; o app consulta a senha com QUERY_LOCK_PASSWORD (0x67) (`DeviceCarControlFrag.java:1890-1913`).
- **Há PIN de 4 dígitos** guardado/validado no enlace; o app o mantém em `curPassword`. Tratar como credencial do proprietário: guardar cifrado (Keystore), nunca em log. Não tentar adivinhar nem contornar (REGRA 6).
- O app de origem condiciona a ação à conta: `DisplayUtil.isLogin()` e `hasCurrentUserDeviceCar` (`DeviceCarControlFrag.java:1408`), e envia `CURRENT_USER_CAR` (0x41) ao patinete. `[HIPÓTESE]` a vinculação dono↔patinete vem do servidor e é repassada ao patinete; **se o firmware exigir esse comando, o novo app precisa reproduzi-lo legitimamente** → crítico para a REGRA 3 (T8/T9).

## Notificações (FF61) — parser [ESTÁTICO] (`uis/car/model/BleViewModel.java:247-500`, `convertBleData`)
- Mesmo quadro `1A A1 | CMD | TIPO | LEN | DADOS…`; `byte[2]` = CMD de origem; dados começam em `byte[5]`. Despacho: 0x20 (Meter1, telemetria), 0x21 (Meter2), 0x30 (resposta CONNECT: `byte[5]` → `bleConnectStart`), 0x61 SN, 0x62 limite de velocidade, 0x63 luzes, 0x66 versões/CRC. **Não há ramo para 0x32 (resposta do LOCK)**: o app não depende de um ACK do comando e sim do **estado** em Meter1 → compatível com a REGRA 5 (confirmar por estado real).
- Meter1 (CMD 0x20, ≥17 bytes): `[5]` bits de luzes; `[6]` bits de status; `[7]` código de falha; `[8]` bateria % (0–100); `[9..11]` velocidade/média/restante (composição com bits de `[13]`, detalhe `[HIPÓTESE]`); `[12]` limite de velocidade atual; `[13..15]` flags/BMS.
- Bits de `byte[6]` (LSB=bit0; `utilss/d.b` gera MSB-first e o app inverte): bit0 `lowPower`, bit1 `forceUnbinding`, bit2 não usado, bit3 `brakeStatus`, **bit4 `lockHasPwd`**, **bit5 `lockStatus`**, bit6 `cruisingStatus`, bit7 `chargeStatus` (`beans/Meter1CarStatus1Dto.java:19-27`). `[ESTÁTICO]`
- Semântica de `lockStatus`: `[HIPÓTESE]` 1 = bloqueado (a UI pede PIN quando `lockStatuss==1 && lockHasPwds==1`, `DeviceCarControlFrag.java:1417-1441`); com `lockHasPwd==0` o desbloqueio é enviado sem PIN (`q3.a.l(0,"")`, linha 1426).

## Sessão e posse [ESTÁTICO + HIPÓTESE]
- CONNECT: `q3.a.c(1)` após conectar (`DeviceCarControlFrag.java:2110/2118`); `q3.a.c(0)` antes de desconectar (`ConnectDeviceFrag.java:927`). `[HIPÓTESE]` 1=sessão iniciada, 0=encerrando.
- CURRENT_USER_CAR (`q3.a.g`, 0x41): enviado por `sendHasCurrentUserCar(0|1)` (`DeviceCarControlFrag.java:2121-2130`) **somente se** a preferência `CAR_DISTANCE_CURRENT_SWITCH` está ligada; senão envia sempre 0. O valor 1 vem da consulta ao servidor (`queryDevice` → `isBind=="1"` e `userInfo.uid` == usuário logado, linhas ~1644–1715). `[HIPÓTESE]` 0x41 serve ao recurso de proximidade (“distância do carro”), **não** é pré-requisito do lock manual. Precisa de captura (Sprint 2): lock manual funciona com 0x41=0?
- Consequência para a REGRA 3: pelo menos o *caminho de UI* do app depende de login+servidor (`isLogin && hasCurrentUserDeviceCar`); no nível BLE ainda **não há prova** de token do servidor no lock. Se a captura confirmar, o app novo opera só com BLE+PIN, sem conta. Nunca falsificar posse (REGRA 6).

## Pendências (atualizado)
1. Critério de scan/identificação (`BleCService.connectBySaveMac`, `ConnectDeviceFrag`), nome/prefixo do anúncio.
2. T9: endpoints, login, expiração; T2 manifest via Apktool.
3. Bytes 9–15 do Meter1 (velocidade, BMS) em captura.
4. Captura HCI: lock manual com 0x41=0; resposta/estado após 0x32; heartbeat obrigatório?
