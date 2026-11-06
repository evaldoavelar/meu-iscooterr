# S29 — Decodificação de Meter2 (CMD 0x21) e campos novos de Meter1 (CMD 0x20)

Pesquisa só de leitura (estática + capturas já existentes). Nenhum TX, nenhum adb, nenhum toque no patinete.
Interoperabilidade apenas: campos, offsets e escalas descritos em prosa/tabela; nenhum trecho do decompilado foi copiado.

## 0. Convenções e fontes

- Quadro: `1A A1 · CMD · TIPO · LEN · 18 B de dados · CRC(2) · 1F F1` (27 B). O app original trabalha com o quadro inteiro (`bytes[]`); os dados começam em `bytes[5]`. Aqui `dado[i] = bytes[5+i]`.
- Fonte primária (decompilado, `research/decompiled/jadx/sources/com/iscooter/car/`): `uis/car/model/BleViewModel.java`, método `convertBleData`. Despacho por `bytes[2]`: 0x21 = Meter2 (linhas 336-370), 0x20 = Meter1 (linhas 377-660). DTOs em `beans/Meter2Dto.java`, `Meter1CarStatus1Dto`, `Meter1CarStatus2Dto`, `Meter1CarStatus3Dto`, `Meter1CarLight1Dto`, `Meter1CarSpeedDto`, `SpeedGearsDto`. Consumo na UI: `uis/car/act/DeviceCarDataAct.java` (tela "dados do veículo", linhas ~96-185 rótulos e ~236-292 valores), `CarRecordActivity.java`, `uis/my/act/ScanSerialAty.java` (odômetro).
- Auxiliares (`utilss/d.java`): leitura de 16 bits = **inteiro com sinal, little-endian** (byte baixo primeiro); "bit string" = byte em 8 bits, e o original **inverte** a string, então o índice 0 da string é o **bit 0** (LSB).
- Capturas usadas (todas privadas, ignoradas pelo git): `s2d`, `s13pin`, `s4-3`, `s4-3b`, `s19-speed`: 11 349 quadros Meter1/Meter2 (56 payloads Meter2 distintos, 63 Meter1 distintos). Script de apoio em scratchpad, não versionado.
- Cuidado: o CRC das capturas aparece como `crc=bad` no logger IX7TAP (variante de CRC), irrelevante aqui; o decoder atual já ignora isso.

## 1. Tabela Meter2 (CMD 0x21, tipo 03, LEN 18)

Campos da `Meter2Dto` na ordem em que são lidos. "Rótulo UI" = como o app original chama/mostra.

