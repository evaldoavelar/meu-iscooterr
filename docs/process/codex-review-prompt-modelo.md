# Modelo de prompt de revisão Codex (enxuto, para poupar cota)
Contexto (sessão 12): cada revisão repete o contexto inteiro em 13–28 chamadas; ler os docs de processo inteiros e revisar intervalos grandes multiplica o custo.

```
# Revisão — <assunto> (somente leitura; revisor ≠ autor)
Intervalo: `git diff <base>..<head>` (<N> arquivos). NÃO leia CLAUDE.md, coordenacao-agentes.md nem READMEs de sprint: as regras relevantes estão aqui.
Arquivos a revisar: <lista curta, com caminhos>.
Regras que importam: <3–5 linhas: ex. "concluído só com Outcome.Confirmed", "nenhum BLE real">.
Procure: defeitos de comportamento (com sequência de entrada → saída errada), não estilo.
Saída: VEREDITO (APROVADO/REPROVADO) + achados numerados [P1–P3][ESTÁTICO] com arquivo:linha e cenário.
```
Regras de uso: Revisor padrão: Sol (`gpt-6.1-sol`) com `medium` (decisão do proprietário, sessão 12); `CODEX_EFFORT=high` só no fechamento de sprint; Luna (`gpt-6-luna`, mais barato) opcional em revisão rotineira; um intervalo por vez (≤ ~15 arquivos). Revisão rotineira de cada fatia: Sonnet (só leitura) continua sendo a barreira local.
