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

## Revisão r1 (correções)
- B1: `HttpTileNetwork` exige assinatura PNG e corpo completo (Content-Length); só o que passa é gravado. Na UI, tile que não decodifica vira "indisponível" e `TileLoader.invalidate` o apaga do cache.
- A1: persistência (write/evict) em try separado: falha de disco devolve `Loaded` sem repetir download; `.tmp` órfão é apagado ao abrir o cache.
- L1: teste de pixel prova o tile desenhado. L2: bitmaps/aviso reiniciam quando o tamanho muda (atribuição some até os novos tiles). L3: tocar na atribuição abre `https://www.openstreetmap.org/copyright` (texto exato mantido). L7: teste limita a 24 tiles em 1080×682 px.
- Pendências registradas (sem ação agora): L4/L5/L6 conforme revisão; sem backoff após falhas de rede (cada abertura de detalhe tenta de novo); tile pode ter até 24 requisições no pior caso (2 simultâneas).
- Evidência TDD: `docs/tdd/mapa-r1.md`. Aceite repetido: `flock /tmp/ix7-gradle.lock bash -c 'source scripts/android-env.sh && cd app && ./gradlew --no-daemon --offline clean check :android-app:assembleDebug'`.
