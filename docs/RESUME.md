# RESUME (fim da sessão 01)
## Estado
Fase 1 estática concluída. Commits-chave: b17da5a estrutura · 3554a14 coordenação · 7362974 ferramentas · 11ab78f plano · 8309e29 T1 · 9ede21a/41b0a5e T3-T7 · 8ee7555 T9+ADR0002 · 2a5ddf1 T2+T12 · 3a72ce7 correções T11-r1 · (últimos: H8 e ajuste r2).
Achados centrais: GATT FF60/FF61/FF62; quadro `1A A1 CMD TIPO LEN DADOS CRC16 1F F1`; LOCK 0x32 + PIN 4 dígitos (modo 0 unlock, 1 lock, 2 temporário); ACK TIPO 0x05, erro de PIN TIPO 0x89; estado real em Meter1 byte[6] bit5; app original recusa lock com velocidade>0; lock sem token de servidor no quadro (ADR 0002 proposto).
## Lições
- Revisor Codex achou 6 erros reais em texto do coordenador: sempre revisão cruzada antes de dar como fato.
- Constantes inlinadas: seguir por `q3.a`. EXIT do script de background mascara o código do JADX (foi 3).
- Respostas: `byte[3]` é tipo (0x05 ACK, 0x89 erro).
## Dívidas / bloqueios (precisam do usuário)
1. Aprovar `docs/research/test-plan.md` e fornecer patinete parado no cavalete para a Sprint 2 (captura HCI do app original). Nada feito no patinete ainda.
2. Decidir se a H8 inclui tentativa com PIN errado (risco de bloqueio desconhecido).
3. Identificação no scan (nome/UUID do anúncio) `[DESCONHECIDO]`.
4. Depois: Fase 2 (FigCraft CLI/MCP ainda não instalado).