| dado[] (bytes[]) | Campo (DTO) | Tipo / escala / unidade | Rótulo UI original | Evidência estática | Evidência nas capturas | Classe |
|---|---|---|---|---|---|---|
| 0-1 (5-6) | `dischargePower` | s16 LE × 0,1; **W** (unidade inferida, ver nota) | Id 11 existe na lista de atualização mas não tem linha na tela de dados; `CarRecordActivity` mostra o número cru (potência de descarga) | BleViewModel:336 | s19: 0x0368 = 87,2 com V=54,5 e I=1,60 → V·I = 87,2. Em 56 payloads distintos, \|P − V·I\| ≤ 0,08 | `[ESTÁTICO]` escala; `[HIPÓTESE forte]` unidade W (aritmética fecha, sem medição externa) |
| 2-3 (7-8) | `voltage` | s16 LE × 0,01; **V** | "Voltagem" / "Real-time voltage", unidade "V" (DeviceCarDataAct:169, valor em 292) | BleViewModel:337 | 54,0 a 55,7 V em todas as capturas (ex.: 0x1540 = 54,40; 0x154a = 54,50). Cai de 54,5 para 54,3 com corrente, sobe sem carga | `[ESTÁTICO]` escala; plausibilidade `[HIPÓTESE]` (pack ~13S/14S, ver §2) |
| 4-5 (9-10) | `current` | s16 LE × 0,01; **A**, **com sinal** (regen deve dar negativo; sem exemplo) | "Corrente" / "Real-time current", "A" (DeviceCarDataAct:165, valor em 291) | BleViewModel:338 | Parado = 0,00 em todos os quadros. s19 (acelerador, roda no ar): 0xa0=1,60; 0x46=0,70; 0x3c=0,60; 0x32=0,50 A | `[ESTÁTICO]` escala; `[HIPÓTESE forte]` unidade |
| 6-7 (11-12) | `singleMileage` | s16 LE × 0,1; **km** (viagem; vira milhas ×0,6213 se a preferência do app for mph) | "Distância da viagem" / "Current mileage" (DeviceCarDataAct:96) | BleViewModel:339; conversão em `utilss/n.java` | 0,0 em todos os quadros (nenhuma viagem rodada nas capturas) | `[ESTÁTICO]`; valor real `[DESCONHECIDO]` |
| 8-9 (13-14) | `accrualMileage` | s16 LE × 1; **km** (odômetro acumulado, inteiro) | "Distância total" / "Total mileage" = **máximo** entre este campo e `singleMileage` (DeviceCarDataAct:236-250); `ScanSerialAty` também usa como odômetro | BleViewModel:340 | 23 (0x17) e 24 (0x18). Foto do painel: ODO ≈ 23 km (S17); virou 24 depois do uso de 11-05 | `[ESTÁTICO]` + coerente com painel; `[HIPÓTESE forte]` (leitura da foto "a confirmar") |
| 10 (15) | `milesRemaining` | u8 × 1; autonomia restante (km) | Nenhuma tela encontrada que leia o campo | BleViewModel:341 | Sempre 0 | `[ESTÁTICO]` campo; valor útil `[DESCONHECIDO]` |
| 11 (16) | `agvCurrent` | u8 × 0,1; A (corrente média) | Nenhuma tela que leia | BleViewModel:342 | Sempre 0 | `[ESTÁTICO]`; `[DESCONHECIDO]` |
| 12 (17) | `gaugeBrightness` | u8 | Nenhuma leitura na UI | BleViewModel:343 | Sempre 0 | `[ESTÁTICO]`; `[DESCONHECIDO]` |
| 13 (18) | `batteryTemperature` | u8 sem offset, °C | **Não lido por nenhuma tela** (busca por `getBatteryTemperature` só acha o DTO) | BleViewModel:344 | Sempre 0 → ou sensor ausente ou campo não usado neste patinete | `[ESTÁTICO]`; `[DESCONHECIDO]` |
| 14 (19) | `motorTemperature` | u8 sem offset, **°C** (sem sinal; sem offset nem escala) | "Temp. do motor" / "Motor temperature", "℃" (DeviceCarDataAct:115; valor sempre em ramo, linhas ~258-284) | BleViewModel:345 | 21-26 °C em dias normais; 32/33/36 °C logo após uso (s4-3b 12:42-13:01, desce 36→33 em ~19 min) | `[ESTÁTICO]` escala; plausibilidade `[HIPÓTESE forte]` |
| 15 (20) | `speedGearsMode` (marchas habilitadas) | máscara de bits: b0 Walking, b1 Eco, b2 Balanced, b3 Sports, b4 Sports2, b5 Sports3. O original só atualiza se algum de b0..b3 estiver ligado | usado no seletor de modos (não na tela de dados) | BleViewModel:346-367; `SpeedGearsDto` | Sempre 0x0F → Walking, Eco, Balanced e Sports habilitados; Sports2/3 desabilitados | `[ESTÁTICO]` |
| 16 (21) | `motorTemperature2` | u8, °C, **só mostrado se versão de protocolo ≥ "1.05" E bits de motor 1 e 2 do Meter1 dado[11] (b4/b3) = 1** (DeviceCarDataAct:262-284) | "Temp. do motor 2" | BleViewModel:368 | 3 (0x03) na maioria; 0xF1 (241) em sessões antigas (s2d, s13pin, s4-3b 12:42). Protocolo do patinete = 1.03 (<1.05) → o original **não mostra** este campo | `[ESTÁTICO]`; valor `[DESCONHECIDO]`, tratar como inválido |
| 17 (22) | — | 0x08 constante; não lido pelo original | — | — | Sempre 0x08 | `[DESCONHECIDO]` |

Notas:

1. A hipótese de S17 ("dado[8]=0x17 e dado[14] podem ser temperaturas") fica **corrigida**: dado[8..9] é o odômetro (23 km = painel); dado[14] é a temperatura do motor.
2. Unidade do campo de potência: o original não rotula nem converte. W vem só da aritmética V·I. Se o app novo exibir, marcar como estimada ou omitir.
3. O original não calcula nada além de arredondar a 2 casas; não há offset de temperatura nem inversão de sinal na corrente.
4. Odômetro: o app original mostra `max(accrual, single)`. No app novo, usar `accrual` como `totalKm`; `single` só como viagem.

