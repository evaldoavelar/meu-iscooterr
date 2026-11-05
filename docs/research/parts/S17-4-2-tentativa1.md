# Etapa 4.2 — tentativa 1 (2026-11-05, sessão 17)
Autorizada pelo proprietário (patinete ligado, parado no cavalete, app original fechado). Só GATT connect; nenhum CCCD/comando chegou a ser escrito. Evidência: `research/captures/s4-2/logcat-full.txt` (privado, ignorado pelo git).

| Fato | Rótulo | Evidência |
|---|---|---|
| Anúncio `ix7Pro` só aparece com o patinete ligado; MAC final `…13:7A` | `[HIPÓTESE forte]` → `[CONFIRMADO]` só como identidade do dispositivo | escaneamento no PC com off/on; o stack do Android registra `name:ix7Pro` para o mesmo endereço (`btm_sec_disconnected`) e o scan do celular o vê (rssi −71 a −80) |
| O app novo (`BleScooterTransport`) iniciou `clientConnect` direto (LE, addressType público) | `[CONFIRMADO]` | logcat 09:49:57.658 e 09:50:23.277 |
| Link LE estabelece (conn_interval=24) e **cai ~150 ms depois** com motivo HCI `0x3E` CONNECTION_FAILED_ESTABLISHMENT → `GATT_ERROR (133)`; ocorreu 2× | `[CONFIRMADO]` | 09:49:59.945→09:50:00.098 e 09:50:23.499→09:50:23.648 |
| Descoberta de serviços, MTU, CCCD, telemetria | `[DESCONHECIDO]` | não chegaram a acontecer; **FF60 continua `[ESTÁTICO]`** |
| Causa do 0x3E (patinete já com outra conexão? parâmetros de conexão? exige pareamento? sinal fraco?) | `[DESCONHECIDO]` | nenhuma hipótese testada; app original não estava aberto |
Conforme o plano (4.2): sem conexão/telemetria → parar, documentar e perguntar; nenhuma nova tentativa nem `0x30`/heartbeat feitos.

## Tentativa 2 (após desinstalar o app original e religar o patinete) — evidência `research/captures/s4-2b/logcat-full.txt`
| Fato | Rótulo | Evidência |
|---|---|---|
| Conexão GATT em 0,52 s; o 0x3E da tentativa 1 não se repetiu (app original desinstalado + patinete religado) | `[CONFIRMADO]` o resultado; causa anterior `[HIPÓTESE]` (conexão concorrente/presa) | 09:54:08.695 → 09:54:09.211 |
| O patinete pede MTU 255; resultado MTU 255 (pedimos 247) | `[CONFIRMADO]` | `gatts_process_mtu_req` / `onConfigureMTU(…,255,0)` |
| Descoberta de serviços concluída em ~0,57 s; escrita do CCCD de FF61 às 09:54:09.797 (única escrita) | `[CONFIRMADO]` (FF61 notificável); FF60 segue `[ESTÁTICO]` (o log não nomeia o serviço) | `onSearchComplete(...,0)`, `setCharacteristicNotification 0000ff61`, `btif_gattc_write_char_descr` |
| **Telemetria chega sem CONNECT 0x30/heartbeat**: 1ª notificação 0,2 s após o CCCD (~1,3 s após iniciar), 26 notificações em ~8,7 s (~300 ms) | `[CONFIRMADO]` (existência e cadência); conteúdo **não verificado** (o log não traz os bytes) | 09:54:10.000… |
| O app encerrou a conexão local aos 8,7 s (HCI reason 0x16, "terminada pelo host local") | `[CONFIRMADO]` o fato; causa `[DESCONHECIDO]` | `on_le_disconnect` 09:54:17.909 |
| A tela mostrou 82 %, 1.234 km, Farol ligado: **idênticos aos valores do Fake**; decodificação real vs. tela NÃO confirmada | `[DESCONHECIDO]` | `ui-45s.png`; 4.2 exige bater com o painel do patinete |

## Tentativa 3 (log temporário `IX7BLE` com os bytes de FF61) — `research/captures/s4-2d/`
Escopo idêntico (só GATT + CCCD de FF61). Conexão 09:59:28.07 → MTU 255 → CCCD → 1ª notificação 09:59:29.70; o app mostrou "conectado" (primeiro uso concluído) e as notificações seguiram por ≥ 34 s sem queda (até a leitura do log).

