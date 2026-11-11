# ADR 0013 — Estado da viagem (Play/Pause/Parar/Reset) e preferência "Iniciar viagem ao abrir a Telemetria"
Status: aceito e implementado (S35) · Decisão do proprietário: 2026-11-10 · Mockup aprovado: `design/mockups/telemetria-barra-fixa-contando.png` e `...-pausada.png` · Local: nada aqui envia comando ao patinete (nenhum `TxCommand`, nenhum `ScooterTransport.send`).

## Decisão
1. **Máquina de estados no domínio** (`TripSession`, `TripRecState` = `Running | Paused | Stopped`), dona do `TripTracker`. O estado vive no `HomeViewModel` (`tripRecState`), não em `remember`: sobrevive a rotação e recomposição.
2. **Contando**: acumula como antes. **Pausada**: congela duração, distância (odômetro da viagem), velocidade média/máxima, energia e perfil de altimetria; as amostras recebidas durante a pausa são descartadas e, ao retomar, a primeira amostra só reancora (o intervalo pausado não conta na duração nem integra distância; `TripTracker.pause/resume`). **Parada**: nada acumula; a tela mostra 00:00.
3. **Play/Pause** alterna: contando → pausada; pausada → contando; parada → contando (viagem nova, início = 1ª amostra).
4. **Parar** encerra a viagem, **salva no histórico** (mesma regra de "Zerar viagem": só se houver distância e duração > 0) e deixa a tela parada em 00:00. **Reset** (com diálogo de confirmação) descarta a viagem em curso **sem salvar** e deixa a tela parada em 00:00. Só Parar e o menu "Zerar viagem" gravam no histórico.
5. **Zerar viagem** (menu ⋮) continua: salva e recomeça vazia **mantendo** o estado atual (contando/pausada/parada).
6. **Preferência** `Preferences.startTripOnOpen` (chave persistida `startTripOnOpen`, padrão `true`): vale a cada **abertura** da Telemetria, não na criação do ViewModel. A sessão nasce `Stopped`; `HomeViewModel.onTelemetryOpened()`, chamado pela navegação do `AppRoot` ao abrir a tela (nunca por efeito de composição, para a rotação não reiniciar uma viagem recém-Parada), faz `play()` se a preferência estiver ligada e a viagem estiver parada. Contando/pausada não muda. **Reabrir a Telemetria depois de Parar (ou Reset) inicia uma viagem nova** com a preferência ligada; desligada, abre parada até o Play. Mudar a preferência vale na próxima abertura. Dados antigos sem a chave (ou valor corrompido) leem `true`: sem migração.
7. **UI** (conforme mockup v2): barra fixa Reset | Play/Pause | Parar (Reset desabilitado com a viagem parada, pois não há o que descartar) no rodapé (conteúdo rola por trás, com folga final igual à altura da barra e respeito à barra de gestos), áreas de toque >= 48 dp, `testTag` `trip-reset`/`trip-play-pause`/`trip-stop`; card Duração, largura total, primeiro abaixo do velocímetro, com "Contando" (verde) / "Pausada" (laranja) / "Parada"; Altimetria em largura total no card "Viagem atual", estilo do Consumo, mantendo as barras (`altitude-bars`) e `altitude-chart`/`altitude-empty`.

## Limitações conhecidas
- Em pausa, o perfil de altimetria também deve congelar quando houver fonte de altitude; hoje o app não alimenta altitude (`altitudesM` vazio, "Sem leitura"), então nada muda na prática.
- A barra de status "Contando" não distingue falta de telemetria; telemetria velha continua mostrada pelo aviso "Dados desatualizados".
- Morte do processo: o `Navigator` é restaurado com `rememberSaveable` (AppRoot). Se o sistema matar o processo com a Telemetria aberta, o app pode reabrir já nela com um ViewModel novo (viagem `Stopped`) e `onTelemetryOpened` não roda: a viagem fica parada até tocar em Play, mesmo com a preferência ligada. Aceito (não é de segurança); revisor S35.
- Voltar do Histórico (ou de qualquer tela) não reabre a Telemetria, então não reinicia a viagem; só a navegação Home → Telemetria chama `onTelemetryOpened`. Não há teste do fio AppRoot → ViewModel, só do ViewModel.

