# ADR 0016 — Registro passivo de modo de condução e tração na viagem
Status: aceito · Tarefa S43-MODES · Fase 5 / Sprint 43 · Extensão dos ADRs 0012, 0013 e 0014

## Contexto e motivação
- O patinete iScooter iX7 possui modos de condução (0 = Walking/Pedestre, 1 = Eco, 2 = Race, 3 = Sport; confirmados no painel pelo proprietário em S35 e ADR 0009) e seleção de tração (1 motor vs. 2 motores, byte 10 bit 4 no Meter1, confirmado em S31).
- No protótipo FigCraft aprovado em S43 (`design/home.figcraft`, `design/s43.py`), a tela de detalhes da viagem (`TripDetailScreen`) exibe:
  1. Seletor de visualização do mapa: "Modo", "Motor", "Nenhum";
  2. Trajeto colorido por segmentos de modo ou tração;
  3. Linha do tempo com instantes e transições de modo/motor;
  4. Resumo de tempo (duração em ms/min e porcentagem) em cada modo de condução.
- O app deve registrar essas trocas durante a viagem de forma puramente passiva (leitura de telemetria Meter1, sem enviar nenhum comando BLE de controle ou alteração ao patinete).

## 1. Modelo de eventos
- Modelo imutável no `core-domain`:
  `data class TripModeEvent(val elapsedMs: Long, val mode: Int?, val dualMotor: Boolean?)`
- **Campos**:
  - `elapsedMs`: instante relativo ao início da viagem em milissegundos ativos (0 <= elapsedMs <= durationMs). Segue a mesma régua do `TripTracker` (não avança durante pausas, garantindo que a soma dos tempos dos segmentos coincida exatamente com a duração ativa da viagem).
  - `mode`: modo de condução bruto (0 = Walking, 1 = Eco, 2 = Race, 3 = Sport; `null` = sem leitura / desconhecido).
  - `dualMotor`: tração (`false` = 1 motor, `true` = 2 motores, `null` = sem leitura / desconhecido).
- A entidade de domínio `Trip` ganha a propriedade `val modeEvents: List<TripModeEvent> = emptyList()`.

## 2. Regra de gravação
- **Somente viagem ativa (`TripRecState.Running`)**:
  - Em estado `Stopped` ou `Paused`, nenhum evento é gravado.
  - Durante `Paused`, as amostras são congeladas/descartadas assim como no `TripTracker` (ADR 0013) e `RouteRecorder` (ADR 0012).
  - Ao retomar (`play()` da pausa), amostras de Meter1 recebidas durante a pausa são descartadas: só se registra evento após o primeiro Meter1 FRESCO pós-retomada (`meter1AtMs` mais novo que o último visto até a retomada; um Meter2 que chega antes não conta). Se o modo/tração mudou na pausa, a transição entra no instante desse quadro, com `elapsedMs` ancorado no tempo ativo.
  - O maior `meter1AtMs` visto é preservado também durante a pausa (quadro atrasado não o rebaixa).
  - Modo bruto fora de 0..3 (ruído; o decoder extrai 3 bits) é gravado como `mode = null` (sem leitura), mantendo a tração e o restante do histórico (ex.: 1 → 4 → 1 vira Eco, sem leitura, Eco).
  - Quadros com tempo regressivo (`receivedAtMs` menor que o último aceito) são rejeitados também para os eventos, como no `TripTracker`.
- **Entrada inicial + uma por mudança**:
  - O primeiro quadro com Meter1 fresco recebido em `Running` grava a entrada inicial. Se chegar com `elapsedMs = 0`, é um único evento. Se a 1ª leitura for tardia (`elapsedMs > 0`), o intervalo anterior fica "sem leitura": evento `(0, null, null)` seguido do evento na leitura real.
  - Quadros subsequentes só registram uma nova entrada se houver alteração efetiva de modo (`mode != lastMode`) ou tração (`dualMotor != lastDualMotor`).
  - Viagem com modo único possui exatamente 1 evento (`modeEvents.size == 1`), cobrindo 100% da duração da viagem sem redundância de dados.
- **Ignora Meter1 velho/obsoleto**:
  - Quadros com Meter1 obsoleto (`now - meter1AtMs > HomeRules.STALE_AFTER_MS` ou sem atualização do grupo Meter1) são ignorados pelo gravador de eventos de modo. Não geram transições espúrias nem registros nulos.
