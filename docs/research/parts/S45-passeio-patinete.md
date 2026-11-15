# Passeio de patinete (S45–S47) — registro de captura

Estado: **[DESCONHECIDO]** velocidade >25,5 km/h, trajeto/altimetria reais, pico de potência V×I e GPS em segundo plano com a tela apagada: **sem dados**, o passeio com o patinete ainda não ocorreu.

## O que se tentou (2026-11-11)
- Captura do PC (`research/captures/s45-patinete/logcat.txt`) caiu às 17:29:07 com o Wi-Fi; ao reconectar (17:29:49) veio só replay do buffer do sistema (sem linhas do app depois de 17:27). [CONFIRMADO]
- Log do celular (`/data/local/tmp/s46`) cobre 15:03–17:09:57; parou provavelmente na reinstalação do APK (mesma causa da S45). [HIPÓTESE]
- Dados do app (`run-as`): `trips.v1` com 2 viagens antigas de 0,0 km (220 s e 50 s, cavalete) e uma rota de 10/11; nada novo. [CONFIRMADO]
- Entradas do sistema mostram o processo do app **congelado** (`OomAdjuster ... frozen status`) em segundo plano entre 17:27 e 18:29. [ESTÁTICO] Relevante para o P3 do serviço em primeiro plano: não verificado se o serviço de localização estava ativo. [DESCONHECIDO]

## Teste a pé (2026-11-11, ~18:27)
- O proprietário andou a pé com a viagem em "Contando", sem patinete conectado. Resultado: duração 00:00, nada gravado; telemetria "Dados desatualizados". [CONFIRMADO]
- Explicação no código: `tripSession.onTelemetry` só roda com quadro do patinete (`HomeViewModel.kt`); o relógio da viagem começa na 1ª amostra; `closeTrip` só salva com `durationMs > 0`, então o Parar descarta também o trajeto GPS. [ESTÁTICO]
- Resposta do app (S47): "Contando" em vermelho e toast "Patinete não conectado" ao dar Play sem telemetria fresca. Contar por relógio/GPS do celular: decisão do proprietário pendente.

## Para o próximo passeio
Antes de sair: instalar o APK final, iniciar captura do PC e do celular **depois** da última instalação; manter o app em primeiro plano ou confirmar o serviço de localização; conferir `adb` por mDNS ao voltar.

## Passeio S48 (2026-11-12, ~12:58–13:07, patinete conectado) — viagem NÃO registrou velocidade/distância
Dados: `research/captures/s48-patinete/` (ignorado): `app/files.tar` (trips.v1, routes), `phone/buffer-13h.txt` (buffer do sistema, só 13:06:04–13:11:32), `phone/s48b/log.txt` (logcat 10:00–10:10).
- **Log perdido [CONFIRMADO]:** o logcat que iniciei por adb (`setsid nohup … -f`) MORREU às 10:10 (adb sem fio caiu); o passeio (12:58–13:07) só tem o buffer circular do sistema, que cobre apenas o último minuto (13:06+). Só 1 linha `IX7GPS` (13:07:09, `spd=0.0`, acc 25,9 m) e `unsubscribe`; 1 950 linhas `IX7TX` (13:06–13:11). Lição: logcat por adb não sobrevive; o log precisa ser gravado PELO APP em arquivo.
- **Viagem 4 [CONFIRMADO]** (`trips.v1`): 492 695 ms (8:12), distância 0,0, média 0,0, máx. 0,0, bateria 77→73 %, modo Walking 0–147 s depois Eco; rota com 5 pontos (≈0,5 km em linha entre os pontos 2→3→4→5, ida e volta ao ponto de partida).
- **Causa [ESTÁTICO]:** `TelemetryDecoder` entrega `speedKmh = null` quando a roda gira (`wheelMoving`) e a viagem integra SÓ a velocidade do GPS do celular (`withGpsSpeed`; `TripTracker` descarta amostra `null`: distância/máx./média ficam 0 enquanto o GPS não entrega). A velocidade do patinete (`scooterSpeedKmh`, [CONFIRMADO] S44) não entra na viagem. Duração conta porque amostras com a roda parada (0) existem. Se o GPS entregou velocidade durante a corrida: [DESCONHECIDO] (sem `IX7GPS` do trecho).
- Último minuto (13:06, buffer): roda girando em 31 de 482 quadros Meter1, máx. 2,3 km/h (manobra de chegada) [CONFIRMADO]; nada do trecho em velocidade.
- **Altimetria [ESTÁTICO]:** o app nunca coleta altitude (a rota guarda só lat/lon); "Sem leitura" é esperado.
- **Mapa [ESTÁTICO]:** sem zoom/gesto (pedido do proprietário).

