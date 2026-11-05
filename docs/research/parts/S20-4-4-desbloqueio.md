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
