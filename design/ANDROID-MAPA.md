# Mapa FigCraft → Android (Compose, Material 3) — plano, sem código ainda
| Componente/tela no FigCraft | Composable previsto | Observações |
|---|---|---|
| Frame `Home-*` | `HomeScreen` com `Scaffold(bottomBar = { LockButton })` | botão de bloqueio fixo no rodapé, área de toque ≥ 56 dp |
| `botao-principal` | `LockButton(state: LockUiState)` | estados: Locked, Unlocked, Sending, Confirmed, Failed, Connecting, Disconnected, Moving; "Confirmed" só após telemetria |
| `chip-conexao` | `ConnectionChip(state)` | |
| `info-Bateria/Velocidade/Total` | `InfoCard(icon, value, label, onClick?)` | Velocidade/Total navegam para Telemetria |
| `linha-farol`, `cartao-cruise`, `cartao-partida` | `ToggleCard(title, subtitle?, checked, pending, onToggle)` | `pending` = laranja até confirmar |
| `linha-tracao`, `linha-modo` | `SegmentedCard(options, selected, pending)` (`SingleChoiceSegmentedButtonRow`) | modo com 4 opções |
| `cartao-limite` | `SpeedLimitCard(value, range = 20..50)` | slider direto no cartão |
| Telemetria | `TelemetryScreen` (LazyColumn) | `SpeedGauge` (Canvas, arco segmentado), `PowerCard` (barras 30 s), `StatCard` |
| Viagem atual + menu ⋮ + diálogo | `TripSummaryCard`, `TopAppBar` actions + `DropdownMenu`, `AlertDialog` | zerar salva a viagem no histórico |
| Histórico / Detalhe | `HistoryScreen`, `TripDetailScreen` + `RouteMap` | fonte do mapa a decidir (OSM/offline/SDK) |
| Configurações | `SettingsScreen` | DataStore para preferências |
| Primeiro uso, Recuperação | `OnboardingScreen`, `StatusScreen(icon, title, text, actions)` | permissões BLE (`BLUETOOTH_SCAN/CONNECT`, Android 12+) |
| Tokens (variáveis Cores Claro/Escuro) | `ColorScheme` + `ExtendedColors` (success, pending, error) | seguir `lib.py` `COLORS` |
