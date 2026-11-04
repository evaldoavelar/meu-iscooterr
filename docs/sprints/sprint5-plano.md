# Sprint 5 — Demais telas (plano, sessão 08)
Base: `docs/sprints/fase-3-plano.md` (decisões 1–8), `design/ANDROID-MAPA.md`, `design/COBERTURA.md`, `docs/design/ux-direcao.md`. Só `FakeScooterTransport`; nenhum BLE/comando real. TDD (S1–S5) e ≥ 80 % de linhas nos módulos com lógica.

## Fatias (ordem; cada uma = ramo `sprint-5/<fatia>`, TDD, `clean check` verde antes do próximo)
| # | Fatia | Domínio/lógica testável (S1–S3) | UI (S4/S5) | Dependências / decisões |
|---|---|---|---|---|
| 5.1 | **Navegação + stubs reais** | `AppDestination` (rota ↔ menu ⋮), back stack simples | `AppRoot` navega Home ↔ telas | substitui stubs "chega na Sprint 5" |
| 5.2 | **Telemetria** | Estender `Telemetry` (tensão, corrente, temp. motor 1/2, odômetro parcial — `[HIPÓTESE]`, só Fake); `TelemetryReducer`: potência = V×I, Wh/km; `TripTracker` (média, máx., duração; zerar → salva viagem) | `TelemetryScreen`, `SpeedGauge` (Canvas, arco 0–60, neon externo), `PowerCard`, `TripSummaryCard`, menu ⋮ + `AlertDialog` zerar | altimetria (GPS do celular) fica **fora**: `[HIPÓTESE]`, campo "sem leitura" |
| 5.3 | **Histórico + detalhe** | `TripRepository` (interface) + `InMemoryTripRepository` e Room/DataStore depois; resumo 30 dias; ordenação por data; vazio | `HistoryScreen`, `TripDetailScreen`, `RouteMap` sobre `MapTileSource` | **decisão da arquitetura do mapa:** interface `MapTileSource` + `OsmTileSource` (online, atribuição "© colaboradores do OpenStreetMap", cache limitado, User-Agent próprio, respeitar política); histórico textual sem internet. Dados só simulados. Rota GPS do celular = Fase 5 |
| 5.4 | **Preferências** | `Preferences` (tema, unidades km/mi, reconexão, trajeto) + `PreferencesStore` (interface; DataStore na impl.); conversão de unidades testada | `SettingsScreen`; unidades propagam à Home/Telemetria | "Esquecer patinete"/"Apagar histórico" com diálogo, só locais |
| 5.5 | **Primeiro uso + Recuperação** | `OnboardingFlow` (procurando → encontrado → conectado) e `RecoveryReason` (BT desligado, permissão negada, fora de alcance, reconectando) sobre o Fake; `PermissionState` atrás de interface (`BluetoothEnvironment`) | `OnboardingScreen` (3 passos), `StatusScreen` | permissões BLE reais só na Fase 4; aqui a interface é simulada |
| 5.6 | **Fechamento** | cobertura/gates dos novos módulos, screenshots vs `design/out/` (telas novas), `docs/sprints/sprint5-entrega.md`, lista de diferenças | | revisão cruzada (Codex ≥ 2026-11-04 09:27) + Sonnet; aprovação do proprietário |

## Regras de projeto para a sprint
- Comando nunca vira "concluído" sem telemetria (já no reducer; Telemetria/Histórico não enviam comandos).
- Nenhuma tela mostra rótulos de pesquisa, PIN, nem selo "dados de exemplo" (decisão 4 de ux-direcao).
- Zerar viagem **não** envia nada ao patinete.
- Gate de cobertura estendido a cada novo módulo/pacote de lógica; Composables medidos e reportados.
- Fonte/escala 1.5–2.0 e 360×780 dp testados em cada tela nova (decisão 5).

## Ordem sugerida de execução e paralelismo
5.1 → 5.2 (sequenciais: mexem em `Telemetry` e `AppRoot`). Depois 5.3 ∥ 5.4 podem rodar em worktrees (arquivos disjuntos), mas Gradle pesado é **um por vez** (<8 GiB livres) — na prática sequencial. 5.5 após 5.4 (usa preferências).

## Decisões `[HIPÓTESE]` a apresentar ao proprietário
Campos extras de telemetria (V, I, temperaturas) só no Fake; unidades/preferências padrão; política de cache de tiles (tamanho/validade); falhas persistem até nova tentativa e chip "Sem resposta" após recusa (item 8 da revisão Sonnet).

## Decisões do proprietário (sessão 09, 2026-11-03)
- Campos extras de telemetria (V, I, temperaturas, odômetro parcial): aprovados, **só no Fake**; sem leitura real confirmada → "sem leitura" `[HIPÓTESE]`.
- Cache de tiles OSM: **validade 30 dias**; tamanho limitado (proposta 50 MB `[HIPÓTESE]`); histórico textual funciona sem internet.
- Falhas de comando: persistem até telemetria/reconexão/nova tentativa; **sem expiração por tempo**; chip "Sem resposta" após recusa.
- Botão principal oferece **Desbloquear** com bloqueio desconhecido (nunca em movimento).
- Ressalvas da revisão Sonnet da 5.1 a tratar na 5.2: estado do `Navigator` observável/sobrevive à recriação, `open(Home)` limpa a pilha, título de Home, menu da Home iterar `AppDestination.menu`, testes de pilha ≥ 2 níveis e BackHandler.

## Pendências da revisão Sonnet da 5.2 (baixas, sessão 09)
- Telemetria velha/desconexão na tela de Telemetria: mostrar "--"/"desatualizado" após `STALE_AFTER_MS` (como a Home).
- Risco residual aceito (decisão da sessão 08): Desbloquear habilitado com telemetria velha mostrando 0 km/h.
- Duração ≥ 60 min vira "h:mm"; SpeedGauge com paddings fixos (revisar na 5.6 com fonte 2,0).
- Restaurar o texto "Ela será salva no histórico…" no diálogo de zerar quando a 5.3 salvar a viagem (hoje o `reset()` é descartado).
- Barras "últimos 30 s" do PowerCard não implementadas.

## Pendências da revisão Sonnet da 5.3 (sessão 09)
- `historyState` só recalcula "Hoje/Ontem" e a janela de 30 dias quando a lista muda; precisa de tick diário (baixo, histórico em memória).
- `InMemoryTripRepository.save`: usar `update{}`/id atômico quando houver persistência (Room); `Navigator.openTrip` dentro de `TripDetail` empilha duplicado.
- Regra a garantir por teste na Fase 5: exibir a atribuição OSM sempre que tiles forem desenhados.
- Viagem < 30 s aparece como "0 min"; data futura cai no formato "Seg 3".

## Pendências da revisão Sonnet da 5.4 (sessão 09)
- Limite de velocidade (slider/comando) continua em km/h com mi/mph selecionado: avisar na UI ou converter só o rótulo (`HomeComponents`).
- `autoReconnect`/`saveRoute` só gravam o valor; efeitos nas fases 4–5. "Esquecer patinete" só desconecta (pareamento real na Fase 4).
- `preferences` com `stateIn(Eagerly, padrão)` causará flash de tema com DataStore (Fase 4); `deviceName` fixo "Meu Patinete" em `AppRoot`; sem teste de rotação com diálogo aberto.
