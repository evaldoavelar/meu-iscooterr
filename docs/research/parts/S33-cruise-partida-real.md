# S33 — Teste real de Cruise (0x38) e Partida do zero (0x39) pelo app novo

Data: 2026-11-09. APK da `main` @ 57646f3 (ícone novo). Patinete parado, no cavalete, desbloqueado; proprietário presente e operando o app; app original fechado (`am force-stop`) antes. "Pode" dado no chat pelo proprietário. Log `IX7TX` do app novo (PID 30023) em `research/captures/s33-comandos/ix7tx-2.log` (privado, git-ignorado; a gravação ao vivo `ix7tx-1.log` morreu quando a porta do adb mudou e foi recuperada com `logcat -d`).

Sessão BLE: 1ª tentativa `GATT timeout step=Connect` (15:31 → 15:37, ~6 min sem conectar), 2ª tentativa conectou em 15:37:18 (MTU 255, CCCD, CONNECT 0x30 com ACK). Unlock com PIN: `Unlocked` em ~0,7 s. Causa do timeout da 1ª tentativa: `[DESCONHECIDO]` (provável original ainda segurando a conexão logo após o force-stop; não investigado).

## Resultado (TX → ACK → Meter1 CMD 0x20, byte[16])

| # | Hora | Ação no app | TX (dado) | ACK | Meter1 byte[16] | App |
|---|------|-------------|-----------|-----|-----------------|-----|
| 1 | 15:37:26.035 | Cruise ligar | `38 05 03` (`1aa1380202050366ec1ff1`) | RX 0x38 em +100 ms | 0x00 → **0x40** em +513 ms | Confirmed |
| 2 | 15:37:29.939 | Cruise desligar | `38 00 03` (`1aa1380202000365bc1ff1`) | RX 0x38 em +91 ms | 0x40 → **0x00** em +807 ms | Confirmed |
| 3 | 15:37:33.679 | Partida do zero desligar (`on=false`) | `39 01 00` (`1aa1390202010019ed1ff1`) | RX 0x39 em +40 ms | 0x00 → **0x80** em +538 ms | Confirmed |
| 4 | 15:37:36.447 | Partida do zero ligar (`on=true`) | `39 00 00` (`1aa13902020000187d1ff1`) | RX 0x39 em +55 ms | 0x80 → **0x00** em +643 ms | Confirmed |
| 5 | 15:37:38.427 | Cruise ligar (de novo) | `38 05 03` | RX 0x38 em +63 ms | 0x00 → **0x40** em +203 ms | Confirmed |

- Os 5 TX são byte a byte iguais aos do original (`S32-cruise-partida-mapeamento.md`); 5/5 ACK; 5/5 transições do Meter1 no primeiro quadro após o comando (≤ 0,81 s); "concluído" só após o Meter1 (regra 5).
- Bit6 = cruise **habilitado** e bit7 = partida do zero **desabilitada** (`01 00` sobe o bit7) `[CONFIRMADO]` também no app novo.
- Cruise **engatado** (acelerador 5 s) não foi testado (exigiria movimento): se o bit6 sobe ao engatar segue `[DESCONHECIDO]`.
- Estado final observado: **Cruise habilitado** (byte[16] = 0x40, 5º comando); partida do zero ligada.
- Nenhum comando fora da lista; sem movimento.
