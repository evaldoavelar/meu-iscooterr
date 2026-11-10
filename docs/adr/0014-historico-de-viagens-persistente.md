# ADR 0014 — Histórico de viagens persistente
Status: aceito · Tarefa S37

## Decisão
1. Com o patinete real (`realBle`), `TripRepository` é `FileTripRepository`, em `filesDir/trips.v1`. O histórico começa **vazio**; `SampleTrips` só existe no modo simulado (InMemory).
2. Formato próprio em texto, sem dependência nova: cabeçalho `TRIPS1` e uma linha por viagem, `id|startedAtMs|distanceKm|durationMs|avgKmh|maxKmh|avgWhPerKm|batteryStart|batteryEnd` (`-` = nulo).
3. Escrita atômica (arquivo `.tmp` + rename), toda mutação serializada por `Mutex`. `nextId` = maior id + 1 ao abrir; `save` com id existente substitui.
4. Tolerância a falha: arquivo ausente, sem cabeçalho ou corrompido lê como lista vazia; linha inválida, com valor não finito (NaN/infinito) ou com id repetido é ignorada. Falha de escrita não lança (a sessão segue em memória). `save` de viagem com valor não finito não grava e devolve 0.
5. `clear()` ("Apagar histórico") apaga o arquivo; `RouteStore.clear()` continua sendo chamado junto (ADR 0012).

## Limites e riscos
- `Trip.route` **nunca** é gravada aqui: coordenadas são dado sensível e ficam só no `FileRouteStore` (ADR 0012); o HomeViewModel junta os dois na leitura.
- Sem criptografia: o arquivo fica no armazenamento privado do app; quem tiver root ou backup do aparelho lê distância, duração, velocidades e horário das viagens. Aceito para uso pessoal; "Apagar histórico" remove os dados.
- Sem migração: mudança de formato exige novo cabeçalho/arquivo (`trips.v2`). Sem limite de tamanho (uso pessoal, poucas viagens).
