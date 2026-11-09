# S32 — Cruise e Partida do zero: mapeamento (capturas existentes + jadx, sem TX nosso)

Investigação somente leitura. Fontes: `research/decompiled/jadx/sources/` (original `com.iscooter.car`), captura privada `research/captures/s2d/ix7tap-roteiro2.txt` (linhas citadas abaixo; patinete desbloqueado e parado, protocolo 1.03), e o app novo (`app/`). Nada foi enviado ao patinete. CRC-16/Modbus (byte baixo primeiro) dos quatro quadros recalculado e conferido.

## 1. Resumo
- Cruise = CMD **0x38** (`APP_CRUISE_GEAR`), TIPO 02, LEN 2. Liga `05 03`, desliga `00 03`. `[CONFIRMADO]` 4 transições (2 liga, 2 desliga) em `s2d`.
- Partida do zero = CMD **0x39** (`APP_START_SPEED`), TIPO 02, LEN 2. Dados `01 00` e `00 00`. **A semântica é invertida** em relação ao nome do bit: `00 00` = partida do zero LIGADA, `01 00` = DESLIGADA `[CONFIRMADO]` (ver §4). A tabela "liga / desliga = `01 00` / `00 00`" de `S2-captura-resultados.md:40` está invertida e deve ser corrigida.
- Estado volta no Meter1 (CMD 0x20) **dado[11]** (byte[16] do quadro, `BleViewModel.java:588-611`): bit6 (0x40) = cruise; bit7 (0x80) = zeroStart. 8/8 transições seguem o comando no primeiro Meter1 (<= 0,66 s).
- Estado do app novo (atualizado): `TelemetryDecoder.meter1` já lê `cruiseOn = (d[11] and 0x40) != 0` e `zeroStartOn = (d[11] and 0x80) == 0`; existem `TxCommand.Cruise/ZeroStart`, `ScooterTransport.setCruise/setZeroStart` e a Home os usa com o BLE real (ADR 0010). Tudo `[ESTÁTICO]` no app novo até teste real; nada foi enviado ao patinete por nós.

## 2. Evidência estática (original)
- `BleConstants.java:12-13`: `APP_CRUISE_GEAR = 56 (0x38)`, `PARAM = 2`. `BleConstants.java:58-59`: `APP_START_SPEED = 57 (0x39)`, `PARAM = 2`.
- Construtores de quadro: `q3/a.java:197` `v(i6,i7)` = `d(APP_CRUISE_GEAR, 2, {i6,i7}, TRUE)`; `q3/a.java:19-20` `C(i6,i7)` = `d(APP_START_SPEED, 2, {i6,i7}, TRUE)`. Mesmo montador `d` dos demais comandos (TIPO 02).
- Único ponto de uso: `uis/car/act/DeviceCarSettingAty.java`.
  - Cruise, `listeners$lambda$27` (l. 538-594): estado do switch ligado envia `v(5,3)`; desligado envia `v(0,3)`. Na primeira vez mostra diálogo de aviso (`dialog_cruise_ctrl`, flag `HAS_SHOW_CRUISE_CRU`) e só envia `v(5,3)` após "confirmar" (l. 603-619).
  - Partida do zero, `listeners$lambda$28` (l. 621-650): exige o aviso `showSpeedLimitDiag()` (flag `HAS_SHOW_SPEED_LIMT_LIM`, texto `speed_limt_tips_slt`); switch ligado envia `C(0,0)`, desligado envia `C(1,0)`.
  - O `OnCheckedChangeListener` (l. 652-662) atualiza `cruiseClickc`/`zeroStartClickc` antes do clique; por isso "ligado" = valor já refletindo o novo estado do switch.
  - Leitura de estado: `listeners$lambda$24` (l. 526-531) e l. 1946-1953: `switchZeroStart.checked = (zeroStart == 0)`, `switchCruise.checked = (cruise == 1)`.
  - Timer de falha de 4 s (`FAIL_MSGS`, `sendEmptyMessageDelayed(..., 4000)`) após cada clique: o original também trata a falha como ausência de mudança no Meter1, não pelo ACK.
