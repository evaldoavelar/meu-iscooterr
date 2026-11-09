# ADR 0010 — Cruise (0x38) e Partida do zero (0x39) reais
Status: aceito (camada protocolo+domínio+UI) · Implementação `[CONFIRMADO]` no app novo (teste real S33, 2026-11-09: `docs/research/parts/S33-cruise-partida-real.md`) · Mapeamento: `docs/research/parts/S32-cruise-partida-mapeamento.md` · Complementa o ADR 0009

## Decisão
1. `TxCommand.Cruise(on)` (CMD 0x38, dados `05 03` liga / `00 03` desliga) e `TxCommand.ZeroStart(on)` (CMD 0x39, dados `00 00` LIGA / `01 00` DESLIGA; semântica invertida). API pública só com `Boolean`; quadros com CRC fixados em `TxFrameTest`.
2. Estado lido do Meter1 dado[11]: `cruiseOn = bit6`, `zeroStartOn = (bit7 == 0)`.
3. `ScooterTransport.setCruise/setZeroStart` via `guardedSetting` (mesmas travas de tração/limite: TX ligado, Meter1 fresco, roda parada e velocidade 0, desbloqueado, um comando por vez, uma tentativa sem retry). Recusa `StateUnknown` se o campo está `null` (nunca envia às cegas). Concluído só por Meter1 novo com o bit pedido; ACK não conclui (regra 5).
4. Home: toggles passam pelo mesmo roteamento (`sendsViaTransportApi`) e porta (`realGate`) dos demais; falhas viram mensagem no card (`SettingAckCodes`). Farol, unidade, modo, limite e tração inalterados. Nenhum aviso/diálogo novo (o original exibe um na primeira vez; fica como pendência de UI).

## Evidências
- `[CONFIRMADO]` no original: quadros e Meter1 em `s2d/ix7tap-roteiro2.txt` (8/8 transições) e `s32-comandos/s32-original-tap.log` (14:49; cruise bit6, partida do zero 01 00 = desabilitada = bit7 sobe).
- `[ESTÁTICO]` jadx: `DeviceCarSettingAty` (sem checagem de movimento/bloqueio no cliente); texto de ajuda do cruise.

## Riscos
- Comportamento do firmware com patinete em movimento ou bloqueado `[DESCONHECIDO]`; o app recusa nesses casos (mais estrito que o original).
- O cruise só habilita; o engate real é segurando o acelerador ~5 s `[ESTÁTICO]`. Se bit6 indica "habilitado" ou "engatado" `[HIPÓTESE]` (habilitado, pelas capturas paradas).
- Desligar a partida do zero exige empurrar para dar partida; ligada é o estado de fábrica do dono.
- Valores de byte (`05`, `03`) além dos observados `[DESCONHECIDO]` e inexistentes na API.
- Status: **teste real feito na S33** (5 TX → 5 ACK → Meter1 byte[16] 0x40/0x00/0x80/0x00/0x40, ≤ 0,81 s) `[CONFIRMADO]`; bit6 = cruise habilitado e bit7 = partida desabilitada confirmados no app novo; cruise engatado em movimento segue `[DESCONHECIDO]`.

## Adendo S34 — aviso de 1º uso
Cruise: ao ligar pela primeira vez, a Home mostra um `AlertDialog` Material 3 (texto rolável) com o texto do original em português (`cruise_control_tips_ccti` `[ESTÁTICO]`). Partida do zero: o aviso aparece nos dois sentidos (ligar e desligar; o original avisa em ambos e desligar é o mais arriscado), com o texto `speed_limt_tips_slt` `[ESTÁTICO]`. Botões "Cancelar" e "Entendi, continuar".
- Cancelar não grava nem envia nada. Confirmar grava `cruiseWarningAck`/`zeroStartWarningAck` em `Preferences` (persistente, `KeyValuePreferencesStore`) e pede o comando só se o controle ainda está habilitado; se ficar desabilitado com o diálogo aberto (conexão caiu, bloqueou, movimento), o diálogo fecha sem gravar nem enviar. Padrão fail-closed: sem informação de ack, o aviso aparece.
- Decisões: o aviso persiste para sempre (o original reseta o do Cruise em `onDestroy`); "Esquecer patinete" não zera os acks; o texto da Partida do zero é o mesmo do aviso do slider de limite no original, e o limite não tem aviso no app novo.
Resolve a pendência de UI da decisão 4.
