package br.dev.evaldo.meuscooter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.dev.evaldo.meuscooter.ui.home.FirstUseWarning
import br.dev.evaldo.meuscooter.ui.home.HomeActions
import br.dev.evaldo.meuscooter.domain.AppDestination
import br.dev.evaldo.meuscooter.domain.Navigator
import br.dev.evaldo.meuscooter.domain.OnboardingStep
import br.dev.evaldo.meuscooter.domain.RecoveryReason
import br.dev.evaldo.meuscooter.ui.onboarding.OnboardingActions
import br.dev.evaldo.meuscooter.ui.onboarding.OnboardingScreen
import br.dev.evaldo.meuscooter.ui.recovery.RecoveryScreen
import androidx.compose.runtime.LaunchedEffect
import br.dev.evaldo.meuscooter.ui.home.HomeScreen
import br.dev.evaldo.meuscooter.ui.home.PinVaultUi
import br.dev.evaldo.meuscooter.ui.eventlog.EventLogScreen
import br.dev.evaldo.meuscooter.ui.history.HistoryScreen
import br.dev.evaldo.meuscooter.ui.history.TripDetailScreen
import br.dev.evaldo.meuscooter.ui.backup.BackupRoute
import br.dev.evaldo.meuscooter.ui.backup.RestorePromptScreen
import br.dev.evaldo.meuscooter.ui.backup.RestoreRoute
import br.dev.evaldo.meuscooter.ui.settings.SettingsActions
import br.dev.evaldo.meuscooter.ui.settings.SettingsScreen
import br.dev.evaldo.meuscooter.ui.telemetry.TelemetryScreen
import br.dev.evaldo.meuscooter.ui.theme.AppIcons
import androidx.compose.ui.graphics.graphicsLayer

