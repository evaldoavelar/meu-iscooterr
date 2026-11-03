# Ferramentas de design (S0-5 / S3)

| Item | Versão | Origem | Verificação |
|---|---|---|---|
| FigCraft (`storytold/figcraft`, Rust, MIT/Apache-2.0, **early alpha**) | 0.15.0 | release oficial do GitHub `figcraft-0.15.0-linux-x86_64.tar.gz` | SHA-256 `1e5e8e0b9b4a1811f31ce8204a9bc53c851f208b6fe45e0287302f70bc6a08d6` (o release não publica checksum; calculado no download) |
| `figcraft-cli` / `figcraft` | 0.15.0 | dentro do tarball, extraído em `tools/figcraft/0.15.0/` (ignorado pelo git) | `figcraft-cli --version` = 0.15.0 |

Atenção: o pacote npm `figcraft` é **outro projeto** (diagramas SVG). Não usar.

## Validado (2026-11-03)
`figcraft-cli run script.json --out x.figcraft` cria frames/textos headless; `info` lista páginas e nós; `render` gera PNG; `commands` lista os comandos (variáveis, auto layout, componentes, protótipo). MCP: `figcraft-cli mcp` (stdio) — ainda não ligado ao Claude Code.

## Como usar
```
C=tools/figcraft/0.15.0/figcraft-0.15.0-linux-x86_64/bin/figcraft-cli
$C run design/build.json --out design/iscooter-connect.figcraft
$C info design/iscooter-connect.figcraft
$C render design/iscooter-connect.figcraft -o design/out/overview.png --scale 2
$C commands --filter variable
```
Scripts são JSON: lista de `{command, params, as}`; `{"$ref": "nome"}` aponta para o id criado antes; o script inteiro é uma etapa de desfazer e é tudo-ou-nada. Doc: `docs/cli.md` e `docs/mcp.md` do repositório do FigCraft.

## Reprodutibilidade
`design/iscooter-connect.figcraft` é gerado por scripts em `design/scripts/` (versionados); renderizar com `--no-system-fonts` para PNGs idênticos entre máquinas.
