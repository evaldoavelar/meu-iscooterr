# ADR 0012 — Trajeto da viagem pelo GPS: dado de localização sensível
Status: aceito · Decisão do proprietário: pedido S34 (2026-11-09), `docs/tasks/TASK-gravar-trajeto.md` · Ligação ao ViewModel/UI: S35, rodadas seguintes

## Decisão
1. O trajeto (lista de latitude/longitude da viagem) é **dado de localização sensível**: uso **somente local**, neste celular, em disco privado do app (`FileRouteStore`, `<tripId>.route`).
2. **Nunca** vai para: logs (inclusive `EventLog`), auditoria `IX7TX`, exportação/compartilhamento, rede (os tiles OSM só carregam imagens; nenhuma coordenada do trajeto é enviada). `GpsFix.toString()` omite as coordenadas para não vazarem em mensagens de erro.
3. Só grava com `Preferences.saveRoute` ligado **e** permissão de localização concedida **e** viagem em curso (`RouteGate`). Desligado, nada é guardado.
4. Limites: precisão pior que 30 m e pontos a menos de 10 m do anterior são descartados; no máximo 2.000 pontos por viagem (o armazenamento recusa ler arquivos acima de 10.000).
5. "Apagar histórico" deve chamar `RouteStore.clear()` junto com `TripRepository.clear()`; apagar uma viagem, `RouteStore.delete(id)`.

## Evidências e riscos
- `[ESTÁTICO]` código: `RouteRecorder.kt`, `FileRouteStore.kt`, testes JVM correspondentes.
- Risco residual: o arquivo fica no armazenamento privado do app, sem criptografia; quem tiver root ou backup do aparelho pode lê-lo. Aceito para uso pessoal; desligar `saveRoute` e apagar o histórico remove os dados.
- `[HIPÓTESE]` a precisão/frequência do GPS em viagem real (limiares 30 m / 10 m) precisa de validação em teste de campo.

## Adendo S45 — última posição conhecida (mapa desfocado de viagem sem percurso)
- Viagem sem percurso (0,0 km/sem rota) mostra tiles OSM **desfocados** centrados na última posição conhecida do aparelho (`LocationManager.getLastKnownLocation`), no cartão do histórico (miniatura) e no detalhe.
- A posição vive **só em memória** (`LastKnownLocation`, `toString` sem coordenadas): nunca em disco, log, `EventLog`, auditoria ou exportação. Usa só a permissão de localização já existente (FINE); **nunca** pede permissão nova. Sem permissão/posição/tiles: fundo neutro (detalhe: "Sem localização para mostrar o mapa").
- A posição **não é enviada**: só os números do tile (zoom 14, 1–4 tiles por cartão) vão ao servidor OSM, o que revela a região aproximada (~2 km) do aparelho a ele; mesmo risco já aceito para os tiles de viagens com percurso.
- `[ESTÁTICO]` `LastKnownLocation.kt`, `LastKnownBackdrop.kt`, `AndroidLastKnownLocationSource.kt` e testes JVM. `[HIPÓTESE]` o desfoque (API 31+: `Modifier.blur` de 6/12 dp + véu 0,12; API 26–30: sem RenderEffect, o tile é reduzido a 1/16 com filtro bilinear ao decodificar e ampliado suavizado, + véu 0,45) esconde o detalhe de rua o bastante, e a última posição é recente o bastante para ser útil: validar em aparelho. A miniatura não exibe a atribuição "© OpenStreetMap" (espaço de 72 dp, tiles ilegíveis); o detalhe exibe.
