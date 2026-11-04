# TASK-MAP-OSM — mapas OSM com trajetos simulados (independente do BLE)
Worker: Sonnet, worktree `../wt/mapa`, branch `task/mapa`. **Não commita.** Gradle sempre com `flock /tmp/ix7-gradle.lock`.
Leia: `CLAUDE.md`, `AGENTS.md`, `docs/sprints/sprint5-entrega.md` (achado A4), `app/core-domain/.../MapTiles.kt` (`MapTileSource`, `OsmTileSource`, `TileCachePolicy`, `TileMath`), `app/ui/.../history/RouteMap.kt`, telas de Histórico/Viagem, `docs/design/ux-direcao.md`.

## Escopo (TDD, um teste por vez; 80 % de cobertura)
1. Permissão `INTERNET` no manifest do app (teste/verificação do manifest).
2. Provedor de tiles configurável (`MapTileSource` já existe; torná-lo selecionável sem tocar na UI; User-Agent identificável: nome do app + versão + contato/URL de uso pessoal, **sem e-mail pessoal** — usar apenas "Meu iScooter/<versão> (uso pessoal)").
3. Carregador de tiles (interface `TileLoader` no domínio/data + implementação HTTP Android atrás de interface; testes com loader falso): cache em disco conforme `TileCachePolicy` (validade 30 dias, 50 MB, eviction), tile expirado usado só se a rede falhar (marcar), rede indisponível → traçado sobre fundo neutro + aviso discreto "Mapa indisponível sem internet" (sem travar a UI, sem bloquear thread principal), limite de concorrência e sem repetir requisição de tile já em voo (respeitar política de uso do OSM).
4. Camada de tiles no `RouteMap`: tiles sob o traçado, zoom/centro calculados a partir do trajeto com `TileMath`; trajeto continua visível por cima.
5. **Atribuição "© colaboradores do OpenStreetMap" sempre visível quando houver tiles desenhados**, com teste Compose que falha se sumir; também em tela de detalhe e miniatura se tiles forem usados nelas (ou miniaturas só traçado — decidir e documentar).
6. Trajetos simulados (`SampleTrips`): manter; documentar que as coordenadas são fictícias (mostrar tiles do local simulado é aceito pelo proprietário nesta fase).
7. Testes de screenshot/S5 existentes não podem regredir (sem `--update-snapshots`; se a mudança visual for legítima, listar no handoff para o líder decidir).
## Proibido
Rede real nos testes; editar BLE; gradle wrapper/lockfiles; STATUS/RESUME; telemetria/analytics; chave de API.
## Aceite
`flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon clean check :android-app:assembleDebug'` verde. Saída final ≤ 10 linhas + `docs/tasks/HANDOFF-MAP-OSM.md`.
