# ADR 0005 — Separar movimento da roda de velocidade em km/h

Status: implementado pelo autor; aguarda revisão independente · Data: 2026-11-05

[CONFIRMADO] Na captura S19, Meter1 bytes[9..11] (`dado[4..6]`) todos zero correspondem a roda parada; byte[9] não zero acompanha movimento, inclusive `0x01` ao girar à mão. Referência: `docs/research/parts/S19-velocidade-captura.md`; `research/captures/s19-speed/ix7tap-speed.txt`:6,544,561,950,1063.

[HIPÓTESE] A escala `byte[9]/10` é forte, mas não calibrada. [DESCONHECIDO] Composição/escala dos bytes[10..11], que não variaram no ensaio. Não atribuir km/h a nenhum valor não zero.

[ESTÁTICO] Decisão: acrescentar `Telemetry.wheelMoving: Boolean?` (sem unidade; `null` = desconhecido), em vez de armazenar um número fictício em `speedKmh`. O decoder publica `false` e `speedKmh = 0f` quando os três bytes são zero; qualquer byte não zero publica `true` e `speedKmh = null`. Para bytes[10..11] não zero, isso é uma guarda conservadora, sem alegar observação física desses valores. Referências: `Protocol.kt:Telemetry`, `TelemetryDecoder.meter1`.

[ESTÁTICO] O acumulador deve substituir o zero anterior por desconhecido ao receber movimento e preservar movimento quando chega Meter2. Home e Unlock devem verificar movimento antes de exigir km/h conhecido. O parâmetro injetável `BleScooterTransport.speedKmh` permanece para os testes existentes; movimento observado na roda tem precedência sobre um zero injetado. Referências: `Telemetry.overlay`, `AgedTelemetry.receive`, `HomeReducer`, `BleScooterTransport.unlockRefusal`.

[ESTÁTICO] Os mappers numéricos continuam consumindo somente `speedKmh`: movimento da roda não fornece um número para exibir. Nenhuma mudança no velocímetro/GPS é necessária neste escopo; a decisão do proprietário de usar GPS permanece. Não se introduz um sentinela numérico que possa aparecer como km/h. Referências: `HomeUiMapper.map`, `TelemetryUiMapper.map`.
