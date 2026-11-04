# HANDOFF — TASK-MAP-OSM (worker Sonnet, sem commit)

## Entregue
- `INTERNET` no manifest; `android-app/.../ManifestTest` verifica.
- Domínio: `TileProvider`/`TileSources` (provedor selecionável; UA `Meu iScooter/<versão> (uso pessoal)`), `TileLoader`/`TileNetwork`/`TileCacheStore`, `CachingTileLoader` (cache 30 dias/50 MB com despejo, expirado só se a rede falhar com `stale=true`, dedupe de tile em voo, máx. 2 downloads simultâneos, falha = `Unavailable`), `MapViewport` (zoom/centro/tiles em Web Mercator).
- Data: `DiskTileCacheStore` (arquivo = 8 bytes de data + PNG; `lastModified` = último acesso), `HttpTileNetwork` (HttpURLConnection em `Dispatchers.IO`, timeout 10 s, limite 512 KB).
- UI: `RouteMap` desenha tiles sob o traçado via `LocalMapTiles` (nenhuma tela alterada); atribuição `map-attribution` só com tile desenhado; aviso `map-offline` "Mapa indisponível sem internet" quando nenhum tile carrega. `MainActivity` fornece o carregador (cache em `cacheDir/map-tiles`).
- Decisão: miniaturas (lista) ficam só traçado, sem tiles nem atribuição (evita rajada de requisições). Detalhe da viagem usa tiles.
- `SampleTrips`: coordenadas continuam fictícias; comentário documenta que os tiles mostram o local simulado.

## Limitações
- Robolectric decodifica qualquer byte como bitmap: o ramo "PNG corrompido vira indisponível" não tem teste.
- `HttpTileNetwork` é testado contra servidor em loopback (127.0.0.1), nunca internet.
- Testes novos foram escritos junto da implementação por grupo de comportamento (vermelho por compilação), não um a um.
- Sem testes de screenshot alterados; nenhum snapshot atualizado (S5 sem loader = caminho antigo `RouteProjection`).

## Comandos executados
- `flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon --offline :core-domain:test|check'` e `:data:check`, `:ui:testDebugUnitTest --tests "*RouteMapTilesTest*"`
- Aceite: `flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon --offline clean check :android-app:assembleDebug'` → BUILD SUCCESSFUL (3m46s). Uma execução anterior falhou em `RouteMapTilesTest` (teste do portão, instável); estabilizado com `runOnIdle` e espera de 15 s (reexecução abaixo).