## 2. Valores calculados (quadros reais)

Payload = os 18 B após `1aa12103 12`.

| # | Quadro (completo) | Origem | P (W) | V (V) | I (A) | Viagem (km) | Odo (km) | T motor (°C) | marchas | Avaliação |
|---|---|---|---|---|---|---|---|---|---|---|
| A | `1aa12103120000401500000000180000000000210f0308e5151ff1` | s4-3b 13:01 (440 repetições) | 0,0 | **54,40** | 0,00 | 0,0 | **24** | **33** | 0x0F | Parado, sem carga. 54,4 V é plausível para pack 13S (≈4,18 V/célula) em 100 %. T motor 33 °C logo após uso. Coerente |
| B | `1aa121031200004a1500000000180000000000240f0308ec4f1ff1` | s4-3b 12:43 | 0,0 | 54,50 | 0,00 | 0,0 | 24 | **36** | 0x0F | Mais quente 20 min antes de A (36 → 33 °C): esfriando. Coerente |
| C | `1aa121031268034a15a0000000180000000000190f030813f11ff1` | s19 15:01:14 (acelerador, roda no cavalete) | **87,2** | 54,50 | **1,60** | 0,0 | 24 | 25 | 0x0F | V·I = 87,2 W exato. Corrente de alguns décimos a 1,6 A é plausível com roda solta |
| D | `1aa12103120000a41500000000170000000000160ff10857811ff1` | s13pin 11-04 / s2d | 0,0 | 55,40 | 0,00 | 0,0 | 23 | 22 | 0x0F | Temp. ambiente (22 °C), odo 23 = painel. `dado[16]=0xF1` fora do padrão (campo inválido) |

Faixas observadas nas 56 combinações Meter2: V 54,0-55,7; I 0-1,6 A; P 0-87,2 W; T motor 21-36 °C; odo 23-24 km; batteryTemperature, avgCurrent, milesRemaining, brilho e viagem sempre 0.

Plausibilidade da tensão: 55,7 V ultrapassa 54,6 V (13S × 4,2 V). Duas leituras possíveis: pack 14S a ~3,98 V/célula (nominal 51,8 V, cheio 58,8 V) ou 13S em carga acima de 4,2 V/célula (improvável). O painel pedia "bateria vermelha com raio" na foto de S17. Com Meter1 `dado[3]` = 100 % em todas as capturas, **não dá para ligar tensão a percentual** só com estas capturas. `[HIPÓTESE]`: não exibir "13S/14S" nem fazer curva de SoC por tensão.

## 3. Campos novos de Meter1 (CMD 0x20) ainda não decodificados pelo app

Meter1 = BleViewModel linhas 377-660. Já decodificados no app: `dado[1]` b5 (bloqueio), `dado[3]` (bateria), `dado[7]` (limite), `dado[4..6]` (movimento).

