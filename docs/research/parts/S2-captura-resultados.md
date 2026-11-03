# S2 — Resultados da captura (2026-11-03)

Fonte: app original 1.0.20 **reempacotado** com o logger passivo IX7TAP (`scripts/repack/`), patinete no cavalete, roteiro `docs/research/captura-roteiro.md` executado pelo proprietário (14:00–14:19). Bruto em `research/captures/s2d/` (ignorado). Relatório: `scripts/analysis/ix7tap_parse.py`. Ressalva: o app não é o binário original; os bytes observados são os que a FastBle entregou/recebeu.

Por que reempacotar: o HCI snoop do Samsung (Android 17) não é acessível sem root (o btsnoop não vem no `bugreport`, `/data/misc/bluetooth` dá permissão negada, as propriedades `persist.bluetooth.*` dão "Access denied" ao `shell`, não há socket). O logcat do app não registra BLE.

## Números (uma sessão, 12 ciclos de bloqueio)
| Medida | n | mediana | p95 | mín–máx |
|---|---|---|---|---|
| t_cmd (TX → callback de escrita) | 12 | 3,5 ms | 5,0 ms | 2,6–9,7 ms |
| t_ack (TX LOCK → ACK 0x32 tipo 05) | 12 | 51 ms | 122 ms | 29–132 ms |
| t_state (TX LOCK → Meter1 com bit5 trocado) | 12 | 419 ms | 681 ms | 175–729 ms |
| t_auth (CONNECT 0x30 → 1ª resposta) | 6 | ~38 ms | 74 ms | 27–74 ms |
| intervalo do heartbeat 0x10 | 865 | 1011 ms | 1100 ms | — |

12/12 comandos de bloqueio tiveram ACK e mudança de estado confirmada. t_scan/t_conn não foram medidos (sem HCI) → `[DESCONHECIDO]`.

## Achados
- `[CONFIRMADO]` TX CRC = CRC-16/Modbus sobre CMD..DADOS, byte baixo primeiro (todos os TX).
- `[CONFIRMADO]` Respostas: tipo `05` = ACK (ex.: `1aa1 30 05 01 01 …`), consultas respondem com tipo `04`, telemetria Meter1 (0x20) e Meter2 (0x21) com tipo `03`, LEN 18, ~a cada 300 ms cada.
- `[DESCONHECIDO]` CRC das respostas: não confere com Modbus, XMODEM, CCITT-FALSE nem soma16 (sobre CMD..DADOS ou frame inteiro, nas duas ordens). O app não o verifica (`convertBleData` só confere `1A A1` e tamanho `[ESTÁTICO]`).
- `[CONFIRMADO]` Bloqueio = LOCK 0x32 modo 2 (`1aa1 32 02 01 02 …`), desbloqueio = modo 0; **DADOS só com o modo (LEN 1), sem PIN**. Meter1 status (dado[1]) bit4 `lock_has_pwd` = 0 em toda a sessão: este patinete **não tem PIN definido**. Bit5 `lock_status` vira 1 após bloquear e 0 após desbloquear (12/12).
- `[CONFIRMADO]` H11 para este patinete: bloqueio e desbloqueio sem PIN. O app novo pode fazer um toque sem pedir nada. Só modos 2 e 0; modo 1 (define PIN) e 3 continuam proibidos.
- `[CONFIRMADO]` Início de sessão do app: `0x41` (usuário atual) com valor **0**, consulta SN `0x61`, `CONNECT 0x30` valor 1 (2×), consultas `0x62` (limite de velocidade). Com 0x41=0 o bloqueio funcionou → o firmware não exige 0x41=1 para LOCK. O proprietário não encontrou opção de proximidade no app (o passo 3 do roteiro não foi feito); pela análise estática, 0x41 só vale 1 com a preferência `CAR_DISTANCE_CURRENT_SWITCH` ligada, então ela estava desligada na sessão toda. H7A: bloqueio funciona sem proximidade `[CONFIRMADO]`; o comportamento *com* proximidade ligada não foi testado.
- `[CONFIRMADO, com ressalva]` H7B: o proprietário fez o passo sem rede; todos os 12 ciclos funcionaram. A captura não marca qual ciclo foi offline.
- `[CONFIRMADO]` Heartbeat `0x10` é consulta (tipo `01`, sem dados), ~1 s, e o patinete responde ACK.
- `[NEGATIVO]` CONNECT 0 ao fechar o app não foi observado (todos os CONNECT tinham valor 1).
- `[HIPÓTESE forte]` Desligamento automático: a telemetria parou 10 min 01 s após o último comando (14:03:02 → 14:13:03), com o app aberto e heartbeats saindo. O roteiro de "ocioso ≥10 min" não é viável; o app novo deve tratar isso como desconexão esperada.
- `[CONFIRMADO]` Meter1 dado[3] = bateria (0x64 = 100 %), dado[7] = limite de velocidade atual (0x32 = 50), coerente com byte[12] da análise estática.

## Comandos de configuração observados (para o backlog)
Todos com ACK tipo 05. Nome pela constante em `BleConstants` `[ESTÁTICO]`; significado do valor pelo que o proprietário fez `[CONFIRMADO]` salvo indicação.
| CMD | Constante | Ação do proprietário | DADOS observados |
|---|---|---|---|
| 0x33 | APP_LIGHT | farol liga / desliga | `00 fa 00 00 00 00` / `00 00 00 00 00 00` (significado de `fa` `[HIPÓTESE]`: brilho 250) |
| 0x3C | APP_UNIT | km ↔ milhas | `01` / `00` (qual é milhas `[HIPÓTESE]`: 01) |
| 0x35 | APP_ELECTRIC_GEAR | modo de condução | `01 00`, `02 00`, `03 00`, `00 00` |
| 0x38 | APP_CRUISE_GEAR | cruise liga / desliga | `05 03` / `00 03` |
| 0x3B | APP_DRIVE | um / dois motores | `01` / `00` |
| 0x39 | APP_START_SPEED | partida do zero liga / desliga | `01 00` / `00 00` |
| 0x36 | APP_SPEED_LIMIT_GEAR | limite da marcha mais alta | 1 byte em km/h: 39, 50, 46, 40, 49, 50 |
| 0x63 | APP_QUERY_LIGHT | consulta do farol | sem dados |

Estado final informado pela tela do app: cruise desligado, tração em uma roda, modo "Walking", partida do zero ligada, limite 50 km/h.
