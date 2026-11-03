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
