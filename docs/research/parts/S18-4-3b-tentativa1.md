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

## Tentativa 2 (após merge da correção do crash; `main` com home-sem-modos) — `research/captures/s4-3b/ix7ble-3.txt`, `ix7ble-4.txt` (privados)
Mesmas condições (patinete ligado/parado/cavalete, proprietário presente, original fechado), log temporário `IX7BLE` (revertido depois, `grep IX7BLE app/` = 0).
| Fato | Rótulo | Evidência |
|---|---|---|
| `CONNECT` 1× (13:01:53) com ACK; depois **314 heartbeats `0x10` em 5 min 14 s, 314 ACKs**, nenhum intervalo > 2 s; **0 bytes TX fora de Connect/Heartbeat**; sem crash | `[CONFIRMADO]` | `ix7ble-3.txt` |
| Durante toda a janela: 464 quadros Meter1 idênticos (dado[1]=0x00 → desbloqueado; bateria 100 %; limite 50 km/h) e 464 Meter2 | `[CONFIRMADO]` os bytes; mapeamento `[ESTÁTICO]`/`[HIPÓTESE]` | idem |
| UI real nesta etapa: card de modo escondido (decisão), Farol "recusou o comando" (`send()` Rejected por desenho), demais controles desabilitados | `[CONFIRMADO]` (relato do proprietário) | proprietário |
| Ensaio do bloqueio (plano §7.3 passo 4): app fechado (`force-stop`) às 13:08:04 → heartbeat parou; **painel NÃO mostrou cadeado** (proprietário) e, ~1 min 37 s depois, reabri só com CCCD (sem Connect/heartbeat): 53 Meter1 em ~31 s, todos dado[1]=0x00 (desbloqueado); nenhum TX | `[CONFIRMADO]` | `ix7ble-4.txt`, relato |
| Conclusão sobre a hipótese do bloqueio pós-4.2: **fechar o app e ~100 s sem heartbeat NÃO bloquearam**; a causa do bloqueio da sessão 17 segue `[DESCONHECIDO]` (outros fatores: tempo maior sem heartbeat, desligamento automático ~10 min, interação com o app original) | `[CONFIRMADO]` o resultado negativo; causa `[DESCONHECIDO]` | idem |
| A tentativa 2b fechou o GATT (`disconnect()` 13:10:35) ~38 s após a 1ª telemetria sem ação documentada nossa; causa `[DESCONHECIDO]` (possível toque/fluxo do app) | `[DESCONHECIDO]` | `logcat-full-3.txt` |
Pedido do proprietário (a implementar): mostrar o card de modo **desabilitado** em vez de escondido quando não há modos.
