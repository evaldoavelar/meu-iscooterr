# S31 — Tração, limite, modo e unidade: mapeamento (capturas existentes, sem TX nosso)

Fonte: `research/captures/s2d/ix7tap-roteiro2.txt` (privada; patinete desbloqueado e parado em todos os TX; protocolo 1.03) + jadx (`q3/a.java`, `DeviceCarSettingAty`, `DeviceCarControlFrag`, `BleViewModel`). Agente Sonnet (leitura); linhas L5077, L5252, L4911, L4823 conferidas pelo coordenador. Todos: TIPO 02, ACK TIPO 05 em 22–149 ms (não prova execução); CRC-16/Modbus conferido 100% nos TX.

Meter1 (CMD 0x20): b12 = limite atual (km/h); b15 = Status2: bits0-2 modo (0..5), bit3 unidade (1 = mph), bit4 tração (1 = 2 motores), bits5-6 recuperação (fora de escopo). Meter2 b20 = máscara dos modos disponíveis.

| Função | CMD / dado | Bytes (exemplos) | Confirmação (Meter1, ≤ 1 s) |
|---|---|---|---|
| Tração | 0x3b, LEN 1: 0 = 1 motor, 1 = 2 motores | `1aa13b0201006…` ver abaixo | b15 bit4 acompanha, 2/2 `[CONFIRMADO]` |
| Limite | 0x36, LEN 1: km/h (20..50 em 1 motor; 15..60 em 2) | 39: `1aa136020127efe61ff1`; 50: `…32 2e29…` | b12 == pedido, 6/6 `[CONFIRMADO]` |
| Modo | 0x35, LEN 2: [modo, 0]; 0 Walking, 1 Eco, 2 Balanced, 3 Sports (`[ESTÁTICO]`) | 0: `1aa135020200 00087c1ff1`; 1: `…0100 09ec…`; 2: `…0200 091c…`; 3: `…0300 088c…` | b15 bits0-2 == modo, 4/4 `[CONFIRMADO]`; b12 não muda |
| Unidade | 0x3c, LEN 1: 0 km/h, 1 mph | mph `1aa13c0201016de41ff1`; km/h `…00 ac24…` | b15 bit3 acompanha, 4/4 `[CONFIRMADO]` |

Tração: 1 motor `1aa13b020100ad501ff1`; 2 motores `1aa13b0201016c901ff1`.

## Efeitos e riscos
- `[CONFIRMADO]` 2 motores: limite sobe sozinho de 50 para 60 e faixa vira 15..60 (consulta 0x62 `1aa16201 00d18e1ff1`, resposta min/máx nos bytes 5/6); ao voltar, 50 e 20..50. Dobra a potência.
- `[CONFIRMADO]` mph: b12 passa de 50 para 51; `[HIPÓTESE]` demais campos de velocidade/distância mudam de escala (não verificado); o app novo precisa ler b15 bit3 antes de interpretar números. Original grava limite bruto em mph (pode enviar < 20 km/h): o app novo só grava limite com unidade = km/h e dentro da faixa do 0x62.
- Restrições do original `[ESTÁTICO]`: tração/limite/modo recusam em movimento; modo recusa bloqueado; unidade recusa bloqueado; limite e tração não checam bloqueio. Só no cliente; firmware com patinete bloqueado/em movimento `[DESCONHECIDO]` (nunca testar em movimento).
- `[DESCONHECIDO]` valores fora da lista (modos 4/5, limite fora da faixa, LEN/dado outros); efeito real de cada modo; escala de odômetro em mph.
- Ordem de risco: unidade < modo < limite < tração.

## Proposta (aguarda aprovação do proprietário)
Lista fechada em `TxCommand`: `Drive(twoMotors)`, `SpeedLimit(kmh)`, `DriveMode(0..3)`, `Unit(mph)`. Travas do Lock/Light (BLE real, TX ligado, parado, Meter1 fresco, desbloqueado, uma tentativa, sem repetição, "concluído" só pelo Meter1 após o envio). Limite: só em km/h e dentro da faixa vigente do 0x62 (requer consulta 0x62 antes; se faixa desconhecida, recusa). Modo: só os marcados no Meter2 b20. Proibidos por teste: demais valores, modos 4/5, limite ≥ 61, outros CMDs.

## Adendo S35 — Ordem e nomes dos modos no painel real
Em 2026-11-10, o proprietário confirmou visualmente no painel real do patinete a ordem: 0 Walking (ícone), 1 Eco, 2 Race, 3 Sport (`[CONFIRMADO pelo proprietário]`). Os códigos enviados (CMD 0x35, valores 0..3) e sua ordem permanecem inalterados; a tabela histórica de captura acima registrou a nomenclatura original de análise estática (`[ESTÁTICO]`, 2 Balanced / 3 Sports).
