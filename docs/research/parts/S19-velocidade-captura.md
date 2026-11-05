# S19 — Captura passiva de velocidade (Meter1/Meter2), 2026-11-05
Origem: app original reempacotado (logger IX7TAP) conectado, patinete ligado no cavalete; o proprietário usou o acelerador (~15:01:14–31) e girou a roda à mão (~15:01:48–15:02:43; máximo 3 km/h na tela do original). Evidência privada: `research/captures/s19-speed/ix7tap-speed.txt` (ignorada pelo git). Nenhum TX nosso; o PIN não aparece.
| Fato | Rótulo |
|---|---|
| Meter1 byte[9] (`dado[4]`) = 0x00 em 411 quadros com o patinete parado; bytes[10..11] = 00 00 em todo o log | `[CONFIRMADO]` |
| Com acelerador: byte[9] = 0x0b, 0x2d, 0x6e, 0xa7, 0xc2, 0xc6 (máx), 0xb1, 0x84…, volta a 0x02; na mão: 0x0d…0x25 (máx 37) e 0x01 no fim | `[CONFIRMADO]` os bytes |
| "Zero = parado; qualquer valor ≠ 0 = roda em movimento" (resolução de 1 unidade, inclusive em giro lento à mão) | `[CONFIRMADO]` neste ensaio |
| Velocidade em km/h = byte[9] / 10 (truncada): máx na mão 0x25 = 3,7 ↔ "3 km/h" na tela; 0x2d = 4,5 ↔ "4" | `[HIPÓTESE forte]` (prints só com minuto; escala não calibrada) |
| Composição de bytes[10..11] e saturação acima de 25,5 km/h (limite do patinete 50) | `[DESCONHECIDO]` |
| Meter2 `dado[0..1]` e `dado[4]` variam só com o acelerador (0x0368, 0x017d…; 0xa0, 0x46, 0x3c…) | `[CONFIRMADO]` os bytes; significado (corrente/potência?) `[HIPÓTESE]` |
Uso no app: a pré-condição do Unlock só precisa de "bytes 9..11 todos zero" (independe de escala). O velocímetro da Telemetria continua sendo o GPS (decisão do proprietário).