- Visibilidade dos switches depende de lista de funções vinda da nuvem (`DeviceFuncDTO.getFuncValue()` == `cruise_control` / `zero_start`, `DeviceCarSettingAty.java:835-945`; `Constant.java:56,95`). Não afeta o protocolo local.
- Decodificação de estado: `BleViewModel.java:588-611`: `string11 = reverse(binário(bytes[16]))`; `substring(7,8)` = zeroStart, `substring(6,7)` = cruise (reverso de MSB-first: char7 = bit7, char6 = bit6). `Meter1CarStatus3Dto.java:12,17`. Coerente com `S29-meter2-decodificacao.md:72`.
- Pré-condições no cliente: **nenhuma** checagem de movimento, bloqueio ou modo nesses dois handlers `[ESTÁTICO]` (contraste: tração/limite/modo recusam em movimento, ver S31). O que o firmware faz com patinete em movimento/bloqueado `[DESCONHECIDO]`.
- Texto de ajuda do cruise (`strings.xml:526`, pt `values-pt/strings.xml:134`): segurar o acelerador ~5 s entra em cruise com dois bipes; freio ou acelerar de novo cancela; não usar em via movimentada. Logo, **0x38 habilita o recurso, não "engata" a velocidade constante**; o engate é físico, pelo acelerador `[ESTÁTICO]` (texto de ajuda; comportamento real do bit6 durante cruise engatado `[DESCONHECIDO]`).

## 2b. Confirmação adicional (captura do original, 14:49)
Fonte: `research/captures/s32-comandos/s32-original-tap.log` (git-ignorado). TX `38 05 03` às 14:49:27.233 -> Meter1 byte[16] = 0x40 às 14:49:27.936 (cruise habilitado); TX `38 00 03` às 14:49:30.444 -> volta a 0x00; TX `39 01 00` às 14:49:32.119 -> byte[16] = 0x80 às 14:49:32.826 (partida do zero DESABILITADA); TX `39 00 00` às 14:49:34.025 -> volta a 0x00. `[CONFIRMADO]` (original): `01 00` = partida do zero desligada = bit7 sobe; `00 00` = ligada; bit6 = cruise habilitado. Meter1 reais usados nos testes do decoder: `1aa1200312000000600000003c0000034023232302030810e01ff1` (cruise ligado), `...3c0000038023...` (partida desabilitada), `...3c0000030023...` (neutro). O teste no app novo (TX nosso) continua pendente.

## 3. Quadros reais (captura `s2d/ix7tap-roteiro2.txt`)
Formato: `1aa1 · CMD · 02 · LEN · DADOS · CRC lohi · 1ff1`. Linhas do `.txt`; horário 03-nov.

| Função / ação | Quadro TX (hex) | Linhas (txt) | ACK (tipo 05) | Meter1 dado[11] depois |
|---|---|---|---|---|
| Cruise LIGA | `1aa1380202050366ec1ff1` | 5014, (5046) | `1aa1380500be921ff1` em 70 ms / 47 ms | 0x00 -> 0x40 em 0,36 s / 0,36 s |
| Cruise DESLIGA | `1aa1380202000365bc1ff1` | 5031, (5056) | idem em 81 ms / 15 ms | 0x40 -> 0x00 em 0,38 s / 0,17 s |
| Partida do zero DESLIGA | `1aa1390202010019ed1ff1` | 5201, (5232) | `1aa13905007f521ff1` em 95 ms / 35 ms | 0x00 -> 0x80 em 0,40 s / 0,34 s |
| Partida do zero LIGA | `1aa13902020000187d1ff1` | 5221, (5243) | idem em 35 ms / 29 ms | 0x80 -> 0x00 em 0,66 s / 0,62 s |

Notas:
- Meter1 de exemplo com cruise ligado: `1aa12003120000006400000032000000 40 2323230203085237 1ff1` (dado[11]=0x40, demais campos iguais ao estado desligado, exceto o rodapé de CRC/versão). Com partida do zero desligada: dado[11]=0x80.
- Em toda a janela (14:17:31 a 14:18:12): dado[1] bit5 = 0 (desbloqueado), dado[4..6] = 0 (rodas paradas), modo e limite inalterados.
- Os ACKs têm dado de LEN 0 e CRC que não confere com o algoritmo conhecido (já registrado em S2); ACK nunca prova execução (regra 5).
- Estado final informado pelo proprietário (`S2-captura-resultados.md:44`): cruise desligado, partida do zero **ligada** = dado[11] bit7 = 0 após `00 00` (linha 5243 + Meter1 seguinte).
- Sem qualquer TX 0x38/0x39 em `research/captures/s32-comandos/` (logs do app novo, 8 tipos de TX, nenhum cruise/partida) nem nas demais capturas (grep `1aa138/39`): só `s2d/roteiro2`.