| Fato | Rótulo | Evidência |
|---|---|---|
| Os quadros reais têm o formato documentado: `1A A1 · CMD · 03 · LEN 18 · 18 B · CRC · 1F F1` (27 B), alternando CMD 0x20 (Meter1) e 0x21 (Meter2), ~300 ms cada, **sem CONNECT 0x30/heartbeat** | `[CONFIRMADO]` | `rx.txt` |
| Meter1 (0x20) é constante: `00 00 00 64 00 00 00 3c 00 00 00 00 23 23 23 02 03 08` → pelas posições da S2: bateria dado[3]=0x64=**100 %**, limite dado[7]=0x3c=**60 km/h**, bit de bloqueio = 0 (**desbloqueado**) | `[ESTÁTICO]` do mapeamento; a leitura **precisa ser conferida com o painel** do patinete | `rx.txt` |
| Meter2 (0x21) varia entre amostras (dado[2] 0x90→0xa4; dado[12..] 0x15/0x17/0x18) sem campo confirmado | `[DESCONHECIDO]` (significado) | `rx.txt` |
| A queda aos 8,7 s da tentativa 2 não se repetiu; causa `[DESCONHECIDO]` (hipótese: toque/ação durante o fluxo; prazo de 10 s do 1º uso não explica esta vez) | `[DESCONHECIDO]` | logcat s4-2b vs s4-2d |
| FF60 | `[ESTÁTICO]` (o app exige FF60 contendo FF61/FF62 para chegar a "conectado": missingParts) → **conectado implica o serviço FF60 presente** | `BleScooterTransport.missingParts` + UI "conectado" |
Pendente: o proprietário confirmar no painel do patinete (bateria 100 %? limite 60? desbloqueado?) — critério final da 4.2.

## Painel vs. bytes (foto do painel, proprietário) e bloqueio posterior
- Painel (parado no cavalete): 0 km/h, modo Eco, ícone 4WD, **ícone de bateria vermelho com raio**, ODO ≈ 23 km (leitura da foto, a confirmar). A foto **não contradiz** bateria 100 % (`dado[3]=0x64`, `[CONFIRMADO]` na S2); o ícone vermelho com raio pode ser indicação de carga (`[HIPÓTESE]`, perguntar se o carregador estava ligado). Limite: 0x3c = 60 km/h (na S2 era 0x32 = 50; foi alterado entre sessões `[HIPÓTESE]`).
- Meter2 (`[HIPÓTESE]`, só pelos bytes): `u16 little-endian` em dado[2..3] = 5520 e 5540 → 55,20/55,40 V (plausível para pack ~13S, tensão subindo é coerente com carga); dado[8]=0x17 (23) fixo e dado[14] 0x17→0x18 (23→24) podem ser temperaturas em °C; dado[15]=0x15 e dado[12..14] de Meter1 = 0x23 `[DESCONHECIDO]`.
- **Bloqueio observado depois da captura** (foto: cadeado): o app só escreve o CCCD (código: sem `writeCharacteristic`; `send()` = Rejected; log do período 09:59:17–10:00:04 só com o CCCD). Hipótese do proprietário: efeito colateral do fechamento do app / perda do enlace (`[HIPÓTESE]`, não verificada; o logcat posterior foi sobrescrito). Para testar sem risco: repetir a conexão, fechar o app e registrar `lock_status` (Meter1 dado[1] bit5) antes/depois, com logcat salvo.

## Captura do app original reempacotado (desbloqueio) — `research/captures/s4-3/ix7tap.txt` (privado; PIN mascarado na origem)
Sessão 10:12:12 → 10:15:56 (3 min 43 s), proprietário usando o app original; nós só lemos o log.
| Fato | Rótulo | Evidência |
|---|---|---|
| Ao conectar, o original envia `CONNECT 0x30` (TIPO 02, LEN 1, valor 1; ACK tipo 05 em ~110 ms; enviado 2×) e consultas `0x61` e `0x62` (3×; `0x62` responde LEN 2 `14 32` = 20/50?) | `[CONFIRMADO]` | linhas 10:12:13.944–10:12:15.117 |
| **Heartbeat `0x10` TIPO 01 LEN 0 a cada ~1,0 s** (219 envios em 224 s; mediana 1,01 s; ACK tipo 05 a cada um) durante toda a conexão | `[CONFIRMADO]` | `TX 1aa110010071951ff1` |
| Meter1 `dado[1]` = **0x30** no início (bit5 bloqueado + bit4 `lock_has_pwd`) → **o patinete agora TEM senha** (corrige "sem PIN" da S2); após `TX 0x32 TIPO 02 LEN 5` (modo + 4 dígitos, mascarado) às 10:15:28.689 e ACK tipo 05 em 49 ms, `dado[1]`=0x00 em 10:15:29.353 (~660 ms depois) | `[CONFIRMADO]` | `lock_data_len=5` |
| O nosso app (4.2) **não envia** `CONNECT 0x30` nem heartbeat `0x10`; ficou lendo telemetria | `[CONFIRMADO]` | código + logs s4-2d |
| Hipótese: sem heartbeat/CONNECT, ou ao encerrar o enlace, o patinete **bloqueia sozinho** (por senha, já que há PIN) | `[HIPÓTESE]` (sustentada pelo proprietário; não testada isoladamente) | bloqueio pós-4.2 + ausência de heartbeat |
Consequência: 4.3/4.4 precisam enviar `CONNECT 0x30` e heartbeat 1 s (comandos) — exigem aprovação específica (regra 6), só parado no cavalete; desbloqueio exige o PIN do proprietário (ADR 0002), nunca definido/alterado por nós.
