# S21 — Teste real do bloqueio (LOCK modo 2) pelo app novo, 2026-11-05

APK limpo da `main` @ 1385d17 (sem log temporário), `--ez ble_real true --ez ble_tx true`, adb por Wi-Fi.

| Achado | Rótulo | Evidência |
|---|---|---|
| Sem `ble_tx` o app só escuta: `lock()`/`unlock()` retornam `Refused(TxDisabled)` antes de escrever | `[ESTÁTICO]` | `BleScooterTransport.kt:171,237` |
| Patinete bloqueou sozinho durante a escuta pura (sem CONNECT/heartbeat), sem toque do proprietário | `[HIPÓTESE]` (coerente com a sessão 17) | relato do proprietário + foto do painel |
| Com `ble_tx` (CONNECT+heartbeat) a telemetria volta e o proprietário desbloqueou pelo app novo (PIN certo) | `[CONFIRMADO]` | tela do app; 4.4 repetida |
| **LOCK modo 2 (sem PIN) com patinete tendo PIN ativo: aceito; o app mostrou "Desbloquear" (Meter1 com bit5=1 recebido ≤ 3 s)**; proprietário confirmou "Bloqueado" | `[CONFIRMADO]` | tela do app 20:03 + relato; sem captura em ms |
| Telemetria além da bateria (corrente, tensão, temperaturas, consumo) segue "--": Meter2 sem decodificação | `[HIPÓTESE]`/`[DESCONHECIDO]` | S17/S19 |

Desvios de processo: o proprietário desbloqueou pelo app novo antes do "pode" (3.º desvio do tipo). O "pode" do bloqueio foi dado no chat antes do toque; uma tentativa.

Defeitos abertos: Home presa em "Desconectado" com "Procurar patinete" desabilitado após queda; mensagem "recusou o comando" no Farol (transporte real não implementa Farol/Tração/Cruise) e mensagem enganosa de `TxDisabled`.
