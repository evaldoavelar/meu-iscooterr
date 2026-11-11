# ADR 0005 — Separar movimento da roda de velocidade em km/h

Status: implementado pelo autor; aguarda revisão independente · Data: 2026-11-05

[CONFIRMADO] Na captura S19, Meter1 bytes[9..11] (`dado[4..6]`) todos zero correspondem a roda parada; byte[9] não zero acompanha movimento, inclusive `0x01` ao girar à mão. Referência: `docs/research/parts/S19-velocidade-captura.md`; `research/captures/s19-speed/ix7tap-speed.txt`:6,544,561,950,1063.

[HIPÓTESE] A escala `byte[9]/10` é forte, mas não calibrada. [DESCONHECIDO] Composição/escala dos bytes[10..11], que não variaram no ensaio. Não atribuir km/h a nenhum valor não zero.

[ESTÁTICO] Decisão: acrescentar `Telemetry.wheelMoving: Boolean?` (sem unidade; `null` = desconhecido), em vez de armazenar um número fictício em `speedKmh`. O decoder publica `false` e `speedKmh = 0f` quando os três bytes são zero; qualquer byte não zero publica `true` e `speedKmh = null`. Para bytes[10..11] não zero, isso é uma guarda conservadora, sem alegar observação física desses valores. Referências: `Protocol.kt:Telemetry`, `TelemetryDecoder.meter1`.

[ESTÁTICO] O acumulador deve substituir o zero anterior por desconhecido ao receber movimento e preservar movimento quando chega Meter2. Home e Unlock devem verificar movimento antes de exigir km/h conhecido. O parâmetro injetável `BleScooterTransport.speedKmh` permanece para os testes existentes; movimento observado na roda tem precedência sobre um zero injetado. Referências: `Telemetry.overlay`, `AgedTelemetry.receive`, `HomeReducer`, `BleScooterTransport.unlockRefusal`.

[ESTÁTICO] Os mappers numéricos continuam consumindo somente `speedKmh`: movimento da roda não fornece um número para exibir. Nenhuma mudança no velocímetro/GPS é necessária neste escopo; a decisão do proprietário de usar GPS permanece. Não se introduz um sentinela numérico que possa aparecer como km/h. Referências: `HomeUiMapper.map`, `TelemetryUiMapper.map`.

## Adendo S41 (2026-11-10) — Divergência intencional entre S30 e TelemetryDecoder

[CONFIRMADO] Em S30 (`docs/research/parts/S30-meter2-validacao.md`), a correlação entre a tela do app original e a telemetria do Meter1 promoveu a escala de velocidade a `[CONFIRMADO]` para os pontos observados. Em S29 (`docs/research/parts/S29-meter2-decodificacao.md`:66), a análise estática (`BleViewModel.java:443-452`) comprovou que a velocidade é um valor de 10 bits: `((dado[8] & 0x03) << 8) | dado[4]`, com escala de 0,1 km/h e sem teto aparente em 25,5 km/h `[ESTÁTICO]`.

[ESTÁTICO] Decisão de segurança (S41-F48-FIX): o `TelemetryDecoder` mantém intencionalmente a divergência em relação a S30, preservando o contrato conservador deste ADR. Qualquer valor de velocidade não zero (seja em `dado[4]` ou nos bits altos `dado[8] & 0x03`) produz `wheelMoving = true` e `speedKmh = null` (velocidade numérica desconhecida). Apenas quando todos os componentes de velocidade forem zero (`dado[4] == 0 && (dado[8] & 0x03) == 0`), o decoder emite `wheelMoving = false` e `speedKmh = 0f`. `dado[5]` (velocidade média) e `dado[6]` (tempo de carga) são campos distintos e não são adotados como prova de movimento. Esta divergência é deliberada para garantir segurança crítica: na dúvida = EM MOVIMENTO; controles do patinete e travas de segurança nunca dependem de uma estimativa não calibrada de velocidade em km/h via BLE, mantendo o GPS como fonte para exibição velocimétrica.

## Adendo S42 (2026-11-10) — Velocidade do patinete exibida (só exibição)

[CONFIRMADO] A escala `(((dado[8] & 0x03) << 8) | dado[4]) / 10` está confirmada em S30 (dois pontos contra a tela do app original), apenas no intervalo com `dado[8] = 0` (até 25,5 km/h); os bits altos (acima disso) são só `[ESTÁTICO]` (S29:66). Passa a ser EXIBIDA no velocímetro da Telemetria como fonte "patinete" (padrão), alternável por gesto horizontal com o GPS (preferência `speedSource`, ausência = Scooter).

[ESTÁTICO] Decisão de projeto (não evidência): novo campo `Telemetry.scooterSpeedKmh: Float?`, preenchido só pelo decoder do Meter1. Lado seguro: `null` (exibido "--", arco vazio) quando `dado[8] & 0xFC != 0` (bits de velocidade média/tempo de carga com semântica `[DESCONHECIDO]`; decisão conservadora), quando o painel está em mph (`dado[10]` b3: S29:66 diz "km/h ou o que o painel mostrar; mph não converte no DTO", a unidade do valor é `[DESCONHECIDO]`; evita conversão dupla) e quando o Meter1 está velho; frame com payload fora de 18 bytes nem é decodificado. Nunca se inventa valor.

[ESTÁTICO] NÃO muda nenhuma decisão de segurança: `wheelMoving` e `speedKmh` (zero/desconhecido) seguem exatamente como no adendo S41; travas, desbloqueio, Air Lock e demais comandos continuam só em `wheelMoving` + Meter1 fresco e nunca leem `scooterSpeedKmh`. Distância, velocidade média e máxima da viagem continuam vindas do GPS (`TripTracker` inalterado); o consumo (Wh/km) segue a velocidade do GPS.

## Adendo S43 (2026-11-11) — Velocidade >24 km/h exibida como desconhecida

[ESTÁTICO] Evidência: nenhuma captura passa de 19,8 km/h (d[4]=198) e `d[8]` é 0 em todas. O proprietário observou o patinete acima de 50 km/h com o velocímetro "patinete" travado em ~26 km/h. [HIPÓTESE] `d[4]` satura em 0xFF (25,5 km/h) e o campo de alta velocidade não está em `d[8]&3` (ou a escala difere). [DESCONHECIDO] onde está a velocidade acima de 25,5 km/h.

[ESTÁTICO] Decisão de projeto (não evidência): `scooterSpeedKmh = null` quando `d[4] >= 0xF0` (24,0 km/h, margem conservadora antes da saturação) ou `d[8] != 0` (bits altos nunca capturados), além das condições do adendo S42. `wheelMoving`, `speedKmh` e travas seguem como no adendo S41. Teste passivo (só leitura, roda no cavalete) pendente: `docs/research/parts/S43-velocidade-saturada.md`.

## Adendo S44 (2026-11-11) — Velocidade acima de 25,5 km/h [CONFIRMADO]
Teste no cavalete (só leitura): `d[4]` dá a volta em 256 e `d[8]` bit 0 sobe, sem saturação `[CONFIRMADO]`. A fórmula `((d[8]&3)<<8 | d[4]) / 10` foi comparada ao painel só no pico (50,0 = 50 km/h) `[CONFIRMADO]`; os pontos intermediários têm suporte estático e curva contínua `[ESTÁTICO]`/`[HIPÓTESE]`. O adendo S43 (guarda `d[4] >= 0xF0`) foi baseado em hipótese refutada e é **substituído**: `scooterSpeedKmh = null` apenas com `d[8] > 1` (nunca capturado) ou painel em mph. Evidência e sequência: `docs/research/parts/S43-velocidade-saturada.md`.
