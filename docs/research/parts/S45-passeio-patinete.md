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
