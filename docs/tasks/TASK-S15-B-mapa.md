# TASK-S15-B — mapa: flake do RouteMapTilesTest, L4–L6, PNG corrompido
Worktree `../wt/s15b`, branch `task/s15b`. Arquivos permitidos: `ui/src/**/history/**`, `ui/src/test/**/RouteMapTilesTest.kt` (e novos testes de mapa), `core-domain/src/**/map*|tile*|Tile*|CachingTileLoader*|MapViewport*` e seus testes, `data/**/DiskTileCacheStore*|HttpTileNetwork*` e testes. Proibido: BLE, `HomeReducer`, `android-app/`, gradle wrapper, STATUS/RESUME.
Itens (TDD estrito; log em `docs/tdd/s15b.md`):
1. Estabilizar os 2 testes de `RouteMapTilesTest` com risco de flake (bytes que não decodificam; pixel (40,40)): esperar a decodificação em `Dispatchers.Default` de forma determinística (sem sleep fixo); rodar o teste 5× seguidas.
2. PNG corrompido: Robolectric decodifica qualquer byte, então injetar um decodificador (seam) para testar o ramo "tile não decodifica → indisponível + `TileLoader.invalidate`".
3. L4 (pior caso de tiles: teto de requisições), L5 (backoff após falhas de rede, sem martelar a cada abertura), L6 (cancelamento em rotação não vaza download/estado). Ver `docs/tasks/HANDOFF-MAP-OSM.md`. Sem rede real (servidor em loopback).
Aceite e regras como em TASK-S15-A (Gradle em FOREGROUND com flock, reportar EXIT; sem commit).
