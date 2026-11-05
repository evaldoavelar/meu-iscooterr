# Sprint 20 — Bloqueio temporário (LOCK modo 2)

Autorização: o proprietário aprovou que o app bloqueie o patinete (comando real) **somente** pelo LOCK modo 2
("bloqueio temporário"), sem PIN. Os modos 1 (bloquear com PIN) e 3 (limpar PIN) e qualquer outro CMD seguem proibidos.

## Evidência
- `[CONFIRMADO]` 12/12 em `docs/research/parts/S2-captura-resultados.md` e `S13-pin-captura.md`: bloqueio = `1aa1 32 02 01 02 <CRC> 1ff1`
  (CMD 0x32, TIPO 02, LEN 1, DADOS = modo 2); Meter1 `dado[1]` bit5 vira 1 após bloquear.
- Quadro do app: `1aa1320201022f0d1ff1`. CRC-16/Modbus 0x0d2f calculado fora do código testado (script à parte, que reproduz
  os literais de CONNECT/heartbeat). As capturas em `research/captures/` mascaram o CRC do LOCK (`1aa132020102****1ff1`),
  então **não há literal de referência com CRC** para este quadro: `[DESCONHECIDO]` até o primeiro teste real.

## Regras implementadas
- `TxCommand.Lock`: objeto fechado, modo 2 fixo, LEN 1. Sem API genérica de bytes/modo; `send()` do transporte real segue
  rejeitando tudo. Testes provam: só existem os quadros 0x32 modo 0 (Unlock, LEN 5) e modo 2 (Lock, LEN 1); nenhum CMD 0x33..0x39.
- `ScooterTransport.lock(): LockResult` (`Locked` / `Rejected` / `Refused(motivo)` / `Unconfirmed(causa)`), só com `ble_tx`.
- Recusa SEM escrever: ocupado (inclui desbloqueio em curso), enlace sem ACK recente, Meter1 não fresco (< 2 s), velocidade
  desconhecida, roda ou velocidade em movimento (travar em movimento é perigoso: falha segura).
- UMA tentativa, sem retry. Carimbo/fronteira de recepção como no desbloqueio (ADR 0004). `Locked` só com Meter1 NOVO
  (recebido após a escrita) com bit5 = 1 em até 3 s inclusive; o ACK tipo 05 sozinho não confirma.
- Resposta tipo 0x89 → `Rejected`; sem confirmação → `Unconfirmed(Timeout)`; escrita sem conclusão/falha/enlace perdido →
  `Unconfirmed(...)` e GATT fechado (estado indeterminado).
- Home: botão "Bloquear" (sem PIN, sem teclado) chama `lock()` quando o transporte oferece; "Bloqueando…" → "Bloqueado às HH:MM"
  só após `Locked`; em movimento mostra "Pare para bloquear"; recusa mostra o motivo; falhas nunca dizem "concluído" (regra 5).
- A preferência `lockWithPin` continua sem efeito (não é lida).

## Não validado
- `[DESCONHECIDO]` Se o patinete **com PIN definido** aceita o modo 2 sem PIN: a testar com o proprietário.
- `[DESCONHECIDO]` Se o erro tipo 0x89 também vale para o modo 2 (só observado para PIN recusado no desbloqueio).
- `[DESCONHECIDO]` CRC do quadro contra uma captura sem máscara; efeito real no patinete (nenhum teste real feito nesta tarefa).
- A latência do Meter1 após o bloqueio real; a janela de 3 s vem do desbloqueio.
