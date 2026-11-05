# Dashboard de progresso — plano e decisões

## Decisões (autônomas, sessão dashboard)
1. **Fonte única de estado:** `docs/progress/progress.json` (versionado, ~100 linhas, 1 tarefa por linha). Python 3 stdlib; sem LLM, sem rede.
2. **Atualizar = 1 comando:** `scripts/progress/done.sh <id>` (marca `done` + data de hoje) ou `scripts/progress/done.sh <id> doing|blocked|todo|unknown`. O agente commita o JSON junto com o trabalho.
3. **Saída gerada e NÃO versionada:** `docs/progress/dashboard.html` (um arquivo, dados embutidos, abre por `file://`), listada em `.gitignore` ⇒ a árvore fica limpa após cada commit.
4. **Gatilho:** hooks `post-commit` e `post-merge` versionados em `scripts/hooks/`, instalados por `scripts/hooks/install.sh` em `$(git rev-parse --git-common-dir)/hooks/` (recusa sobrescrever hook alheio). O hook: só roda no checkout principal (não em `.wc/*`), chama `build`, sempre `exit 0`, silencioso, < 1 s.
5. **Derivado do git/docs (sem LLM):** commits/dia e últimos 12 commits (`git log`), último marco = última linha `- Sessão NN` de `docs/STATUS.md` (regex), contagem de `[HIPÓTESE]`/`[DESCONHECIDO]` em `docs/research/**` e as 5 primeiras linhas de cada.
6. **Cálculo (sempre exibido na página):**
   - Crédito por tarefa: `done`=1, `doing`=0,5, demais=0. % da fase = Σ(peso×crédito)/Σpeso; % total idem sobre todas as tarefas.
   - Ritmo = peso concluído por dia, medido pelas datas `done`: janelas 3 d, 7 d e desde o início (janela limitada à idade do projeto, mínimo 1 d).
   - Restante = Σ peso×(1−crédito). ETA = restante / ritmo; mostrar faixa [menor, maior] entre as 3 janelas e a data prevista (hoje + dias). Ritmo 0 ⇒ "indeterminado". Tarefas `est:true` são marcadas "escopo estimado" e somadas à parte (mostrar % com e sem elas).
   - Aviso fixo: pesos são estimativas do coordenador; ETA ignora esperas por aprovação do proprietário/cota.
7. **Testes:** `python3 -m unittest discover -s tests/progress` (sem instalar nada).

## Critérios de aceite
- `python3 scripts/progress/progress.py build` gera `docs/progress/dashboard.html` com os dados reais, exit 0, < 1 s.
- `done.sh F4.10` altera só aquela tarefa (JSON continua 1 tarefa/linha) e regenera o HTML; id inexistente ⇒ erro claro, exit ≠ 0, sem alterar arquivo.
- JSON inválido/ausente ⇒ `build` falha com mensagem; hook ainda sai 0.
- HTML: sem recursos externos, tema claro/escuro (`prefers-color-scheme` + botão), legível em 360 px, barra total + por fase, tabela de fases, sprints/tarefas por fase com estado, bloqueios, critérios do projeto, método da estimativa, marcos/commits, hipóteses abertas.
- Testes cobrem: parse/validação, crédito e % (fase/total, com `est`), ritmo/ETA (janelas, ritmo 0), `set` (done+data, id inexistente, estado inválido), extração de STATUS e hipóteses, escape de HTML.
- Hook: num commit de teste, árvore limpa depois; falha do build não falha o commit.
- Sem `test.skip`, stubs, TODO.
