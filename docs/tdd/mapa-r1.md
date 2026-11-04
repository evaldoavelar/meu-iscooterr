# TASK-MAP-OSM — correções da revisão r1 (vermelho → verde)
Vermelho = falha observada antes da correção correspondente.
| Achado | Teste | Vermelho observado | Verde |
|---|---|---|---|
| B1 | `TileStorageTest` http 200 + corpo `<html>` lança IOException | `FAILED` (1 de 8 falhou; fetch devolvia o HTML) | checagem de assinatura PNG em `HttpTileNetwork`; testes antigos passaram a servir bytes com assinatura PNG |
| B1 | http com Content-Length 100 e corpo curto lança IOException | `FAILED` (1 de 9) | comparação com `contentLengthLong` |
| A1 | `TileLoaderTest` falha ao gravar no disco devolve Loaded e não repete download | `FAILED` (1 de 11) | try separado para persistência |
| A1 | `DiskTileCacheStore` apaga `.tmp` órfão ao abrir | `FAILED` (1 de 11, com a linha de limpeza removida) | limpeza no `init` |
| A1 | falha de rename não deixa `.tmp` | verde direto (código já tratava); teste de caracterização | — |
| B1 (UI) | `TileLoader.invalidate` apaga o cache | `Unresolved reference 'invalidate'` | `CachingTileLoader.invalidate` |
| B1 (UI) | bytes que não decodificam → aviso offline + `invalidate` | `Too many arguments for constructor MapTiles` (compilação) | `MapTiles.decode` injetável + `invalidate` no RouteMap |
| L1 | pixel (40,40) do mapa é a cor do tile | NPE de `Bitmap` em modo gráfico legado | `@GraphicsMode(NATIVE)` na classe; verde |
| L2/L3/L7 | atribuição some ao mudar tamanho; toque abre openstreetmap.org/copyright; ≤ 24 tiles em 1080×682 | escritos junto da implementação (vermelho não observado isoladamente); na 1ª rodada 8 testes falharam por `remember(viewport)` sem equals (corrigido com chave por tamanho) | verde |