## Passeio S49 (2026-11-12, 17:44–17:51, patinete conectado, APK com log em arquivo)
Dados: `research/captures/s49-patinete/` (ignorado): `ix7-0.log` (3 528 linhas: 24 `IX7GPS`, 3 500 `IX7TX`, 4 `IX7APP`), `files.tar` (routes, trips.v1).
- **Movimento [CONFIRMADO]** (Meter1 CMD 0x20, `((d[8]&3)<<8|d[4])/10`): em movimento 17:44–17:51; pico **29,9 km/h** (17:48); distância integrada ≈ **987 m**; bateria 75→70 %. A viagem salva (1,0 km, média 8, máx. 30 km/h) bate: o fallback `Telemetry.forTrip` (velocidade do patinete) funcionou.
- **GPS com a tela apagada [CONFIRMADO]:** só 24 fixes (ritmo de 2 s, tela ligada). Buracos: 17:44:43→17:45:50 (67 s) e **17:45:58→17:50:09 (251 s)**, exatamente quando o patinete fez 26–30 km/h. Ao acender a tela (17:50:09) os fixes voltaram na hora. Distância da rota por GPS ≈ 84 m contra ≈ 987 m reais.
- **Causa do GPS parar [HIPÓTESE]:** sem `ACCESS_BACKGROUND_LOCATION` (appop FINE_LOCATION = foreground), app fora da lista de exceção de bateria (`deviceidle whitelist` = 0), possível suspensão pelo Samsung; não se sabe se o serviço `location` iniciou ([DESCONHECIDO], app não logava). Ramo `task/s49-svclog` grava `IX7SVC` para decidir.
- **Altimetria ao vivo [ESTÁTICO]:** `TelemetryUiMapper(altitudesM = emptyList())` nunca é alimentado: cartão "Viagem atual" mostra "Sem leitura"; o histórico lê a rota salva e funciona (+13/−6 m, mín 834, máx 847 m). Ramo `task/s49-livealt`.
- **Rota de S48 (12:58) [CONFIRMADO]:** 5 pontos, saltos de 13/54/202/248 m (retas cortando quarteirões), mesma causa (poucos fixes).
- Potência: pico V×I NÃO analisado nesta sessão [DESCONHECIDO]; teto do gráfico segue 750 W.

