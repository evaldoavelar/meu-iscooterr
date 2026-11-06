# S29 — Teste real do cofre de PIN e do primeiro uso (2026-11-06)

APK da `main` @ 21e7036 (cooldown de PIN), sem extras de depuração, adb por Wi-Fi. Proprietário operou o patinete; o app não tem log de TX (só `[ESTÁTICO]` + sistema Android).

| Achado | Rótulo | Evidência |
|---|---|---|
| Primeiro uso: varredura achou o patinete, o proprietário confirmou; a Home abriu **"Desconectado"**; voltar e reabrir conectou | `[CONFIRMADO]` (relato + print 14:33) | defeito aberto: reconexão/connect inicial após cadastro |
| Cofre: chave `iscooter_pin_v…` gerada às 14:33:49 (Salvar PIN), operação Keystore 14:34:44, "Usar PIN salvo" → biometria por digital OK às 14:35:19 | `[CONFIRMADO]` | logcat do aparelho (keystore2, FingerprintService, BiometricPrompt 14:35:19) |
| Depois das 14:35:19 não há nenhuma operação Keystore do app: nenhum "Usar PIN salvo" posterior. PIN digitado à mão não deixa rastro no log | `[ESTÁTICO]` | logcat |
| O app foi para segundo plano às 14:37:08 e o processo foi **congelado** pelo Android às 14:38:18 (`am_freeze`): sem serviço em primeiro plano o heartbeat para | `[CONFIRMADO]` | logcat (`am_freeze 29919`) |
| Único caminho no código que escreve Unlock/Lock: botão da Home (`HomeController.unlock/lock`); nada automático escreve além de CONNECT/heartbeat | `[ESTÁTICO]` | grep `BleScooterTransport.kt:187,251`, `HomeViewModel.kt:374,393` |
| Patinete desbloqueou sozinho e apitou com o app em segundo plano | `[HIPÓTESE]` | causa não provada: sem log de TX; app congelado desde 14:38:18 não poderia enviar nada depois |

Pedidos do proprietário: biometria primeiro (cancelar → teclado de PIN); corrigir "Desconectado" após o cadastro.

## Segundo e terceiro testes (APK `main` @ 391b6a2, log `IX7TX`)

| Achado | Rótulo | Evidência |
|---|---|---|
| 2º teste (APK 21e7036, sem log do app): na 1ª tentativa de Desbloquear (biometria 14:47:56,45) NÃO houve escrita GATT além dos heartbeats; a 2ª (biometria 14:48:06,59) gerou escrita extra em 14:48:06,597 e desbloqueou. A recusa da 1ª ficou silenciosa na tela (o motivo exato não foi logado) | `[CONFIRMADO]` (escritas `btif_gattc_write_char` no logcat do sistema) | logcat do aparelho |
| 3º teste (log `IX7TX`): conectar sozinho ao abrir → CONNECT acked em 113 ms; Desbloquear → `UNLOCK sent` → `Unlocked` em ~0,8 s; Bloquear (modo 2) → `Locked` em ~0,5 s; "Desconectar" do menu → `SESSION end reason=UserDisconnect` sem reconexão automática | `[CONFIRMADO]` | `IX7TX` capturado por adb (`/tmp`, não versionado) |
| Cofre com biometria primeiro, conexão ao abrir, botão vermelho e menu do chip: aprovados pelo proprietário em uso real ("Tudo funcionando") | `[CONFIRMADO]` | relato do proprietário |
| A causa do desbloqueio sozinho com o app congelado em segundo plano segue sem prova | `[HIPÓTESE]` | — |
