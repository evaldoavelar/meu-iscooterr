# S30 — validação do Meter2 contra a tela do app original

Data: 2026-11-06, ~17:27 (hora do celular). Captura passiva privada: `research/captures/s30-potencia/logcat.txt` (ignorada pelo git; log `IX7TAP` do original reempacotado). Patinete no cavalete, teste feito pelo proprietário no app original; esta sessão só escutou.

| Campo | Tela "Details" do original | Quadros 0x21 (17:27:01–04) | Classe |
|---|---|---|---|
| Tensão | 54,4 V | 54,4 V | `[CONFIRMADO]` |
| Corrente | 0,6 A | 0,6 A | `[CONFIRMADO]` |
| Odômetro total | 24,00 km | 24 | `[CONFIRMADO]` |
| Temp. motor 1 | 23 °C | 23 | `[CONFIRMADO]` |
| Temp. motor 2 | `---` (protocolo 1.03 < 1.05) | ignorado | coerente com o decompilado |
| Potência | **sem linha na tela** | 32,6 W (V·I = 32,64) | `[HIPÓTESE forte]`, sem referência externa |

Outros: "Remaining power 100%" = bateria, "Current speed 20 km/h" com a roda no ar (não investigado). Quadros com corrente ≠ 0 em 17:27:01–05 e 17:27:58 (0,2–0,8 A; P = V·I em todos).
Conclusão: a potência só pode ser validada por medição externa; no app novo exibir como estimada ou omitir.
Observação: o original enviou nesta janela, além de CONNECT/heartbeat, os comandos 0x3b, 0x41, 0x61, 0x62 e 0x63 (ações do proprietário; não enviados pelo app novo).

## Velocidade (Meter1 bytes[9..11] e byte[13], escala km/h = (((byte[13] & 0x03) << 8) | byte[9]) / 10f)

[ESTÁTICO] A análise decompilada em S29 (`BleViewModel.java:443-452`, `docs/research/parts/S29-meter2-decodificacao.md`:66) comprovou que a velocidade atual é composta por 10 bits: os 8 bits de `dado[4]` (`bytes[9]`) como parte baixa e os bits 0..1 de `dado[8]` (`bytes[13]`) como parte alta: `((dado[8] & 0x03) << 8) | dado[4]`, multiplicados pela escala de 0,1 km/h. Não há teto aparente em 25,5 km/h (o campo de 10 bits alcança até 102,3 km/h). `dado[5]` (`bytes[10]`) é velocidade média e `dado[6]` (`bytes[11]`) é tempo de carga.

[CONFIRMADO] Nas capturas desta sessão com a roda no ar (2 motores), `bytes[13]` permaneceu 0 em todos os quadros (velocidades ≤ 25,5 km/h). No segundo print do proprietário (17:29): 7 km/h, 0,0 A, 54,4 V no app original; o log mostra `byte[9]` subindo até 84 (8,4 km/h) em 17:29:40–45 e transitando por ~66–84 (~6,6–8,4 km/h) na janela do print. No primeiro print (17:27): 20 km/h na tela; `byte[9]` atingiu 198 (19,8 km/h) em 17:27:04. A escala de 0,1 km/h sobre a composição de 10 bits concorda nos dois pontos medidos: promove a escala de `[HIPÓTESE forte]` para `[CONFIRMADO]` (2 pontos, arredondamento da tela; instante exato do print desconhecido).

Nota de interoperabilidade e segurança (S41): o `TelemetryDecoder` mantém intencionalmente `speedKmh = null` quando em movimento, por segurança e aderência ao ADR 0005 (adendo S41).