## Passeio S51 (2026-11-14 10:07–10:28, patinete conectado, APK debug com `IX7SVC`)
Dados: `research/captures/s51-patinete/` (ignorado): `ix7-0.log` (15 358 linhas, 12/11 14:43 → 14/11 10:28; não há `ix7-1..3`), `x/files/` (`trips.v1`, `routes/`). Decodificação com script próprio (Meter1 0x20 e Meter2 0x21, mesmas fórmulas do `TelemetryDecoder`), lido com `python3 -I`.
- **BLE [CONFIRMADO]:** 4 tentativas `ConnectFailed` (10:07:32–44, 12 s) e conexão às 10:07:45; sessão até 10:15:30 (`WriteTimedOut`), religou às 10:15:43 (buraco de 14,4 s nos quadros, patinete parado antes e depois); `UserDisconnect` às 10:27:13 e 10:28:51 (ações do dono). 1 820 quadros Meter1 e 1 820 Meter2 (≈1,45 Hz). **Todo o trecho em movimento (10:08:22–10:22:27) está completo**: os dois buracos (10:15:28–43 e 10:27:12–25) ocorreram com a roda parada.
- **Velocidade/distância [CONFIRMADO]** (`((d[8]&3)<<8|d[4])/10`): pico **30,0 km/h** (10:20), 693 quadros em movimento, ≈473 s em movimento, distância integrada **≈1 562 m**. Bateria 73→66 % (mediana 65; afunda a 51–53 % sob carga e volta: leitura por tensão, não por carga).
- **Viagem 6 (`trips.v1`) [CONFIRMADO]:** 1,488 km, máx. 30,0, média 7,04, 761 214 ms, bateria 73→64. Máx. e bateria batem; a distância fica **74 m (−4,7 %)** abaixo da integração do patinete [HIPÓTESE]: amostragem/regra ≤2 s do `Telemetry.forTrip` ou uso da velocidade GPS (0,0–0,4) nos poucos instantes com fix; não investigado.
- **Pausa, não reinício [HIPÓTESE forte]:** `tripRecording=true` em 10:08:25–10:15:07 (402 s) e 10:17:13–10:23:14 (361 s) = 763 s ≈ duração salva (761 s). A viagem foi **pausada** entre 10:15:07 e 10:17:13 (patinete parado, 0 km/h) e parada às 10:23:14. Só 1 quadro >0,5 km/h fora da gravação. O app não loga Play/Pause: causa manual não provada.
- **GPS [CONFIRMADO o fato; causa HIPÓTESE forte]:** só 10 fixes `IX7GPS` (10:08:25–29, 10:15:05, 10:17:13–15). Tela apagada 10:08:30→10:15:04 (395 s) e 10:17:16→10:23:11 (355 s): **zero fixes**, e o primeiro fix volta ~1,2 s após `screen on`. `IX7SVC` mostra o serviço **promovido** com `connectedDevice|location` (`ok`, sem fallback) às 10:08:25 e 10:17:13, `locationGranted=true`, `backgroundRestricted=false`, `standbyBucket=10` (ativo), `deviceIdle=false`, sem `gps provider disabled`. Logo a falta de permissão de background, o tipo do serviço e o Doze **não** explicam. O que sobra: **`powerSave=true` em todas as 10 linhas `state`** (também no S49).
  - **Causa provável:** a Economia de bateria está ligada (`mSettingBatterySaverEnabled=true`, sticky) e a política do aparelho (Android 17, SDK 37) tem **`location_mode=1`** = `LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF` (`adb shell dumpsys power`, 15/11): com tela apagada o sistema para o GNSS para **todos** os apps, sem `onProviderDisabled` (casa com o log). Falta só o teste de controle (viagem com a economia desligada), por isso `[HIPÓTESE forte]`, não `[CONFIRMADO]`. `ignoringBatteryOpt=false` é irrelevante para esse modo.
- **Rota perdida [CONFIRMADO]:** `6.route` (62 B, formato v2) tem **2 pontos** (início 10:08:25 e fix de 10:15:05, ≈393 m em linha reta), nada depois: 1 562 m rodados, rota de ~0,4 km. Os fixes parados de 10:17 caíram no limiar de 10 m/30 m.
- **Potência V×I [CONFIRMADO]:** `dado[0..1]` do Meter2 = V×I a menos de 0,1 W em 1 820 quadros (a "potência" é calculada, sem leitura independente). Máx. **1 284 W** (10:11:31, 47,9 V, 26,8 A); 250 quadros com P>0: **141 acima de 750 W** e 109 acima de 1 000 W (p95 = 1 255 W). **O teto fixo de 750 W do gráfico de consumo está curto**; corrigir para ≥1 300–1 500 W é decisão de UI (mockup antes). Tensão 47,7–50,6 V; corrente máx. 26,8 A; temp. do motor máx. 50 °C; odômetro 35→37 km (resolução 1 km).
- **Descartado como causa:** tipo do serviço, `ACCESS_BACKGROUND_LOCATION`, Doze, standby bucket, restrição de background. **Não analisado:** quanto da diferença de 74 m vem de GPS×patinete; rota por patinete (sem rumo, só distância).
