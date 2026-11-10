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