## 4. Mapa de estado (Meter1 dado[11])
| Bit | Significado | Valor observado | Classificação |
|---|---|---|---|
| bit6 (0x40) | cruise habilitado | 1 após `05 03`, 0 após `00 03` (4/4) | `[CONFIRMADO]` |
| bit7 (0x80) | partida do zero **desabilitada** | 1 após `01 00`, 0 após `00 00` (4/4); UI original usa `zeroStart == 0` como "ligado" | `[CONFIRMADO]` |
| bit5 | smartLock (nome do DTO) | nunca visto em 1 | `[ESTÁTICO]`, fora de escopo |
| bits 3-4 | presença de temp. dos motores | `[ESTÁTICO]` | fora de escopo |
| bits 0-3 | "lightMode" | `[ESTÁTICO]` | fora de escopo |

Atenção: o nome do campo do original (`zeroStart`) vale 1 quando a partida do zero está DESLIGADA. O app novo deve mapear `zeroStartOn = (dado[11] and 0x80) == 0`.

## 5. Valores dos parâmetros
- Cruise: byte0 = 5 liga, 0 desliga `[CONFIRMADO]`. Byte1 = 3 em ambos `[CONFIRMADO]` que o original sempre envia 3; significado de cada byte `[DESCONHECIDO]`. `[HIPÓTESE]` byte0 = 5 ligado ao "5 segundos" do texto de ajuda (tempo de espera do acelerador) — não validado. Outros valores (ex.: 1..4, 6+) `[DESCONHECIDO]` e não devem ser enviados.
- Partida do zero: byte0 = 0 liga, 1 desliga; byte1 = 0 sempre `[CONFIRMADO]` (nos dois sentidos). Outros valores `[DESCONHECIDO]` (o `C(i6,i7)` aceita genérico, mas a UI só envia esses dois).

## 6. Sequência / handshake
- `[CONFIRMADO]` Nenhum handshake: um único TX TIPO 02 → ACK TIPO 05 em 15-95 ms → Meter1 atualizado em <= 0,66 s. Sem consulta prévia nem posterior (nenhum 0x62/0x63-equivalente para cruise/partida; `[DESCONHECIDO]` se existe).
- O original só gera um clique por vez (timer de 4 s); sem repetição automática no código lido.
- Heartbeat 0x10 (~1 s) continua normalmente entre os TX.

## 7. Riscos
- Partida do zero ligada com patinete parado: aceleração sem empurrar. É a configuração de fábrica do dono (estado final = ligada); desligá-la exige empurrar para dar partida (ver efeito real `[DESCONHECIDO]`, não testado em movimento).
- Cruise: 0x38 só habilita; o engate acontece ao segurar o acelerador, então habilitar não muda a condução sozinho, mas habilita um modo que mantém velocidade sem mão no acelerador. Risco de segurança maior que luz/unidade. Ordem de risco proposta: unidade < modo < limite < cruise < partida do zero (desabilitar) < tração, a confirmar com o dono.
- Semântica invertida do bit7/0x39: erro de mapeamento faria o app mostrar o oposto e enviar o contrário do desejado. Obrigatório teste unitário que fixe os 4 quadros e o mapeamento de bit.
- `[DESCONHECIDO]` comportamento do firmware com patinete bloqueado, em movimento ou em modos específicos (o original não bloqueia no cliente); nunca testar em movimento (regra 6).
- Meter1 só confirma o estado "configurado"; se o patinete recusar silenciosamente, não haverá erro explícito (sem TIPO 0x89 visto para 0x38/0x39) — usar o timeout como "não confirmado".

