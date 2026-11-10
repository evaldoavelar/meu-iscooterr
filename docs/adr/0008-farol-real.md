# ADR 0008 — Farol real (CMD 0x33)
Status: aceito (proposta aprovada pelo proprietário, S31) · Implementação `[ESTÁTICO]` até o teste real com "pode" no chat

## Contexto
Mapeamento em `docs/research/parts/S31-farol.md` (capturas s2d e s13pin do original). `[CONFIRMADO]` CMD 0x33, TIPO 02, LEN 6, ligar `00 fa 00 00 00 00`, desligar seis zeros; Meter1 `dado[0]` (byte[5]) 0x00 ↔ 0x18 em 6/6 transições.

## Decisão
1. `TxCommand.Light(on)` fechado: único parâmetro booleano; só existem os dois quadros observados (`1aa133020600fa0000000075cc1ff1` e `1aa1330206000000000000add81ff1`). Outros brilhos e os 4 bytes finais são `[DESCONHECIDO]` e inexistentes. 0x3b, 0x41, 0x61–0x63 seguem proibidos (testes).
2. `BleScooterTransport.setHeadlight(on)` com as mesmas travas do Lock (TX ligado, enlace com ACK recente, Meter1 fresco, roda parada, velocidade 0, nenhuma recepção pendente, um comando por vez, uma escrita, sem retry). **Acréscimo:** recusa com o patinete BLOQUEADO (`HeadlightRefusal.Locked`), como o botão do original e mais seguro; o proprietário aceitou "como proposto", e isto é restrição extra, não relaxamento.
3. "Concluído" só com Meter1 NOVO (após a escrita, ≤ `unlockConfirmMs` = 3 s do carimbo de recepção, ADR 0004) com `dado[0]` bit4 igual ao pedido. ACK 0x33 sozinho não vale; bit3 (traseira) é ignorado. Sem confirmação → `Unconfirmed(Timeout)` visível; resposta 0x89 → `Rejected`.
4. Estado exibido do farol = Meter1 bit4 (`Telemetry.headlightOn`). Meter2 não o carrega; o `overlay` mantém o último valor do Meter1.
5. Integração: `ScooterTransport.headlightAvailable` (padrão = `genericCommandsAvailable`; BLE real = TX ligado). `send(SetHeadlight)` chama `setHeadlight`; recusa/falta de confirmação voltam como `Ack.Rejected` com códigos de `HeadlightAckCodes`, que a Home mostra com texto próprio (a recusa some em 6 s). `genericCommandsAvailable` continua `false`: Tração/Modo/Cruise/Partida/Limite seguem desabilitados. O Fake mantém o comportamento.
6. Auditoria `IX7TX`: `TX #n LIGHT <hex completo>` (sem PIN, sem máscara), `LIGHT requested/sent/result=`.

## Riscos
- `[HIPÓTESE]` "ligado" = b4 (0x18 = b3+b4 não se separam nas capturas); se o firmware subir só b3 o app reporta sem confirmação.
- `[DESCONHECIDO]` se o firmware aceita 0x33 com o patinete bloqueado (a recusa é só do app) e se usa tipo 0x89 para erro.
- O reducer confirma o pendente por qualquer telemetria posterior igual ao pedido; pedir o estado já exibido pode mostrar "confirmado" antes do Meter1 pós-envio (o toggle da UI pede o oposto do exibido, então é improvável).
- Pendência da UI expira em 3 s do pedido; o resultado do transporte pode chegar até ~3 s do envio e substitui o "sem resposta" por "sem confirmação".


## Atualização S32 (teste real)
Todos os comandos deste ADR (farol 0x33) passaram a `[CONFIRMADO]` no teste real de 2026-11-09 (`docs/research/parts/S32-comandos-real.md`).

## Adendo S40 (riscos 3 e 4 reavaliados)
`[ESTÁTICO]` Os riscos 3 e 4 acima valem só para o caminho legado (sem transporte real). Com o BLE real, `HomeState.transportConfirmed` torna a Home inerte para esses comandos: `TelemetryReceived` não os confirma por telemetria acumulada (`HomeReducer.kt`, filtro `!transportConfirmed`) e o `Tick` não os expira (idem). Só o resultado do transporte (Meter1 NOVO após a escrita, prazo `unlockConfirmMs`) conclui, então "confirmado antes do Meter1 pós-envio" e "sem resposta → sem confirmação" não ocorrem no real. Riscos 1 e 2 seguem como estavam (1 coberto pelo teste real S32).

