# S49 — altimetria GPS no detalhe

Implementação no worktree `s49-alt`; sem Gradle, git, Bluetooth, dispositivo ou alteração de permissões pelo worker. Decisões no adendo S49 do ADR 0012.

- `[ESTÁTICO]` Leitura e inspeção dos seis PNGs aprovados em `/home/evaldo/Documents/ix7/design/mockups/altimetria-v1-{preenchida,sem-leitura,preenchida-fonte2}-{claro,escuro}.png`, além de `AGENTS.md`, `CLAUDE.md`, ADR 0012/0013, `design/s48.md` e `design/s48.py`. O novo card mantém posição entre grid e bateria, área ciano a 12%, perfil por distância e métricas empilhadas na fonte 2,0 (`TripAltimetryCard.kt`).
- `[ESTÁTICO]` Fluxo rastreado: `AndroidGpsFixSource.onLocationChanged` → `gpsFixOf` → `HomeViewModel`/`RouteRecorder.offer` → `GeoPoint` → `FileRouteStore.write/read` → `HomeViewModel.trips` → `HistoryUiMapper.detail` → `TripDetailScreen`/`TripAltimetryCard`. O ViewModel já transporta a lista inteira, sem reconstruir pontos; não exigiu mudança de produção. Altitude passa pelo mesmo portão da rota.
- `[ESTÁTICO]` Testes escritos antes dos respectivos comportamentos: altitude opcional e texto redigido (`RouteRecorderTest`); contrato V2, compatibilidade e migração V1→V2, corrupção e exclusão (`FileRouteStoreTest`); ausência, ruído, distância, lacunas, leitura isolada, nível negativo e terreno plano (`TripAltimetryTest`); unidades/normalização (`HistoryUiMapperTest`); salvar/pausar/excluir (`HomeViewModelRouteTest`); claro/escuro, fonte 2,0, traço/preenchimento e capturas (`TripAltimetryScreenTest`). **Ciclo vermelho/verde não executado**, pois o coordenador é responsável por rodar os testes neste ambiente.
- `[ESTÁTICO]` `ScreensScreenshotTest` foi atualizado: referências anteriores a S49 comparam somente o trecho do detalhe até as estatísticas, mantendo o denominador do MAD e os tetos existentes; captura também o novo card sem leitura. `TripAltimetryScreenTest` verifica os seis casos novos por semântica, ausência de corte, geometria do traço e cor do preenchimento, salvando recortes em `app/ui/build/screenshots/{claro,escuro}/altimetria-v1-*-card.png`. A fixture visual transcreve os valores explícitos dos PNGs; não usa o cálculo do domínio para produzir esperados. Os PNGs aprovados e o protótipo não foram modificados.
- `[ESTÁTICO]` Executada conferência local com Python dos 17 caminhos listados abaixo: existência, newline final, espaços finais e marcadores de conflito, sem problemas encontrados. Leituras por `rg`/`cat`/`sed`, inspeção de PNGs por `view_image` e edições por `apply_patch`; nenhum teste Kotlin executado. Essa conferência não equivale a compilação nem revisão independente.

## Pendências para o coordenador

- `[DESCONHECIDO]` Compilação, testes JVM/Compose, cobertura ≥80% e `clean check`: não executados pelo worker. Não há alegação de testes verdes nem aprovação independente.
- `[DESCONHECIDO]` Comparação visual final das capturas Compose com os seis PNGs: aguarda execução. Os testes novos conferem estrutura e pixels do gráfico, sem limiar global de similaridade calibrado nem referência nova gerada pelo app.
- `[HIPÓTESE]` Histerese de 3 m: validar precisão em campo. Referência independente: fixture `design/s48.md` tem +18/−12 brutos; filtrada resulta +16/−11, ver teste e ADR. O usuário autorizou filtro simples, sem valor exato obrigatório.
- `[ESTÁTICO]` A pausa continua ligando os pontos GPS de antes/depois por reta (ADR 0013, S36); a altitude entre esses pontos também não tem marcador de pausa no formato da rota. O teste descarta a altitude recebida durante a pausa. Não se acrescentou segmentação temporal ao escopo S49.

## Arquivos alterados

- `app/android-app/src/main/kotlin/br/dev/evaldo/meuscooter/AndroidGpsFixSource.kt`
- `app/core-domain/src/main/kotlin/br/dev/evaldo/meuscooter/domain/TripHistory.kt`
- `app/core-domain/src/main/kotlin/br/dev/evaldo/meuscooter/domain/RouteRecorder.kt`
- `app/core-domain/src/main/kotlin/br/dev/evaldo/meuscooter/domain/TripAltimetry.kt` (novo)
- `app/core-domain/src/test/kotlin/br/dev/evaldo/meuscooter/domain/RouteRecorderTest.kt`
- `app/core-domain/src/test/kotlin/br/dev/evaldo/meuscooter/domain/TripAltimetryTest.kt` (novo)
- `app/data/src/main/kotlin/br/dev/evaldo/meuscooter/data/FileRouteStore.kt`
- `app/data/src/test/kotlin/br/dev/evaldo/meuscooter/data/FileRouteStoreTest.kt`
- `app/ui/src/main/kotlin/br/dev/evaldo/meuscooter/ui/HistoryUiMapper.kt`
- `app/ui/src/main/kotlin/br/dev/evaldo/meuscooter/ui/history/TripDetailScreen.kt`
- `app/ui/src/main/kotlin/br/dev/evaldo/meuscooter/ui/history/TripAltimetryCard.kt` (novo)
- `app/ui/src/test/kotlin/br/dev/evaldo/meuscooter/ui/HistoryUiMapperTest.kt`
- `app/ui/src/test/kotlin/br/dev/evaldo/meuscooter/ui/HomeViewModelRouteTest.kt`
- `app/ui/src/test/kotlin/br/dev/evaldo/meuscooter/ui/ScreensScreenshotTest.kt`
- `app/ui/src/test/kotlin/br/dev/evaldo/meuscooter/ui/TripAltimetryScreenTest.kt` (novo)
- `docs/adr/0012-trajeto-gps-dado-sensivel.md`
- `docs/research/s49-altimetria-gps.md` (novo)
