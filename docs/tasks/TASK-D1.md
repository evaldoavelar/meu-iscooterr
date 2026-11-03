# TASK-D1 — Protótipo FigCraft (Fase 2)
Autor: worker designer (agy). Revisor: outro agente (Haiku/agy plan). Coordenador commita.

## Escopo
Gerar `design/iscooter-connect.figcraft` **só por scripts** `design/scripts/NN-*.json` executados em ordem por `design/build.sh` (que cria o arquivo do zero a partir do 1º script e acumula os demais, ou concatena os JSON; tudo reproduzível) e renderizar PNGs em `design/out/` (`--no-system-fonts --scale 2`).
Spec funcional: `docs/sprints/fase-2-plano.md` (leia). Exemplo funcional de variáveis com modos: `design/scripts/_exemplo-variaveis.json` (variable.createCollection/create com `values`, `node.set fill {$var}`, `variable.setMode` no frame para o modo Escuro).

## Arquivos permitidos
`design/**` apenas. Proibido: `docs/**`, `app/**`, `scripts/**`, `research/**`, `CLAUDE.md`. Não commitar. Nenhum Bluetooth/ADB/rede. Nenhum comando ao patinete.

## Ferramenta
`/home/evaldo/Documents/ix7/tools/figcraft/0.15.0/figcraft-0.15.0-linux-x86_64/bin/figcraft-cli` (`run script.json --out x.figcraft`, `info`, `render -o x.png --scale 2 --no-system-fonts`, `commands --filter X` para parâmetros). Script = lista `{command, params, as}`; `{"$ref":"nome"}`. Tudo-ou-nada por script. Se um comando falhar, ajuste e rode de novo; não contorne com atalhos sem componente/variável.

## Entregas
1. **Variáveis** (coleção com modos `Claro`/`Escuro`, Material 3): cores (bg, surface, surface-variant, primary, on-primary, secondary-container, on-surface, on-surface-variant, outline, success, error, warning, pending), números (espaçamento 4/8/12/16/24/32, raio 8/12/16/28/full). Toda cor das telas usa `$var`.
2. **Componentes** (com variantes quando fizer sentido): Botão de bloqueio grande (circular/pílula ~200 px): variantes `estado=desbloqueado|bloqueado|enviando|falha|desabilitado`; Botão (primário/tonal/desabilitado/carregando); cartão de estado; interruptor; slider; chip de bateria; banner de erro.
3. **5 telas 360×800** em Claro **e** Escuro (10 frames; Escuro via `variable.setMode`): Início/Bloqueio · Primeiro uso/Cadastro · Recuperação de conexão · Controles · Diagnóstico.
4. **Estados da tela principal** (frames separados, ambos os modos): conectando · conectado-desbloqueado · conectado-bloqueado · **enviando** ("Aguardando o patinete…", NÃO diz concluído) · **confirmado** (só aqui aparece "Bloqueado/Desbloqueado ✓" + hora da confirmação) · falha/sem confirmação (com Tentar de novo). Estado "enviando" visualmente distinto de "confirmado". Bateria e limite de velocidade visíveis.
5. **Telas de recuperação**: patinete desligado/fora de alcance · Bluetooth desligado · permissão negada · reconectando (4 variações no frame 3 ou frames extras).
6. **Interações** (`proto.interaction.add`, `proto.flow.add`): Início → Controles/Diagnóstico e voltar; toque no botão: bloqueado→enviando (afterDelay)→confirmado; falha→tentar de novo; fluxo Primeiro uso → Início.
7. Regras de conteúdo: textos em português; nenhum "concluído/sucesso" fora do estado confirmado; **sem campo de PIN** (patinete sem PIN `[CONFIRMADO]`); Controles só habilitados com patinete parado e confirmado (mostrar aviso); nunca mostrar dados de captura.
8. Organização: página `Sistema` (variáveis/componentes), página `Telas Claro`, página `Telas Escuro` (ou frames lado a lado, nomeados `NN-Nome/Claro`).

## Aceite
`design/build.sh` roda do zero com exit 0; `figcraft-cli info` lista as páginas; PNGs gerados e legíveis (texto cortado/sobreposto = reprovação); contraste adequado; nada fora do escopo alterado.

## Saída
≤10 linhas no stdout; handoff em `design/HANDOFF-D1.md` só com comandos realmente executados e limitações encontradas no FigCraft.
