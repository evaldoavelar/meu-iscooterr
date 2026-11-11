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
