# Dashboard de Progresso do Projeto

Dashboard estático, leve e autônomo para acompanhamento do progresso do iScooter Connect.

## Como abrir
O dashboard é um arquivo HTML único e autônomo. Pode ser aberto diretamente no navegador:
```bash
xdg-open docs/progress/dashboard.html
# Ou abra no navegador via file://
# file:///caminho/para/ix7/docs/progress/dashboard.html
```

## Como atualizar (1 comando)
Para marcar uma tarefa concluída com a data de hoje:
```bash
scripts/progress/done.sh <id>
# Exemplo:
scripts/progress/done.sh F4.10
```
Para alterar para outro estado:
```bash
scripts/progress/done.sh <id> [doing|blocked|todo|unknown]
# Exemplo:
scripts/progress/done.sh F4.9 doing
```
O comando atualiza `docs/progress/progress.json` e automaticamente regenera `docs/progress/dashboard.html`.

Para regenerar o dashboard manualmente a qualquer momento:
```bash
python3 scripts/progress/progress.py build
```

## Hooks do Git e instalação
Os hooks `post-commit` e `post-merge` regeneram automaticamente o dashboard a cada commit/merge local.

- **Instalação:**
  ```bash
  scripts/hooks/install.sh
  ```
- **Desinstalação:**
  ```bash
  scripts/hooks/install.sh --uninstall
  ```
- **Comportamento seguro:**
  - Só age no diretório principal (ignora worktrees secundárias em `.wc/*`).
  - Executa silenciosamente com timeout de 3 segundos.
  - Se o build falhar por qualquer motivo, o commit **não falha** (sempre `exit 0`).
  - Não sobrescreve hooks de terceiros (identifica nossos hooks pela linha `# iscooter-progress-hook`).

## Custo de tokens = ZERO
O dashboard não utiliza LLMs, APIs ou serviços externos:
- Roda 100% localmente em Python 3 stdlib.
- O HTML gerado é autônomo, sem bibliotecas externas, fontes remotas ou chamadas `fetch`.
- Zero custo de tokens ou consumo de cota.

## Formato do `progress.json`
- `project`: nome do projeto.
- `started`: data de início (AAAA-MM-DD).
- `state_values`: estados válidos (`done`, `doing`, `blocked`, `todo`, `unknown`).
- `weight_note`: explicação da escala de peso.
- `phases`: lista de fases (`F0`–`F7`), cada uma com `id`, `name` e lista de `tasks`.
  - Tarefa: `id`, `sprint`, `title`, `state`, `weight` (numérico > 0), `done` (AAAA-MM-DD, obrigatório se `done`, proibido se não-`done`), `est` (booleano opcional para escopo estimado), `blocked_by` (opcional).
- `blockers`: lista de bloqueios abertos (`id`, `kind`, `text`, `ref`).
- `criteria`: critérios de aceitação do projeto (`text`, `state`, `note` opcional).

## Como o ETA é calculado (PLANO.md §6)
1. **Crédito por tarefa:** `done` = 1,0; `doing` = 0,5; demais = 0,0.
2. **% de conclusão:** $\Sigma(\text{peso} \times \text{crédito}) / \Sigma(\text{peso})$. Exibido com e sem tarefas de escopo estimado (`est: true`).
3. **Ritmo:** peso concluído por dia, medido pelas datas `done` em três janelas: 3 dias, 7 dias e desde o início do projeto. A janela é limitada à idade do projeto (mínimo 1 dia).
4. **Restante:** $\Sigma(\text{peso} \times (1 - \text{crédito}))$.
5. **ETA:** $\text{Restante} / \text{Ritmo}$. Exibe a faixa de dias [menor, maior] entre as janelas e as datas previstas de conclusão. Caso o ritmo seja 0, exibe "indeterminado".

## Limitações
- Os pesos são estimativas subjetivas do coordenador e não refletem complexidade imprevista.
- O cálculo de ETA assume dedicação constante e **ignora esperas por aprovação do proprietário e limites de cota** de ferramentas externas.
- A granularidade temporal do git e das tarefas é diária (baseada em datas de conclusão).
