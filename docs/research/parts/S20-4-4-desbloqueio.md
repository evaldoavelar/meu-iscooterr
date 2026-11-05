# S20 — Etapa 4.4: desbloqueio real pelo app novo (2026-11-05)

## Resultado
- **[CONFIRMADO]** O app novo (APK da `main` @ 6953a0d, `ble_real` + `ble_tx`) desbloqueou o patinete real: o proprietário tocou em "Desbloquear", digitou o próprio PIN no teclado do app e o patinete passou a desbloqueado (confirmação física do proprietário + foto do painel com o ícone verde de piloto). Original fechado, patinete ligado, parado, no cavalete.
- Antes (Teste A, só escuta, sem `ble_tx`): Home "Conectado", 100 %, 0 km/h, botão "Desbloquear" com o patinete bloqueado pelo original; sem comandos enviados.
- O PIN nunca passou por mim, por log, git ou prompt.

## Desvio de processo (registrado)
- O proprietário disparou o Unlock sem dizer "pode" no chat (plano §7.4/§7.7 pedia o "pode" na hora). O resultado foi bom; o proprietário é o dono do patinete e quem tocou. Nenhum comando foi enviado por mim além de `CONNECT`/heartbeat (`ble_tx`).
- Eu reiniciei a Activity com `am start` sem extras logo depois, o que derrubou a sessão ao vivo e a tela do app pós-Unlock não foi capturada.

## [DESCONHECIDO] / pendente
- Se o app exibiu "Desbloqueado" via Meter1 bit5=0 em ≤ 3 s (sem captura de tela nem log; log temporário desligado).
- Número de tentativas (esperado: 1) e resposta do patinete (ACK/`0x89`) — sem log.
- Se um PIN errado produz `0x89` no app novo (não testado; não repetir sem aprovação).

## Lições
- Não rodar `am start` sem os mesmos extras sobre uma sessão viva; para ver a tela usar só `screencap`.
- Para próxima validação com captura: ligar log temporário (`IX7BLE`, sem PIN) ANTES, e combinar o "pode" no chat.

## Captura (2ª rodada, 17:29-17:33, log temporário `IX7BLE` em branch descartada; RX em hex, TX só horário+tamanho; `research/captures/s20-unlock/`, privado)
O proprietário bloqueou pelo original, fechou-o e, no app novo (`ble_real`+`ble_tx`), tentou PIN **errado de propósito** e depois o **certo**. Sem "pode" prévio no chat, **duas tentativas** (o plano previa uma): desvio registrado.
- Sessão: `CONNECT` (len 10) 1× e 96 heartbeats (len 9, ~1 s) sem falha.
- **Tentativa 1 (PIN errado)**: TX len 14 em t=0; escrita concluída em +72 ms; resposta `1aa132 89 00 3e …` (**tipo 0x89 = PIN incorreto**) em +106 ms. Meter1 permaneceu travado (byte `0x30`). **[CONFIRMADO]** (antes só `[ESTÁTICO]/[HIPÓTESE forte]`, S13).
- **Tentativa 2 (PIN certo)**, ~7,8 s depois: TX len 14; escrita concluída em +77 ms; **ACK tipo 0x05 do LOCK (`1aa132 05 00 3e …`) em +17 ms**; Meter1 seguinte com o byte `0x30 → 0x00` (bit5 de travado limpo, e bit4 também) a **+606 ms** da escrita (≪ 3 s) ⇒ **"Desbloqueado" confirmado pelo patinete**. **[CONFIRMADO]**
- Heartbeats e telemetria continuaram normais após as duas tentativas; sem queda de enlace, sem bloqueio por PIN errado observado após 1 erro.
- Resposta com PIN errado NÃO impediu a tentativa seguinte do app (sem backoff no app). **[DESCONHECIDO]**: se o patinete tem contador de erros/bloqueio por N tentativas erradas; não testar mais PIN errado sem decisão do proprietário.
- Formato dos dígitos aceito: o teclado do app envia valor 0..9 por dígito e o patinete aceitou ⇒ o formato `[ESTÁTICO]` foi **[CONFIRMADO]** (o PIN não foi lido).
