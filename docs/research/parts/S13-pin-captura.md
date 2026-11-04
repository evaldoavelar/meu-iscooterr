# S13 — captura do teste com PIN no app original (2026-11-04)
Fonte: logcat `IX7TAP` do app original 1.0.20 reempacotado (mesmo SHA do `research/captures/repack/out/base.apk`), patinete conectado, 13:24–13:26. Bruto: `research/captures/s13pin/ix7tap-pin.txt` (ignorado pelo git; dígitos do PIN mascarados pelo logger). Relato do proprietário: definiu um PIN, bloqueou, desbloqueou, depois rotacionou o PIN.

## Achados
- `[CONFIRMADO]` Com PIN, o LOCK 0x32 tem **LEN = 5**: `1aa1 32 02 05 <modo> <4 bytes do PIN> CRC 1ff1`. Sem PIN (S2) era LEN = 1 (`… 32 02 01 <modo>`). A conclusão "sem PIN" da S2 vale só para o estado de 03/11 (patinete sem PIN naquele dia); **não vale para um patinete com PIN**.
- `[CONFIRMADO]` Três LOCK com PIN, nesta ordem (13:24:26, :36, :46), modos **3, 1 e 0**; cada um recebeu ACK (`RX 1aa1 32 05 00`, tipo 05) e escrita GATT `status=0`.
- `[CONFIRMADO]` Meter1 `dado[1]` (bits 4 = `lock_has_pwd`, 5 = `lock_status`): 0x30 antes e logo após o modo 1; 0x00 após os modos 3 e 0. Ou seja: `0x30` (PIN presente + bloqueado) → [modo 3] → `0x00` → [modo 1] → `0x30` → [modo 0] → `0x00`.
- `[HIPÓTESE]` Significado dos modos com PIN: modo 0 = desbloquear com PIN (consistente com S2 e com a transição final); modo 1 = bloquear com PIN (transição para 0x30); modo 3 = ? (levou 0x30 → 0x00; pode ser "remover/validar PIN" ou desbloqueio por outro caminho). **Diverge do estático** (T3-T7: modo 1 = define PIN). Falta o proprietário dizer qual ação de tela correspondeu a cada horário (13:24:26, :36, :46).
- `[DESCONHECIDO]` Se `lock_has_pwd` espelha "bloqueado com PIN" ou "PIN cadastrado": o bit zerou ao desbloquear, o que sugere o primeiro.
- Outros comandos vistos, não decodificados: 0x61 (dado mascarado pelo logger, 13:24:16), 0x63 (13:26:24; resposta `1aa1 63 05 00`), 0x62 (`…1432…`, resposta do patinete), 0x33 modo 6 (`fa00…` / `0000…`, 13:25–13:26, provavelmente limite de velocidade). Nenhum foi reproduzido.

## Consequências
- O app novo **não envia nenhum LOCK** (4.4 bloqueada). Ao chegar na 4.4: tratar PIN como obrigatório se `lock_has_pwd` indicar; o PIN é do proprietário, nunca definido/adivinhado pelo app (ADR 0002 e plano da Fase 4). Os modos 1 e 3 continuam proibidos até virarem `[CONFIRMADO]`.
- Proprietário já rotacionou o PIN (memória `pin-leak-hardening`); o log só tem o PIN mascarado.