| dado[] (bytes[]) | Campo (DTO) | Escala / unidade / bits | Evidência estática | Evidência nas capturas | Classe |
|---|---|---|---|---|---|
| 0 (5) | `Meter1CarLight1Dto`: b0 mood, b1 right, b2 left, b3 rear, b4 large | bits (1 = ligado) | BleViewModel:377-396 | Valor 0x18 (= b3 + b4: luz traseira + farol grande) aparece 14 quadros em s2d durante o teste de farol (TX 0x33 ligou/desligou) e 24 em s13pin | `[ESTÁTICO]`; "farol = b4" `[HIPÓTESE forte]` (correlação com TX 0x33; não validada contra painel) |
| 1 (6) | `Meter1CarStatus1Dto`: b0 lowPower, b1 forceUnbinding, **b3 brake**, b4 lockHasPwd, b5 lockStatus, b6 cruising, b7 charge. b2 não lido | bits | BleViewModel:398-424 | Valores vistos: 0x00, 0x08 (b3, s19: 6 quadros), 0x20 (b5), 0x30 (b5+b4). 0x20/0x30 = bloqueado já `[CONFIRMADO]` (S2/S21). b3=0x08 só em s19, não validado | b3/b6/b7 `[ESTÁTICO]` (freio e carga precisam de medição); b4/b5 `[CONFIRMADO]` |
| 2 (7) | código de falha | u8; 0 = sem falha (só publica se ≠ 0) | BleViewModel:425-428 | Sempre 0 | `[ESTÁTICO]`; tabela de códigos `[DESCONHECIDO]` |
| 3 (8) | bateria % | u8 saturado em 0..100 | BleViewModel:430-436 | 100 em todas | `[CONFIRMADO]` (já no app) |
| 4 (9) + dado[8] b1:b0 | velocidade atual | 10 bits: (dado[8] & 3) << 8 \| dado[4], × 0,1 → **km/h** (ou o que o painel mostrar; mph não converte no DTO) | BleViewModel:443-452 | Máx. s19: 0xc6 = 19,8 km/h (roda no ar com acelerador); na mão 0x25 = 3,7 ↔ "3 km/h" no painel original. dado[8] sempre 0 (ninguém passou de 25,5 km/h) | `[ESTÁTICO]` escala 0,1; `[HIPÓTESE forte]` (S19, comparado com print) |
| 5 (10) + dado[8] b3:b2 | velocidade média | (dado[8]>>2 & 3) << 8 \| dado[5], × 0,1 | BleViewModel:453-465 | Sempre 0 nas capturas | `[ESTÁTICO]`; semântica "média desde quando?" `[DESCONHECIDO]` |
| 6 (11) + dado[8] b5:b4 | "remainingCharging" (tempo restante de carga) | ((dado[8]>>4)&3) << 8 \| dado[6], s16, **sem escala** (minutos, inferido do nome) | BleViewModel:466-474 | Sempre 0 (carregador ligado ou não, 0) | `[ESTÁTICO]`; unidade `[DESCONHECIDO]` |
| 7 (12) | limite de velocidade atual | u8 km/h | BleViewModel:440 | Valores 39, 40, 46, 49, 50, 51, 60 (alterado pelo dono em testes) | `[CONFIRMADO]` (já no app) |
| 9 (14) | BMS (string de 8 bits) | bits sem decodificação no original | BleViewModel:493-499 | Sempre 0 | `[DESCONHECIDO]` |
| 10 (15) | Status2: b0..b2 = modo de velocidade (000→0, 100→1, 010→2, 110→3, 001→4, 101→5; 011 e 111 → 0), **b3 = unidade (0 = km/h, 1 = mph)**, b4 = "singleModel", b5..b7 = "electric" (000→0, 100→1, 110→3; 010 não tem caso explícito) | bits | BleViewModel:494-587 | Valores 0, 1, 2, 3 (s2d, trocas de modo pelo teste S2), 8 (b3 = mph? só s2d, 18 quadros), 16 (b4, 28 quadros). Mapeamento do modo 1..3 é consistente com S2; b3 como unidade precisa de teste | modo `[ESTÁTICO]` + s2d; unidade/b4/b5..7 `[HIPÓTESE]` |
| 11 (16) | Status3: b7 zeroStart, b6 cruise, b5 smartLock, b4 motor1-temp-presente, b3 motor2-temp-presente, b0..b3 "lightMode" (string decimal) | bits | BleViewModel:588-611 | Valores 0x00, 0x40 (cruise, 12 quadros), 0x80 (zeroStart, 16 quadros) em s2d; cruise e zero-start foram testados em S2 | `[ESTÁTICO]`; b6/b7 `[HIPÓTESE forte]` |
| 12-14 (17-19) | versões de protocolo, painel, controlador | 3 bits altos . 5 bits baixos (ex.: 0x23 = "1.03") | BleViewModel:612-657 | 0x23 em todas → 1.03 | `[ESTÁTICO]`; texto exato (formatação do "03") `[DESCONHECIDO]` |
| 15-17 (20-22) | país, fornecedor do painel, fornecedor do controlador | u8 | BleViewModel:655-660 | 2, 3, 8 | `[ESTÁTICO]` (rótulos de fornecedor `[DESCONHECIDO]`) |

Riscos para o decoder atual (Meter1):

1. `wheelMoving` considera `dado[4..6]` ≠ 0. Mas `dado[5]` (velocidade média) e `dado[6]` (tempo de carga) **não são velocidade**: se algum ficar ≠ 0 com o patinete parado (carregando ou logo após uma corrida), o app acusa movimento falso. É falha segura (bloqueia o desbloqueio automático), mas pode confundir. Velocidade real é `dado[4]` (+ 2 bits de `dado[8]`). `[HIPÓTESE]`, só acontece se média/carga ≠ 0 na prática; nenhuma captura exibe isso.
2. Bit `dado[1] b3` parece **freio** e não deve ser confundido com bloqueio (`b5`).

