# S43 — Velocidade do patinete saturada (~26 km/h)

Relato do proprietário (APK 085f723): o patinete passa de 50 km/h e o velocímetro "patinete" trava em ~26.

## Achados
- [ESTÁTICO] `TelemetryDecoder` calcula `((d[8]&3)<<8 | d[4]) / 10` (S29:66, `BleViewModel.java:443-452`).
- [CONFIRMADO] A escala 0,1 km/h concorda com o painel original em dois pontos, até 19,8 km/h (S30:22). [HIPÓTESE] Que ela valha até 25,5 km/h (byte único) é extrapolação do decoder estático, não observação.
- [ESTÁTICO] Nenhuma captura em `research/captures/` passa de d[4]=198 (19,8 km/h); `d[8]` é 0 em todas.
- [HIPÓTESE] Acima de 25,5 km/h `d[4]` satura em 0xFF e o ~26 exibido é 25,5 saturado.
- [HIPÓTESE] O campo de alta velocidade não está em `d[8]&3`, ou a escala difere acima do ponto confirmado.
- [DESCONHECIDO] Em qual byte/escala está a velocidade acima de 25,5 km/h.

## Decisão (S43, segurança de exibição)
- `scooterSpeedKmh = null` ("--", arco vazio) quando `d[4] >= 0xF0` (24,0 km/h) ou `d[8] != 0` (nunca capturado), além das condições do adendo S42.
- 0xF0 é margem conservadora [HIPÓTESE] antes da saturação; 0xEF (23,9) ainda é exibido.
- `wheelMoving`, `speedKmh` e travas inalterados (ADR 0005 adendo S41).

## Pendente
- Teste passivo, SOMENTE leitura, com a roda no cavalete (sem comandos): registrar d[4], d[8] e telas do app original
  subindo a velocidade; só o coordenador/proprietário toca o patinete.

## Resultado do teste no cavalete (S44, 2026-11-11, 11:37–11:41) — resolve o pendente
Captura passiva (nenhum comando enviado): `research/captures/s43-velocidade/logcat.txt` (ignorada pelo git), 250 quadros Meter1 (CMD 0x20).
- [CONFIRMADO] A velocidade acima de 25,5 km/h está em `d[8]` bit 0: `((d[8]&3)<<8 | d[4]) / 10`. `d[4]` NÃO satura em 0xFF: dá a volta em 256 enquanto `d[8]` passa de 0 para 1 (11:39:04 `d4=191,d8=0` = 19,1; 11:39:05 `d4=6,d8=1` = 26,2). A descida refaz o caminho (11:39:25 `d4=1,d8=1` = 25,7; 11:39:26 `d4=236,d8=0` = 23,6). Curva contínua, sem saltos.
- [CONFIRMADO] Platô de 7 s com `d4=244,d8=1` = 50,0 km/h; o proprietário leu **50 km/h no painel** no máximo (declaração em chat, 2026-11-11). `d[9]` foi sempre 0.
- [CONFIRMADO] O app com a guarda S43 mostrava "--" acima de ~26 km/h (relato do proprietário), coerente com a guarda `d[4] >= 0xF0`/`d[8] != 0`.
- A comparação com o painel foi feita só no máximo (50 km/h). [HIPÓTESE] A correspondência dos demais pontos (o proprietário não consegue ler o painel e operar o celular juntos); suporte: [ESTÁTICO] decoder + curva contínua.
- [DESCONHECIDO] `d[8]` = 2 ou 3 (≥ 51,2 km/h): nunca visto; segue desconhecida ("--").
- A hipótese S43 de saturação em 0xFF foi REFUTADA; a guarda `0xF0` escondia leituras válidas (24–25,5 km/h, e tudo acima).

## Decisão S44
`scooterSpeedKmh = null` só se `d[8] > 1` ou painel em mph (`d[10]` bit 3). Guarda `0xF0` removida.
