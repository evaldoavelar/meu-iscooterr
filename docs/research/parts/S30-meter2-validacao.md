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