- **Debounce de frame ruidoso**:
  - O protocolo BLE do iX7 protege todos os quadros com CRC-16/Modbus (confirmado em S2 e S31). Quadros corrompidos no enlace são rejeitados antes do decodificador.
  - No firmware do patinete, a transição em `d[10]` é atômica. Um debounce de 1 frame adicionaria atraso artificial de ~500 ms (um período de telemetria) na anotação temporal da troca. Portanto, a gravação imediata no 1º quadro íntegro e fresco é a decisão correta e suficiente.

## 3. Persistência e migração retrocompatível
- **Armazenamento no `FileTripRepository` (`trips.v1` / formato texto)**:
  - O registro de modo e tração é telemetria do patinete, não coordenada geográfica. Deve ser salvo mesmo quando o GPS estiver desativado (`saveRoute = false` ou sem permissão de localização).
  - Portanto, os eventos pertencem ao histórico da viagem (`FileTripRepository`), e NÃO ao `FileRouteStore`.
  - Cabeçalho atualizado para `TRIPS2|<próximo id>`.
  - Formato da linha:
    `id|startedAtMs|distanceKm|durationMs|avgKmh|maxKmh|avgWhPerKm|batteryStart|batteryEnd|modeEvents`
  - Serialização de `modeEvents`: lista separada por vírgula no formato `elapsedMs:mode:dualMotor` (onde `-` representa nulo, e dualMotor usa `1` ou `0`). Se a lista estiver vazia, grava `-`.
  - Exemplo: `1|1700000000000|5.0|600000|20.0|25.0|12.5|90|80|0:1:0,120000:3:1`
- **Validação estrita (leitura e gravação)**: cada item é `tempo:modo:motor` com tempo inteiro sem sinal, modo `-` ou 0..3, motor `-`, `0` ou `1`; tempos `0 <= t <= durationMs` em ordem não decrescente. Qualquer item inválido descarta TODOS os eventos da viagem (nunca a viagem): ela vira "sem eventos". No `save` a lista inválida também é gravada como `-`.
- **Migração retrocompatível**:
  - Arquivos legados com cabeçalho `TRIPS1` (ou linhas com 9 colunas) continuam sendo lidos perfeitamente.
  - Para viagens legadas sem a 10ª coluna, `modeEvents` é preenchido com `emptyList()`.
  - Na UI, viagens com `modeEvents.isEmpty()` são exibidas como "Modo não registrado" (com toast / indicação visual "Sem informação de modo de condução", desabilitando os seletores Modo/Motor no mapa e exibindo a rota em traço neutro).
  - Ao persistir novas viagens ou regravações, o repositório adota o formato `TRIPS2`, preservando todos os registros e o contador monotônico `nextId`.

## 4. Privacidade e ciclo de vida
- **Local apenas**: os eventos de modo e tração permanecem exclusivamente no disco privado do aplicativo (`filesDir/trips.v1`). Nunca são exportados, enviados para rede ou incluídos em logs sensíveis (`EventLog`, `IX7TX`).
- **Exclusão atômica**:
  - Apagar uma viagem (`TripRepository.delete(id)`) apaga a linha da viagem e seus eventos de modo atomicamente.
  - "Apagar histórico" (`TripRepository.clear()`) remove todas as viagens e seus eventos associados, mantendo o contador monotônico.

## 5. Consulta para a UI (funções puras de segmentação)
- `analyzeTripModes(route: List<GeoPoint>, events: List<TripModeEvent>, totalDurationMs: Long): TripModeAnalysis`
- Devolve:
  1. `modeSegments: List<ModeSegment>`: fatias do trajeto associadas a cada modo, com os pontos de rota interpolados pela extensão percorrida (conforme algoritmo `sub(pts, f0, f1)` do FigCraft `design/s43.py`), instantes de início/fim e duração.
  2. `motorSegments: List<MotorSegment>`: fatias do trajeto contíguas por tração (1 motor vs. 2 motores).
  3. `modeTimes: List<ModeTimeSummary>`: tempo acumulado (em ms) e porcentagem inteira em cada modo, pelo método do maior resto (empate: ordem de aparição): a soma é exatamente 100.
- Com `events` vazio: devolve segmentos vazios e `modeTimes` vazio, indicando "Modo não registrado".