## 4. O que NÃO dá para fechar só com análise estática (e medição real, sem comandos novos)

Tudo abaixo é só escuta: CONNECT 0x30 + heartbeat 0x10 como já fazemos, sem LOCK/unlock, sem escrever outro comando.

| Pendência | Medição que fecha | Cuidado |
|---|---|---|
| Unidade da potência (W) | Anotar corrente/tensão/potência da tela de dados do **app original** (ou do painel) no mesmo instante de um quadro 0x21 capturado | Dono liga o acelerador com a **roda no cavalete**, como em S19 |
| Tensão e % de bateria; 13S vs 14S | Capturar durante um ciclo de carga/descarga e anotar a tela do original (tensão e %) em 4-5 pontos | Longo (horas); só escuta |
| Corrente negativa (regen) | Ver sinal numa freada com roda no cavalete (freio elétrico) | Só com roda no ar |
| Odômetro e viagem | Foto do painel/tela "Distância total" e "Distância da viagem" no mesmo minuto de um quadro 0x21; depois de uma volta, comparar o incremento | Resolução da viagem (0,1) e do total (1 km) |
| Temperatura do motor | Comparar tela do original com `dado[14]` em 2-3 horários (frio/após uso) | Sem offset visível; sensor externo opcional |
| `batteryTemperature`, `milesRemaining`, `agvCurrent`, `gaugeBrightness`, `dado[17]` | Provavelmente inativos neste modelo; fechar vendo se alguma vez saem de 0 em carga/viagem longa | Não exibir até variar |
| `dado[16]` do Meter2 (3 vs 0xF1) | Registrar timestamp de quando muda (s2d/s13pin/s4-3b 12:42: 0xF1; 13:01 em diante: 3) e se correlaciona com versão do firmware/estado | Não exibir motor 2 |
| Velocidade: bits altos (acima de 25,5 km/h), média, tempo de carga | Só com o patinete em movimento real, e **o app novo não conduz isso**. Alternativa sem risco: carregar e observar `dado[6]`; observar `dado[5]` depois de uma corrida pelo original | Nenhum teste em movimento pelo app novo |
| Bits `dado[0]`, `dado[1] b3`, Meter1 Status2/Status3 | Comparar com o painel enquanto o dono muda luz/modo/cruise/freio **pelo app original ou botões físicos** (nós só escutamos) | Mapeia bit ↔ ícone do painel |
| Tabela de códigos de falha | Só ocorre se houver falha; deixar `[DESCONHECIDO]` | Não provocar falha |

## 5. Proposta de testes unitários do decoder

Valores esperados vêm da tabela da §1 (BleViewModel) e dos quadros capturados, calculados à mão (não pela função em teste). Parser de quadro: usar `RxFrameParser` existente com o hex completo.

Meter2 (CMD 0x21, sobre `TelemetryDecoder.decode`; novos campos em `Telemetry` ou `Meter2Telemetry`):

| Teste | Entrada (hex completo) | Esperado (independente) |
|---|---|---|
| Quadro A (parado, 33 °C) | `1aa12103120000401500000000180000000000210f0308e5151ff1` | V = 54,40; I = 0,00; P = 0,0; viagem = 0,0 km; odo = 24 km; motor = 33 °C |
| Quadro B (36 °C) | `1aa121031200004a1500000000180000000000240f0308ec4f1ff1` | V = 54,50; odo 24; motor 36 |
| Quadro C (acelerador) | `1aa121031268034a15a0000000180000000000190f030813f11ff1` | P = 87,2; V = 54,50; I = 1,60; odo 24; motor 25. Invariante: \|P − V·I\| ≤ 0,1 |
| Quadro D (odo 23) | `1aa12103120000a41500000000170000000000160ff10857811ff1` | V = 55,40; odo 23; motor 22; `motor2` ignorado (não vira 241 °C) |
| Sinal e escala | payloads sintéticos (calculados à mão): corrente `0xff9c` (s16 = −100) → −1,00 A; tensão `0x1388` = 5000 → 50,00 V; potência `0xfc18` = −1000 → −100,0 W | exatamente esses valores |
| Odômetro sem sinal falso | `dado[8..9] = 0x00 0x80` (32768 como s16 = −32768) | o decoder deve rejeitar ou tratar como inválido (decisão do dono do decoder; teste documenta o comportamento escolhido) |
| Viagem | `dado[6..7] = 0xe8 0x03` (1000) | 100,0 km |
| Motor 2 | `dado[16] = 3` e `0xF1` | `motor2TempC` continua `null` (protocolo 1.03 < 1.05) |
| Plausibilidade | motor fora de 0..120 °C, V fora de 20..90 V | campo `null` (não exibir lixo) |
| Tamanho | LEN ≠ 18 | `null` (já testado hoje; manter) |
| Regressão | Meter2 quadro A não altera `locked`, `batteryPercent`, `speedLimitKmh` | permanecem `null` (grupos independentes) |

