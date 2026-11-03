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

## Atualização (sessão 01, após aprovação do proprietário)
- Plano de testes v2.1 aprovado pelo proprietário com condições (sem PIN errado, sem comandos próprios, H7A/H7B, tempos, sem bruto no git) e H11; Codex T12 r4 APROVADO. Lição: r2 inicial foi inválida (diff vazio por commit antes); sempre fixar o intervalo `git diff A..B`.
- Achado: modo 1 do lock DEFINE PIN (grava após ACK); sem PIN só modo 2 (temporário). Não enviar modo 1/3.
- Haiku usado para edição mecânica (scripts/agents/run-haiku.sh); o líder verificou o diff.
- Bloqueio atual: telefone não aparece no adb; reconectar e autorizar depuração USB, depois seguir docs/research/captura-roteiro.md.

## Backlog de funções pedido pelo proprietário (2026-11-03)
Copiar do app original, **depois** de lock/unlock estável (REGRA 4): ligar/desligar farol (CMD 0x33), trocar limite de velocidade (CMD 0x62/Meter1 byte[12], a confirmar), alternar km/milhas (CMD 0x3C). Protocolo ainda `[HIPÓTESE]`/`[ESTÁTICO]`; nenhum comando próprio nesta sprint. Se a captura passiva cobrir estas ações (passo opcional 8 do roteiro), vira evidência.

## Sprint 2 — captura concluída (2026-11-03)
- Resultados: `docs/research/parts/S2-captura-resultados.md`. 12/12 bloqueios confirmados pelo estado real; LOCK modo 2/0 sem PIN (patinete sem PIN, `lock_has_pwd=0`); ACK mediana 51 ms, estado mediana 419 ms (p95 681 ms).
- Método: app original reempacotado com IX7TAP (`scripts/repack/`), porque HCI snoop do Samsung é inacessível sem root. O app modificado **continua instalado** no telefone (assinatura própria); restaurar o original exige desinstalar (ver `scripts/repack/README.md`).
- Pendências: revisão Codex do `S2-captura-resultados.md` e do commit `0c1e53f` (Codex sem cota até 2026-11-04 09:27); decidir restaurar o app original; atualizar H1–H11 no `test-plan.md` e ADR 0002; CRC das respostas `[DESCONHECIDO]`; t_scan/t_conn não medidos; desligamento automático ~10 min `[HIPÓTESE]`.
- Lembrete: o proprietário aceitou risco residual de PIN no logger; como o patinete não tem PIN, nada a trocar — confirmar com ele.

## Próximo passo (2026-11-03, fim da sessão 3)
1. Construir o protótipo conforme `docs/sprints/fase-2-plano.md` com `figcraft-cli run` (scripts em `design/scripts/`), 1 worker designer em worktree; revisor ≠ autor (Codex só após 2026-11-04 09:27; antes, agy/Haiku).
2. Pendências da Fase 1: mesclar `docs/research/parts/*` em `protocol-analysis.md`; revisão Codex de S2-captura-resultados e do logger (0c1e53f); atualizar H1–H11 em test-plan e ADR 0002 (aceitar: sem nuvem, sem PIN neste patinete); decidir restaurar app original no telefone (hoje: app reempacotado instalado, assinatura própria; restaurar exige desinstalar e novo login).
3. Nenhum comando ao patinete até a Fase 4 com aprovação.

## Sessão 4 — fim (2026-11-03)
- **Estado:** main = `docs` + `README.md`/`LICENSE` (MIT, 342c9dd; ficha TASK-D1/D2 e `docs/design/ux-direcao.md` em 744ffaa). Protótipo na branch **`task/TASK-D3-home-coordenador` @ 6ba4701** (ainda não mesclada; mesclar só com autorização). Ramos `task/TASK-D1-prototipo` e `task/TASK-D2-prototipo-v2` (trabalho do agy, descartado pelo proprietário) podem ser apagados.
- **Como gerar:** `cd design && ./run.sh` (usa `tools/figcraft/...` do repo principal, `python3 build.py` → `scripts/home.json` → `home.figcraft`, PNGs em `design/out/{escuro,claro}`). Código: `lib.py` (tokens, ícones; `measure_icons.py` mede o desvio do traçado → `icons_dim.json`), `home.py`, `telemetry.py`, `history.py`, `screens.py`.
- **Decisões do proprietário:** botão de bloqueio retangular largo no rodapé; nome do app **"Meu iScooter"** (constante `APP_NAME`; é marca de terceiros no nome — rever antes de distribuir); 4 modos Pedestre/Eco/Race/Sport (mapeamento nome↔valor 0–3 do CMD 0x35 é `[HIPÓTESE]`); menu ⋮ no topo da Telemetria (zerar viagem salva no histórico); sem versões de comunicação/controlador.
- **Pendências de decisão do proprietário:** (1) comportamento dos controles com patinete em movimento (hoje só o bloqueio é desabilitado); (2) fonte do mapa do histórico (OSM/offline/SDK); (3) nome do app; (4) selo "dados de exemplo" na UI (hoje não há); (5) textos "mais economia/autonomia" e "reconectar sozinho" sem evidência.
- **Revisão Haiku r1 (D3):** sem PIN, sem jargão, sem "concluído" fora do confirmado; achados aplicados: datas do histórico. Não aplicados (decisões acima). Falta revisão visual cruzada por agente (agy/Haiku não vê imagens bem) ou pelo próprio proprietário.
- **Lições:** FigCraft soma o mínimo NÃO escalado do traçado a x/y em `vector.create` (medir com `node.get`); `text.create` não aceita height (usar `node.set` com `textAutoResize:"none"`, `textAlignVertical:"middle"`); `pkill -f agy` mata o próprio shell (usar `kill <pid>`); `page.select` aceita `index`/`id`; `proto.interaction.add` com delay precisa `dest` dentro de `action`; export de várias páginas exige pastas distintas.
- **Pendências da Fase 1 (inalteradas):** mesclar `docs/research/parts/*` em protocol-analysis.md; revisão Codex do S2 (cota volta 2026-11-04 09:27); H1–H11 e ADR 0002; decidir restaurar o app original no telefone.