## 8. App novo hoje (estado ANTES desta implementação; ver nota em §1 e ADR 0010)
- `Protocol.kt:68-69` já tem `Telemetry.cruiseOn`/`zeroStartOn` (nullable) e `ScooterCommand.SetCruise/SetZeroStart` (`Protocol.kt:39-45`); `TelemetryDecoder.overlay` já os propaga (l. 106-107); `HomeReducer.kt:324-325` já define "confirmado" por `t.cruiseOn == on` / `t.zeroStartOn == on`; `HomeUiMapper.kt:62-66` e `FakeScooterTransport.kt:240-241` os usam (Fake).
- Falta: (a) `TelemetryDecoder.meter1` não lê `d[11]`; (b) não existem `TxCommand.Cruise`/`ZeroStart` nem constantes `CMD_CRUISE=0x38`/`CMD_ZERO_START=0x39` em `TxFrame.kt`; (c) `BleScooterTransport` não implementa `setCruise`/`setZeroStart` nem flags `*Available` (só `unit/driveMode/speedLimit/drive`, l. 366-404); (d) `isS31Rejection` (TxFrame.kt) não inclui 0x38/0x39.

## 9. Recomendação de implementação (sem código nesta fase)
1. Decodificador (somente leitura, risco nulo, pode ir antes do TX): em `meter1`, se `d.size > 11`: `cruiseOn = (d[11] and 0x40) != 0`; `zeroStartOn = (d[11] and 0x80) == 0`. Testes com os Meter1 reais acima (0x00, 0x40, 0x80) e frame curto (-> null). Já mostra o estado real do painel sem enviar nada.
2. `TxCommand` fechado: `Cruise(on)` = CMD 0x38 TIPO 02 payload `[5,3]`/`[0,3]`; `ZeroStart(on)` = CMD 0x39 TIPO 02 payload `[0,0]` se on / `[1,0]` se off. Nenhum parâmetro livre. `TxFrameTest` com os 4 hex da §3.
3. `BleScooterTransport`: `setCruise`/`setZeroStart` via `guardedSetting(TxCommand.Cruise(on), "CRUISE") { it.cruiseOn == on }` e `guardedSetting(TxCommand.ZeroStart(on), "ZEROSTART") { it.zeroStartOn == on }`. Reusa as travas existentes (TX ligado, BLE real, Meter1 fresco, uma tentativa, sem repetição, ocupado, confirmação só pelo Meter1 após o envio, `unlockConfirmMs`). `beforeWrite`: recusar se a telemetria atual não traz o campo (null) — não enviar "às cegas"; recusar se `wheelMoving != false`; decidir com o dono se exige desbloqueado (o original não exige; S31 trata modo/unidade como exigindo).
4. UI: exibir aviso equivalente ao do original antes da primeira vez (regras 5/6), mostrar "pendente" até o Meter1 confirmar, "não confirmado" no timeout. Nunca "concluído" pelo ACK.
5. Ordem sugerida: cruise (habilitar/desabilitar é reversível e não altera condução sozinho) antes da partida do zero; ambos só após aprovação explícita do dono e do plano de teste (regra 6, "pedir 'pode' no chat" antes do toque).

## 10. O que FALTA validar em teste real (patinete parado, sob autorização)
- [ ] Decodificação passiva: com o app novo apenas escutando, mudar cruise/partida pelo app original ou pelo painel e conferir dado[11] bit6/bit7 (não custa TX).
- [ ] Meter1 dado[11] bit6 durante cruise realmente engatado (segurar acelerador 5 s) — se o bit indica "habilitado" ou "ativo" `[DESCONHECIDO]`. Só com o dono, nunca testado por nós em movimento.
- [ ] Quadros do app novo (`05 03`, `00 03`, `00 00`, `01 00`) provocam o mesmo Meter1 que o original (1 ciclo cada, parado).
- [ ] Sentido físico da partida do zero: `00 00` realmente liga (aceleração sem empurrar) e `01 00` exige empurrão — confirmar pelo painel, não só pelo bit.
- [ ] Efeito de enviar com patinete bloqueado (firmware) — `[DESCONHECIDO]`; só se o dono quiser permitir.
- [ ] Significado do byte1 `03` no cruise e do byte0 `05` (tempo?) — `[DESCONHECIDO]`; só observar, nunca variar.
- [ ] Se o patinete rejeita (TIPO 0x89) em algum caso para 0x38/0x39 — não visto.
- [ ] Se o estado persiste após religar o patinete (o original não o regrava na conexão no código lido).