/** Raiz da navegação: Home + telas (stubs até a fatia correspondente da Sprint 5). */
@Composable
fun AppRoot(
    viewModel: HomeViewModel,
    onOpenBatterySaverSettings: () -> Unit = {},
    /** Backup/restauração (S52). `null` (testes antigos): sem a tela "Já usou o app antes?" e sem as telas de backup. */
    backup: BackupViewModel? = null,
    /** Depois de restaurar: reinicia o app para o transporte real e o histórico restaurado valerem. */
    onRestartApp: () -> Unit = {},
) {
    var navigator by rememberSaveable(stateSaver = NavigatorSaver) { mutableStateOf(Navigator()) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val onboarding by viewModel.onboarding.collectAsStateWithLifecycle()
    val bluetooth by viewModel.bluetooth.collectAsStateWithLifecycle()
    val showRestorePrompt = backup != null && !prefs.onboardingDone && prefs.scooterAddress == null && !prefs.restorePromptDone
    if (backup != null && navigator.current == AppDestination.Restore) {
        // Fora do resto da cadeia: ao restaurar, `onboardingDone` vira true no meio e a tela não pode sumir antes do "Tudo restaurado".
        val restoreState by backup.restore.collectAsStateWithLifecycle()
        RestoreRoute(backup, onBack = { navigator = navigator.back() }, onOpenApp = { navigator = Navigator(); onRestartApp() })
        // Ocupado (lendo/abrindo/aplicando): Back é ignorado (a restauração não pode ser abandonada no meio).
        // Concluído: Back = "Abrir o app" (a Activity reinicia para o transporte real e o histórico restaurado valerem).
        // Demais passos: sai e o fluxo volta ao início (a próxima visita começa em Idle).
        BackHandler(enabled = !restoreState.busy) {
            val done = restoreState.step == RestoreStep.Done
            backup.onRestoreExit()
            if (done) { navigator = Navigator(); onRestartApp() } else navigator = navigator.back()
        }
    } else if (showRestorePrompt) {
        RestorePromptScreen(onRestore = { navigator = navigator.open(AppDestination.Restore) }, onStartFresh = viewModel::onRestorePromptDone)
    } else if (!prefs.onboardingDone && onboarding.step != OnboardingStep.Cancelled) {
        // Primeiro uso (ou depois de "Esquecer este patinete"). Reentrar só reinicia a busca enquanto ela ainda procura.
        LaunchedEffect(Unit) { if (viewModel.onboarding.value.step == OnboardingStep.Searching) viewModel.startOnboarding() }
        val blocker = RecoveryReason.from(bluetooth, outOfRange = false, reconnectAttempt = null)
        if (blocker != null) {
            // Sem Bluetooth/permissão não há como procurar: mesmas telas da recuperação, mas sem tentar conectar (SIMULADO).
            RecoveryScreen(
                RecoveryUiMapper.map(blocker),
                onPrimary = {
                    when (blocker) {
                        RecoveryReason.BluetoothOff -> viewModel.onEnableBluetooth()
                        RecoveryReason.LocationOff -> viewModel.onOpenLocationSettings()
                        else -> viewModel.onGrantPermission()
                    }
                },
                onSecondary = viewModel::onOpenSystemSettings,
            )
        } else {
            val found by viewModel.foundScooter.collectAsStateWithLifecycle()
            OnboardingScreen(
                onboarding, deviceName = found?.let { "${it.name} · …${it.address.takeLast(5)}" } ?: "Meu iScooterr",   // fim do MAC: distingue patinetes do mesmo modelo
                OnboardingActions(onConfirm = viewModel::onboardingConfirm, onFinish = viewModel::onboardingFinish, onCancel = viewModel::onboardingCancel),
            )
        }
    } else if (navigator.current == AppDestination.Home) {
        val pinSaved by viewModel.pinSaved.collectAsStateWithLifecycle()
        val pinNotice by viewModel.pinNotice.collectAsStateWithLifecycle()
        val keypadRequest by viewModel.pinKeypadRequests.collectAsStateWithLifecycle()
        HomeScreen(
            state,
            HomeActions(
                onMainButton = {
                    // "Procurar patinete" leva à Recuperação (design/build.py: Home-Desconectado → Recuperacao-Reconectando).
                    if (state.mainButton.kind == MainButtonKind.Search) {
                        viewModel.startRecovery(); navigator = navigator.open(AppDestination.Recovery)
                    } else viewModel.onMainButton()
                }, onConnectionChip = viewModel::onConnectionChip, onConnectionMenu = viewModel::onConnectionMenu, onUnlockPin = viewModel::onUnlockPin, onUnlockPinAndSave = { viewModel.onUnlockPin(it, saveAfterUnlock = true) },
                onUseSavedPin = viewModel::onUseSavedPin, onForgetPin = viewModel::onForgetPin, onHeadlight = viewModel::onHeadlight, onMotors = viewModel::onMotors,
                onMode = viewModel::onMode, onCruise = viewModel::onCruise, onZeroStart = viewModel::onZeroStart,
                onLimit = viewModel::onLimit, onOpen = { if (it == AppDestination.Telemetry) viewModel.onTelemetryOpened(); navigator = navigator.open(it) },
                onAckWarning = { if (it == FirstUseWarning.Cruise) viewModel.onAckCruiseWarning() else viewModel.onAckZeroStartWarning() },
            ),
            pinVault = PinVaultUi(viewModel.pinVaultAvailable, pinSaved, pinNotice, keypadRequest),
            warningsAcked = setOfNotNull(FirstUseWarning.Cruise.takeIf { prefs.cruiseWarningAck }, FirstUseWarning.ZeroStart.takeIf { prefs.zeroStartWarningAck }),
        )
    } else {
        val backupBusy = backup?.backup?.collectAsStateWithLifecycle()?.value?.busy == true
        BackHandler(enabled = !(navigator.current == AppDestination.Backup && backupBusy)) {
            if (navigator.current == AppDestination.Recovery) viewModel.cancelRecovery()
            if (navigator.current == AppDestination.Backup) backup?.onBackupExit()
            navigator = navigator.back()
        }
        val telemetry by viewModel.telemetryState.collectAsStateWithLifecycle()
        if (navigator.current == AppDestination.Telemetry) {
            TelemetryScreen(
                telemetry, onBack = { navigator = navigator.back() },
                onPlayPause = viewModel::onTripPlayPause, onStop = viewModel::onTripStop, onReset = viewModel::onTripReset, onSpeedSource = viewModel::onSpeedSource,
                onOpenHistory = { navigator = navigator.open(AppDestination.History) },
                onOpenBatterySaverSettings = onOpenBatterySaverSettings,
            )
        } else if (navigator.current == AppDestination.Settings) {
            val prefs by viewModel.preferences.collectAsStateWithLifecycle()
            SettingsScreen(
                prefs, deviceName = "Meu iScooterr",
                SettingsActions(
                    onBack = { navigator = navigator.back() }, onTheme = viewModel::onTheme, onUnits = viewModel::onUnits,
                    onAutoReconnect = viewModel::onAutoReconnect, onSaveRoute = viewModel::onSaveRoute, onStartTripOnOpen = viewModel::onStartTripOnOpen, onLockWithPin = viewModel::onLockWithPin,
                    onForget = { viewModel.onForgetScooter(); navigator = Navigator() }, onClearHistory = viewModel::onClearHistory,
                    onBackup = { navigator = navigator.open(AppDestination.Backup) }, onRestore = { navigator = navigator.open(AppDestination.Restore) },
                ),
                airLockNotice = viewModel.airLockNotice.collectAsStateWithLifecycle().value,
            )
        } else if (navigator.current == AppDestination.Backup && backup != null) {
            BackupRoute(backup, onBack = { navigator = navigator.back() })
        } else if (navigator.current == AppDestination.History) {
            val history by viewModel.historyState.collectAsStateWithLifecycle()
            HistoryScreen(
                history, onBack = { navigator = navigator.back() }, onOpenTrip = { navigator = navigator.openTrip(it) },
                routeOf = { viewModel.trip(it)?.route.orEmpty() },
                onDeleteTrip = viewModel::onDeleteTrip,
            )
        } else if (navigator.current == AppDestination.EventLog) {
            val events by viewModel.eventLog.events.collectAsStateWithLifecycle()
            EventLogScreen(events, viewModel.zone, viewModel.wallNowMs(), onBack = { navigator = navigator.back() }, onClear = viewModel::onClearEventLog)
        } else if (navigator.current == AppDestination.TripDetail) {
            val id = navigator.tripId
            val detail = id?.let(viewModel::tripDetail)
            if (detail != null) TripDetailScreen(detail, viewModel.trip(id).let { it?.route.orEmpty() }, onBack = { navigator = navigator.back() })
            else StubScreen("Viagem não encontrada", onBack = { navigator = navigator.back() })
        } else if (navigator.current == AppDestination.Recovery) {
            val reason by viewModel.recovery.collectAsStateWithLifecycle()
            val current = reason
            // Nada mais a recuperar (reconectou ou foi cancelado): sai da tela.
            LaunchedEffect(current) { if (current == null) navigator = navigator.back() }
            if (current != null) {
                RecoveryScreen(
                    RecoveryUiMapper.map(current),
                    onPrimary = {
                        when (current) {
                            RecoveryReason.OutOfRange -> viewModel.startRecovery()
                            RecoveryReason.BluetoothOff -> { viewModel.onEnableBluetooth(); viewModel.startRecovery() }
                            RecoveryReason.PermissionDenied -> { viewModel.onGrantPermission(); viewModel.startRecovery() }
                            RecoveryReason.LocationOff -> { viewModel.onOpenLocationSettings(); viewModel.startRecovery() }
                            is RecoveryReason.Reconnecting -> { viewModel.cancelRecovery(); navigator = navigator.back() }
                        }
                    },
                    onSecondary = viewModel::onOpenSystemSettings,
                )
            }
        } else {
            StubScreen(navigator.current.label, onBack = { navigator = navigator.back() })
        }
    }
}

@Composable
private fun StubScreen(title: String, onBack: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, Modifier.size(48.dp)) {
                    Icon(AppIcons.Chevron, contentDescription = "Voltar", Modifier.graphicsLayer { scaleX = -1f })
                }
                Text(title, Modifier.testTag("stub-title"), style = MaterialTheme.typography.headlineSmall)
            }
            Text("Esta tela chega na Sprint 5.", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private val NavigatorSaver = listSaver<Navigator, String>(save = { it.names }, restore = { Navigator.fromNames(it) })
