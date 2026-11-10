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
