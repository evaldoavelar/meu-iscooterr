# S37 — Air Lock: teste real (2026-11-10)

"Pode" dado no chat pelo proprietário (patinete ligado, parado, 0 km/h, app Conectado, proprietário presente). Log `IX7TX` ativo. APK da `main` 0e68a16 (com reenvio único de `PendingReception` da S35).

## Rodada 1 — d=1 e desconexão (parcial: falta o controle)

| Hora | Evento | Classe |
|---|---|---|
| 10:55:39 | Switch ON: TX 0x41 d=1 (`1aa14102010175881ff1`); 1º envio SEM ACK em 2 s; reenvio às 10:55:41 com ACK `1aa14105007f701ff1` em ~114 ms | `[CONFIRMADO]` (log) |
| 10:55:55 | Switch OFF: 1ª tentativa `PendingReception`, reenvio único em 150 ms, TX d=0 (`1aa141020100b4481ff1`), ACK em ~120 ms | `[CONFIRMADO]` |
| 10:56:11 | Switch ON: TX d=1, ACK em 63 ms | `[CONFIRMADO]` |
| 10:56:11–10:56:41 | Heartbeat contínuo (73 TX de heartbeat acumulados) | `[CONFIRMADO]` |
| 10:57:08 | Desconexão pelo chip (`SESSION end reason=UserDisconnect`, GATT limpo) | `[CONFIRMADO]` |
| ≤ 10:59 | Proprietário observou o cadeado aceso no painel (foto); horário exato do bloqueio desconhecido | relato + foto |
| 10:59:16 | Reconexão só por CCCD + CONNECT (sem Unlock): app mostra "Desbloquear" (Meter1 bit5=1) | `[CONFIRMADO]` |

Leitura: com d=1 confirmado por ACK e desconexão pelo chip, o patinete terminou bloqueado em ≤ ~2 min. **Ainda sem controle**: o patinete já bloqueou sozinho em escuta pura (S21/S29, `[HIPÓTESE]`), então o efeito do 0x41 só é atribuível depois da rodada de controle (d=0 + desconexão, observar 5 min).

## Rodada 2 — controle d=0 (feita)

| Hora | Evento | Classe |
|---|---|---|
| 11:05 | Proprietário desbloqueou no app ("Desbloqueado às 11:05") | `[CONFIRMADO]` |
| 11:05:44 | Switch OFF: TX d=0, ACK em 25 ms | `[CONFIRMADO]` |
| 11:06:31 | Heartbeat 30 s e desconexão pelo chip (`UserDisconnect`) | `[CONFIRMADO]` |
| ~11:08 | Foto: SEM cadeado (desbloqueado) a ~1,5 min da desconexão | relato + foto |
| 11:12 | Proprietário ainda não confirmou o estado | — |
| depois (≤ 11:30) | Foto: cadeado aceso; horário exato desconhecido | relato + foto |

## Conclusão

- Com d=0 (Air Lock desligado, ACK) o patinete TAMBÉM terminou bloqueado depois da desconexão. Logo o bloqueio ao perder o link **não prova** efeito do 0x41 d=1: o firmware bloqueia sozinho após um tempo sem conexão `[CONFIRMADO]` (2 rodadas, ambos os valores).
- Diferença possível de tempo: d=1 bloqueou em ≤ ~2 min; d=0 estava desbloqueado a ~1,5 min e bloqueou em momento desconhecido (≤ ~24 min). Uma rodada de cada, sem cronometrar o bloqueio: `[HIPÓTESE]` de que d=1 encurta o prazo; **não confirmada**.
- Para fechar: repetir cada valor 2× com cronômetro (observador anota o minuto exato do cadeado) ou reconectar a cada 30 s e ler o bit5 do Meter1 no log (sem Unlock). O app pode medir isso sozinho: reconexão periódica só por CCCD e leitura do Meter1. Precisa de "pode" e do patinete desbloqueado a cada rodada.
- O relato da S35 ("não funcionou") é compatível com: sem diferença visível de efeito quando o firmware bloqueia sozinho depois de algum tempo.

## Observações
- O relato da S35 foi de um APK anterior ao reenvio de `PendingReception`.
- Experimento B (laço 2 s × 14) NÃO executado: exigiria mudança de código.
- O PIN foi dado no chat pelo proprietário, mas não foi usado (ele desbloqueou no próprio app); não gravado em nenhum arquivo.

## Sessão 40 (2026-11-10) — medição do prazo (4 rodadas, com "pode" no chat)

Patinete parado, cavalete, proprietário presente, APK da `main` @ a4e77ad. Procedimento: desbloquear, alternar o switch para enviar o valor, ACK, desconectar pelo chip, cronometrar o cadeado no painel (cronômetro do proprietário). Log `IX7TX` privado em `research/captures/s40-airlock-prazo.log` (ignorado pelo git; cobre as rodadas 1–3, o adb Wi-Fi caiu antes da 4).

| Rodada | Valor (TX) | ACK | Desconexão | Cadeado | Classe |
|---|---|---|---|---|---|
| 1 | d=1 `1aa14102010175881ff1` | 12:51:27, ~92 ms | 12:52:05 (`UserDisconnect`) | acendeu em ~2 s | `[CONFIRMADO]` (log + cronômetro) |
| 2 | d=1 | 12:53:16, 34 ms | 12:53:31 | acendeu em ~3 s | `[CONFIRMADO]` |
| 3 | d=0 `1aa141020100b4481ff1` | 12:54:16, 74 ms | 12:54:29 | NÃO bloqueou em 3 min; o patinete desligou pelo próprio timeout e religado estava bloqueado | `[CONFIRMADO]` (log + relato) |
| 4 | d=0 | sem log | sem log | NÃO bloqueou em 2 min | só relato do proprietário (sem log do envio) |

Observação: ao conectar, o app tenta o Air Lock sozinho e recebe `Refused:LinkNotReady` (nada escrito; é só o gate de enlace). O envio real vem do toque no switch.

### Conclusão
- **Air Lock d=1 bloqueia o patinete 2–3 s após a desconexão pelo chip `[CONFIRMADO]` (2/2); com d=0 não bloqueou em 2–3 min (2/2, a segunda só por relato).** Isto corrige a S37: lá o cadeado com d=0 vinha do bloqueio por inatividade do firmware (≤ 24 min), sem cronômetro; o 0x41 d=1 muda o prazo de minutos para segundos.
- O bloqueio por timeout/religamento é um mecanismo separado (o patinete desligou sozinho e voltou bloqueado), não é efeito do 0x41.
- Não foi testado: desbloqueio automático ao reconectar com d=1 (o proprietário desbloqueou pelo app com PIN em cada rodada; o log mostra `UNLOCK` do app); bloqueio com a desconexão por perda de link (só pelo chip).
- Recomendação: **manter** a linha "Bloquear ao desconectar" (tem efeito real). Texto da UI "Efeito ainda em validação" e selo "Em teste" podem ser revistos pelo proprietário (mudança de UI = decisão dele, protótipo `.figcraft` primeiro, regra 2).
- O PIN não foi gravado em arquivo (log mascarado `XX`).
