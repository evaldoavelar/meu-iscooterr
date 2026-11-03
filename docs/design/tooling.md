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

# Ferramental Android (Sprint 4, 2026-11-03)
Tudo em `tools/` (ignorado pelo git; baixar de novo conforme abaixo). Ambiente: `source scripts/android-env.sh` (define `ANDROID_HOME`, `GRADLE_USER_HOME=tools/gradle-home`).

| Item | Versão | Origem | SHA-256 |
|---|---|---|---|
| Gradle (dist. e wrapper em `app/`) | 9.8.2 | `services.gradle.org/distributions/gradle-9.8.2-bin.zip` | `21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607` (igual ao publicado pelo Gradle; fixado em `distributionSha256Sum`) |
| Android `cmdline-tools` | 15859902 | `dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip` | `4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583` (igual ao de developer.android.com/studio) |
| `platforms;android-35`, `build-tools;35.0.0`, `platform-tools` 37.0.1 | — | `sdkmanager` (repositório oficial; licenças aceitas via `yes \| sdkmanager`) | verificação interna do sdkmanager |
| AGP / Kotlin / Compose BOM / Coroutines / Robolectric / JaCoCo | 9.5.0 / 2.4.21 / 2026.09.00 / 1.11.0 / 4.17 / 0.8.16 | Google Maven / Maven Central (estáveis mais recentes em 2026-11-03) | — (`app/gradle/libs.versions.toml`) |

Notas: sem `unzip` no host (extração com Python `zipfile` + `chmod`); `org.gradle.daemon=false` e 2 GiB de heap (máquina com ~6 GiB livres). Identificador Android: `br.dev.evaldo.meuscooter` (neutro). Nome de trabalho do app: constante única (ver sessão 07).