## Trajeto GPS e Pausa (S36)
- **Pausa NÃO grava pontos**: o `RouteGate` só abre com `TripRecState.Running` (`tripActive`). Ao retomar, o próximo ponto liga-se ao último gravado por uma reta (o trecho percorrido pausado fica sem pontos, coerente com a distância congelada). Parada/Reset também fecham o portão.
- Trajeto: `RouteRecorder` zera ao iniciar viagem nova (Parada → Play) e em Parar/Reset/Zerar; Parar/Zerar gravam `RouteStore.write(id, pontos)` só se `saveRoute` ainda estiver ligado (ADR 0012); Reset descarta. O detalhe lê `Trip.route` do store (`HomeViewModel.trips`), sem alterar a UI: `RouteMap` já desenha a polilinha.

## Adendo S41 (2026-11-10) — Salvamento de viagem sem distância
- **Regra nova**: `TripSession.closeTrip()` salva a viagem no histórico sempre que houver mais de uma amostra e duração > 0 (`durationMs > 0`), mesmo com distância zero (`distanceKm == 0f`). Amostra única (duração 0) ou viagem sem amostras (`null`) continuam descartadas sem salvar.
- **Motivo**: teste com patinete no cavalete (roda girando, celular parado). Como a velocidade da viagem vem do GPS do celular (ADR 0005) e este permanece em 0 km/h com o aparelho imóvel, a distância acumulada era 0 e a viagem era descartada em silêncio ao Parar, apesar de watts e duração funcionarem.
- **Consequência**: Play → Parar rápido (com ao menos duas amostras e duração > 0) cria uma viagem de 0,0 km no histórico, que o usuário pode apagar com "Apagar histórico de viagens" (apaga todas); a exclusão individual por deslize entra na tarefa S41-TRIP-DELETE (ADR 0014).
- **Iniciar ao abrir + Zerar**: o salvamento de viagens com distância 0 e duração > 0 aplica-se tanto a "Parar" quanto a "Zerar viagem" (menu ⋮) e a viagens iniciadas pela preferência "Iniciar viagem ao abrir a Telemetria".

## Adendo S41b: bateria em pílula e gráfico de consumo (2026-11-10)
- **Bateria** sai do tile ao lado do consumo e vira a pílula sob o velocímetro (180x38 dp, contorno primário, ícone verde, valor, separador, "Bateria"), como em `design/telemetry.py`. Semântica `Bateria 82%` (ou `Bateria --` sem leitura/vencida, com cor neutra) e `testTag` `battery-pill`.
- **Consumo em tempo real** vira card de largura total: valor em W, Wh/km (ou "Parado · sem consumo") e gráfico de 24 barras "últimos 30 s".
- **Dados reais**: `PowerHistory` (core-domain, pura) guarda 30 s de `powerW` por `receivedAtMs` monotônico; `bars(24)` = média por fatia de 1,25 s normalizada pelo máximo da janela; fatia sem amostra = 0; stale, tempo regressivo, NaN/inf não entram. O `HomeViewModel` alimenta a cada Telemetry, independente de a viagem estar contando/pausada, e expõe `powerBars` (vazio sem dado/obsoleto). Parado/sem dado: barras cinza de 3 dp.
- Nada aqui envia comando ao patinete.

## Adendo S43: gráfico de consumo em escala absoluta fixa 0–750 W (2026-11-11)
- **Substitui a normalização do adendo S41b** (barras normalizadas pelo máximo da janela, que fazia 60 W parecerem tão "cheios" quanto 600 W). Aprovado pelo proprietário (mockup `design/mockups/consumo-escala-fixa-v1-{claro,escuro}.png` e `-fonte2-v1`; fonte `design/s43.py`).
- **Regra**: `PowerHistory.bars(24)` = potência média da fatia de 1,25 s / `FULL_SCALE_W` (750 W), com clamp em 0..1. Valor acima de 750 W enche a barra; fatia sem amostra = 0. A linha tracejada marca o valor atual (barra mais recente); fio claro no teto com rótulo "750 W".
- **Estados**: parado (0 W: barras mínimas de 2 dp em cor neutra, linha na base); 60 W constantes (barras baixas e estáveis, 0,08); aceleração; sem dado (`-- W`, subtítulo "Sem dado do patinete", sem barras, linha de base tênue).
- **A11y**: o gráfico tem uma única leitura-resumo qualitativa em relação ao teto de 750 W; o valor em W continua só no cartão (sem leitura duplicada).
- **Fonte 2,0**: com `fontScale >= 1,75` o gráfico vai para baixo do texto em largura total, sem corte.
- `PowerHistory` segue com dados reais; decoder e velocímetro não foram tocados. Nada aqui envia comando ao patinete.
