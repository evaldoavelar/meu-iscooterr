# S31 — Farol (F4.15): mapeamento a partir de capturas já existentes

Sem captura nova: o farol já foi acionado no original em duas capturas privadas (`research/captures/s2d/ix7tap-roteiro2.txt`, `research/captures/s13pin/ix7tap-pin.txt`). Análise estática por agente Sonnet (leitura), conferida por amostragem pelo coordenador (linhas 4756/4777 de s2d; `BleConstants.java:28-29`). Nenhum TX nosso.

## Comando
- `[CONFIRMADO]` Farol = CMD **0x33** (APP_LIGHT), TIPO 02, LEN 6. Ligar: `1aa1 33 02 06 00fa00000000 75cc 1ff1`. Desligar: `1aa1 33 02 06 000000000000 add8 1ff1` (CRC-16/Modbus sobre CMD..DADOS, byte baixo primeiro; recalculado com o algoritmo do projeto).
- `[CONFIRMADO]` ACK `1aa1 33 05 00 ff55 1ff1` em 3–120 ms; o ACK não prova execução.
- `[ESTÁTICO]` `q3/a.java:111-114` (`lightsSet`), botão em `DeviceCarControlFrag.java:3641-3660`: ligar = `i(0,250,0,0,0,0)`, desligar = zeros. O botão do original recusa com patinete bloqueado (só no cliente; firmware não testado).
- `[HIPÓTESE]` `fa` = brilho (250). Valores intermediários `[DESCONHECIDO]`. Os 4 bytes finais (cor/modo da luz ambiente) só com 0; qualquer outro valor `[DESCONHECIDO]` e proibido.

## Confirmação de estado
- `[CONFIRMADO]` Meter1 `dado[0]` (byte[5]) 0x00 → 0x18 ~1 s após ligar e 0x18 → 0x00 ao desligar: 6/6 transições em 2 capturas.
- `[ESTÁTICO]` mapa de bits `BleViewModel.java:377-396`: b3 traseira, b4 farol grande. `[HIPÓTESE forte]` 0x18 = b3+b4, sobem juntos; não dá para separar. Regra proposta: "ligado" = b4=1.
- `[DESCONHECIDO]` consulta 0x63 (resposta só ACK, sem dados de luz).

## Outros comandos do original (nenhum implementado)
0x3b = APP_DRIVE tração 1/2 motores (`..3b 02 01 00` = 0, `..01` = 1; F4.16) · 0x41 = valor do usuário atual (0) · 0x61 = consulta de SN · 0x62 = consulta do limite de velocidade (F4.17) · 0x63 = consulta de luz.

## Proposta de implementação (aguarda aprovação do proprietário)
`TxCommand.Light(on)` fechado: CMD 0x33, TIPO 02, payload `00 (fa|00) 00 00 00 00`; mesmas travas do Lock (BLE real, parado, Meter1 fresco, uma tentativa, sem repetição); "concluído" só com Meter1 b4 igual ao pedido após o envio (≤ 3 s); recusa/timeout visíveis. Proibidos por teste: demais valores de brilho, bytes de cor ≠ 0, variantes `j`/`k`. Decisão pendente: permitir com patinete bloqueado? (proposta: não, como o original). Teste real só com "pode" no chat.