Meter1 (CMD 0x20):

| Teste | Entrada | Esperado |
|---|---|---|
| Velocidade | `dado[4] = 0xc6`, `dado[8] = 0` | 19,8 km/h |
| Velocidade 10 bits | `dado[4] = 0x2c`, `dado[8] = 0x01` | 30,0 km/h ((1<<8 \| 0x2c) = 300 × 0,1) |
| Média e carga | `dado[5] = 0x32`, `dado[6] = 0x0a`, `dado[8] = 0` | média 5,0; carga 10 (não movem a "velocidade") |
| Freio | `dado[1] = 0x08` | freio = true, bloqueado = false |
| Bloqueado | `dado[1] = 0x20` e `0x30` | bloqueado = true (já testado) |
| Luzes | `dado[0] = 0x18` | `largeLight`/`rearLight` ligados; `0x00` desligados |
| Cruise / zero start | `dado[11] = 0x40` / `0x80` | cruise ligado / zero start ligado |
| Modo e unidade | `dado[10] = 0x01`/`0x02`/`0x03`/`0x04`/`0x05`/`0x06`/`0x07` | modos 1, 2, 3, 4, 5, 0, 0 (tabela do original); `dado[10] = 0x08` → unidade = mph (marcar `[HIPÓTESE]` no teste) |
| Versão | `dado[12..14] = 0x23` | "1.03" |
| Falha | `dado[2] = 0x05` | falha = 5 |

Cada teste de campo `[HIPÓTESE]` deve trazer o rótulo no nome (`hipotese_...`), para que a UI possa filtrar o que exibe.

## 6. Resumo

1. Meter2 decifrado só com os offsets/escalas do original, conferidos em 11 349 quadros reais: potência (s16 × 0,1), tensão (s16 × 0,01), corrente (s16 × 0,01), viagem (s16 × 0,1), odômetro (s16 × 1), autonomia/corrente média/brilho/temperatura da bateria (u8, nunca variam), temperatura do motor (u8 °C), marchas habilitadas (máscara).
2. Quadro A = 54,40 V, 0 A, odômetro 24 km, motor 33 °C, 0 W. Quadro C (acelerador no cavalete) = 87,2 W = 54,5 V × 1,60 A: a aritmética P = V·I fecha em 56 payloads.
3. Odômetro 23 → 24 km bate com a foto do painel de S17 (ODO ≈ 23 km), e corrige a hipótese antiga de "temperatura em dado[8]".
4. Temperatura do motor = `dado[14]` direto em °C (21-36 °C nas capturas, esfria em ~19 min).
5. `dado[16]` (motor 2) é inválido neste patinete (protocolo 1.03 < 1.05 e valores 3/0xF1): não exibir.
6. Tensão 54-55,7 V: 14S a ~3,98 V/célula ou 13S acima de 4,2 V; não ligar a % de bateria nem cravar a química.
7. Meter1: velocidade real é `dado[4]` (+ bits de `dado[8]`) × 0,1, não `dado[4..6]`; `dado[5]` (média) e `dado[6]` (carga) podem dar "movimento" falso, mas só no lado seguro.
8. Meter1 novo: luzes (`dado[0]`), freio (`dado[1] b3`), modo/unidade (`dado[10]`), cruise/zero start (`dado[11]`), versões (`dado[12..14]`), falha (`dado[2]`).
9. Não fecha estaticamente: unidade W, regen (corrente negativa), % × tensão, viagem, bits altos de velocidade; todos exigem só escuta + foto/tela do original no mesmo instante, nenhum comando novo.
10. Classificação sugerida para a UI: tensão/corrente/odômetro/temperatura do motor como `[HIPÓTESE forte]` (exibir com ressalva até comparar com painel); o resto, omitir.
