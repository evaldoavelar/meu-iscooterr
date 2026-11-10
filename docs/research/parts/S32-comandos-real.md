# S32 — teste real: farol, unidade, modo, limite e tração

Data: 2026-11-09. Patinete parado, no cavalete, desbloqueado, proprietário presente, app original fechado. Logs: `research/captures/s32-comandos/` (git-ignorado): `s32-logcat.log` (1ª rodada), `s32-logcat-2.log` (limite com toast), `s32-logcat-4.log` (modo/apito), `s32-original-tap.log` (original: Cruise e Partida do zero).

## Resultado `[CONFIRMADO]` (TX → ACK → Meter1; app só mostrou "concluído" após o Meter1)
| Comando | CMD | Quadros TX observados | Confirmação |
|---|---|---|---|
| Farol | 0x33 | ligar `1aa133020600fa0000000075cc1ff1`; desligar `1aa1330206000000000000add81ff1` | `LIGHT result=Confirmed` (~0,5 s) |
| Unidade | 0x3c | km/h `1aa13c020100ac241ff1`; mph `1aa13c0201016de41ff1` | `UNIT result=Confirmed` |
| Modo | 0x35 | 0 `…0200087c`; 1 `…0201 0009ec`; 2 `…0202 00091c`; 3 `…0203 00088c` (payload `[modo,0]`) | `MODE result=Confirmed` nos 4 modos, várias rodadas |
| Limite | 0x36 | `1aa1360201 <km/h> crc`; valores 42, 45, 50, 60 aceitos | `LIMIT result=Confirmed`; 60 só com 2 motores |
| Faixa | 0x62 | `1aa1620100d18e1ff1` | resposta `1aa16204 02 14 32 …` = 20..50 (1 motor) |
| Tração | 0x3b | 1 motor `1aa13b020100ad501ff1`; 2 motores `1aa13b0201016c901ff1` | `DRIVE result=Confirmed`; teto sobe a 60 / volta a 50 |

## Observações
- **Apito duplo ao trocar o modo:** o app envia **um** TX 0x35 e recebe **um** ACK (9 trocas em `s32-logcat-4.log`; nenhum outro comando); os quadros são idênticos aos do original. `[HIPÓTESE]` firmware (conferir se o original também apita duas vezes).
- **Slider do limite (APK da S31):** enviou sempre 0x32 (50), que já era o valor do patinete e o teto de 1 motor → "Confirmed" sem mudança. Causa exata não isolada `[DESCONHECIDO]`; com o APK novo (valor no log) 42/45/60 saíram certos.
- **Limitação:** após trocar a tração a faixa é invalidada; o 1º ajuste de limite só consulta o 0x62 (recusado por `RangeUnknown/PendingReception`), o 2º envia. Candidato a correção: consultar a faixa após a troca.
- **UI (pedidos do proprietário):** sem diálogo na tração; unidade só nas Preferências (envia 0x3c); modos Pedestre(ícone verde)/Eco/Esporte/Race (nomes do painel; o modo 2 = "Esporte", 3 = "Race" é mapeamento por ordem `[HIPÓTESE]` até conferir no painel); toast do teto (1 motor 50 / 2 motores 60).
- **Cruise e Partida do zero:** ver `S32-cruise-partida-mapeamento.md` e ADR 0010 (captura do original em `s32-original-tap.log`: 0x38 `05 03`/`00 03`; 0x39 `01 00`=desabilitada / `00 00`=habilitada; Meter1 byte[16] bit6 = cruise, bit7 = partida DESABILITADA).

## Adendo S35 — Ordem e rótulos dos modos Race e Sport no painel real
Em 2026-11-10, o proprietário confirmou visualmente no painel real do patinete a ordem: 0 Walking (ícone), 1 Eco, 2 Race, 3 Sport (`[CONFIRMADO pelo proprietário]`). O mapeamento anterior por ordem (2 = Esporte, 3 = Race) era `[HIPÓTESE]` e estava invertido. Os códigos de protocolo (CMD 0x35, valores 0..3) e quadros permanecem inalterados.
