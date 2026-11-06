# Tarefa — Farol, tração (1/2 motores) e velocidade com o patinete real (F4.15–F4.17)
Origem: pedido do proprietário na S30 (2026-11-06): "quero o farol, o motor e a velocidade funcionando". Hoje esses controles ficam desabilitados com o patinete real ("Este comando ainda não está disponível…", S22); `fase-4-plano.md` os exclui até haver plano e aprovação próprios (este arquivo é o plano).

## Ordem
1. **F4.15 Farol** (menor risco; o Meter1 já traz o estado das luzes, `S29-meter2-decodificacao.md` §1).
2. **F4.16 Tração 1/2 motores** (tela Settings do original: "Single and dual drive mode").
3. **F4.17 Velocidade** (limite 20–50 km/h e "Driving mode", ex.: Walking mode; maior efeito na segurança).

## Método de cada item (investigação antes de implementação)
1. **Mapear** com captura passiva (log `IX7TAP` do original reempacotado; privada em `research/captures/`): o proprietário aciona UMA função por vez no original, anotando a hora; o agente lê o TX/RX e identifica comando, parâmetros, CRC e a resposta/estado que confirma. Nesta sessão o original enviou `0x3b`, `0x41`, `0x61`–`0x63`: **nenhum mapeado ainda** `[DESCONHECIDO]`. Classificar achados `[ESTÁTICO]/[HIPÓTESE]/[CONFIRMADO]`; conferir com o decompilado (`BleViewModel`, `SpeedGearsDto`).
2. **Plano e aprovação** do proprietário (comando exato, parâmetros permitidos, lista fechada em `TxCommand`, proibidos por teste como no Unlock).
3. **Implementar** com TDD: comando só com patinete parado, Meter1 recente, conexão ativa, um por toque; "concluído" só com confirmação do patinete (Meter1 mostrando o novo estado após o envio); recusa e timeout visíveis; sem repetição automática.
4. **Teste real** com "pode" NO CHAT, patinete parado e no cavalete, proprietário presente, log `IX7TX`; registrar em `docs/research/parts/`.
5. Revisor independente (≠ autor) e `clean check` EXIT 0 antes do merge local.

## Fora de escopo / proibido sem aprovação específica
PIN (definir/alterar/limpar), LOCK modos 1/3, firmware, cruise e partida do zero (ficam para depois, mesmo método), qualquer comando em movimento, push.
