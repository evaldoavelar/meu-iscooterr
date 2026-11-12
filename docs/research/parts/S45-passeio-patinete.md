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
