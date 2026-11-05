# Etapa 4.3b — tentativa 1 (2026-11-05, sessão 18)
Autorizada (plano §7.7), patinete ligado/parado no cavalete, proprietário presente; app original parado via `am force-stop` antes. APK debug da `main` (eb2dfd9) + log TEMPORÁRIO `IX7BLE` (bytes TX/RX, não versionado). Evidência privada: `research/captures/s4-3b/` (ignorado pelo git).
| Fato | Rótulo | Evidência |
|---|---|---|
| O app enviou `CONNECT` (`1aa1300201016eb41ff1`) **uma vez** às 12:43:21.087 e recebeu o ACK (`1aa130050101d5b31ff1`) em ~100 ms | `[CONFIRMADO]` | `ix7ble-2.txt` |
| Nenhum outro TX foi enviado (0 heartbeats, 0 bytes fora da lista) | `[CONFIRMADO]` | idem |
| Meter1 logo após o CONNECT: dado[1]=0x00 (desbloqueado), bateria 100 %, limite 0x32=50 | `[ESTÁTICO]` (mapeamento) | `RX 1aa120031200000064…` |
| **O app novo travou** 12:43:22.230, ~1,1 s após o ACK: `IllegalArgumentException: size 0 must be greater than zero` em `HomeComponents.kt:252` (`Segments` → `chunked(0)`, chamado por `ChoiceCard`). Causa `[HIPÓTESE forte]`: o transporte real não tem modos de velocidade (`speedModes` vazio) e a Home real renderiza `ChoiceCard` com lista vazia; o Fake sempre tem modos, por isso nunca apareceu | `[CONFIRMADO]` a exceção; causa `[HIPÓTESE forte]` | `logcat-full.txt:128580` |
| Com o processo morto, o GATT fechou e o heartbeat nunca começou: o ensaio de 5 min **não aconteceu**; é também uma queda de app sem heartbeat | `[CONFIRMADO]` | idem |
Consequência: bug de UI (Home com transporte real) bloqueia a 4.3b. Corrigir com TDD antes de repetir. Estado do bloqueio do patinete após a queda: ver painel (`[DESCONHECIDO]` até o proprietário informar).
