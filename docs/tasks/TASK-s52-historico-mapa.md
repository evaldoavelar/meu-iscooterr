# TASK s52-historico-mapa — miniatura com mapa desfocado nos cartões do Histórico (aprovado, variante A, 2026-11-17)

**Mockup aprovado (variante A):** `design/mockups/historico-fundo-mapa-A-v1-escuro.png` e `-claro.png` (fonte `design/s52.py`). Hoje a miniatura 72 dp (`HistoryScreen.kt`) mostra o trajeto sobre um fundo azul liso. Passa a mostrar o **mapa OSM da própria viagem, desfocado**, com o trajeto **nítido por cima** (contorno escuro `#0F1A2A` ~6 px + linha `primary` ~3 px, pontos de início verde e fim ciano com borda `card`). O resto do cartão não muda. Viagem sem percurso (0 km) continua como hoje (`LastKnownBackdrop`, mapa desfocado + pino).

**Regras:**
- Reaproveitar o que já existe: `TileStorage`/cache de tiles OSM (a mesma origem do `RouteMap` do detalhe), `LastKnownBackdrop` (blur API 31+ via `Modifier.blur`/RenderEffect; API 26–30 usa downscale 1/16 como já implementado). **Sem acesso extra à internet para a lista**: usar só tiles já em cache; se não houver tile em cache, **fallback ao fundo atual** (azul liso) — nunca spinner, nunca erro visível. Não bloquear a UI: carga em IO, miniatura aparece quando pronta.
- Enquadrar a rota inteira na miniatura (mesmo cálculo de câmera do detalhe, `RouteMapCamera.kt`), zoom escolhido para caber, mapa desfocado ~4–6 dp de raio.
- Mapa OSM é claro nos dois temas; o contorno escuro do trajeto garante contraste. Atribuição OSM: manter como o app já faz para o mapa (a miniatura é decorativa; se o projeto exigir crédito na lista, seguir o padrão do `LastKnownBackdrop`).
- Desempenho: lista com rolagem; no máximo N miniaturas em composição, cache em memória da imagem desfocada por id de viagem.
- Fonte 2,0 e acessibilidade: miniatura continua sem descrição própria (decorativa), o texto do cartão é a leitura.

**Arquivos permitidos:** `app/ui/src/main/.../history/HistoryScreen.kt`, `LastKnownBackdrop.kt` (ou novo `TripThumbnail.kt` na mesma pasta), `RouteMapCamera.kt` se precisar, `app/data/.../TileStorage.kt` só para uma leitura "só cache" (sem rede), testes. Não tocar `design/`.

**Aceite (TDD):** teste do cálculo do enquadramento e do fallback sem cache (JVM), teste de UI do cartão com e sem miniatura; `cd app && ./gradlew clean check` EXIT 0. Sem push. Revisor independente (Sol).
