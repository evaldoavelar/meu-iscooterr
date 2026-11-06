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

## Velocidade (Meter1 bytes[9..11], escala km/h = byte[9]/10)
Segundo print do proprietário (17:29, roda no ar, 2 motores): 7 km/h, 0,0 A, 54,4 V. Log: byte[9] sobe até 84 (8,4 km/h) em 17:29:40–45 e passa por ~66–84 perto de 7 km/h; corrente 0 nessa janela. Primeiro print (17:27): tela 20 km/h; byte[9] chegou a 198 (19,8 km/h) em 17:27:04. A escala byte[9]/10 km/h concorda nos dois pontos: promove de `[HIPÓTESE forte]` para `[CONFIRMADO]` (2 pontos, arredondamento da tela; instante exato do print desconhecido).
