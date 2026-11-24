package br.dev.evaldo.meuscooter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.dev.evaldo.meuscooter.domain.PinLoadResult
import br.dev.evaldo.meuscooter.domain.PinSaveResult
import br.dev.evaldo.meuscooter.domain.PinVault
import br.dev.evaldo.meuscooter.domain.Preferences
import br.dev.evaldo.meuscooter.domain.PreferencesStore
import br.dev.evaldo.meuscooter.domain.backup.BackupApplyResult
import br.dev.evaldo.meuscooter.domain.backup.BackupCodec
import br.dev.evaldo.meuscooter.domain.backup.BackupContents
import br.dev.evaldo.meuscooter.domain.backup.BackupError
import br.dev.evaldo.meuscooter.domain.backup.BackupException
import br.dev.evaldo.meuscooter.domain.backup.BackupHeader
import br.dev.evaldo.meuscooter.domain.backup.BackupPreferences
import br.dev.evaldo.meuscooter.domain.backup.BackupResult
import br.dev.evaldo.meuscooter.domain.backup.BackupStore
import br.dev.evaldo.meuscooter.protocol.Pin
import java.nio.CharBuffer
import java.security.SecureRandom
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Máquina de estados de "Fazer backup" e "Restaurar backup" (ADR 0017, S52). Tudo que é E/S ou criptografia roda em [ioDispatcher];
 * a UI só observa [backup]/[restore]. Senha e PIN existem só como `CharArray`/[Pin] zerados depois do uso: nunca vão a estado, log,
 * `toString` nem a qualquer Intent. Quem chama entrega a senha e o ViewModel a zera (mesmo em erro).
 */
class BackupViewModel(
    private val store: BackupStore,
    private val documents: BackupDocuments,
    private val pinVault: PinVault,
    private val preferencesStore: PreferencesStore,
    private val host: BackupHost,
    private val appVersion: String,
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val random: SecureRandom = SecureRandom(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) : ViewModel() {
    private val _backup = MutableStateFlow(BackupUiState())
    val backup: StateFlow<BackupUiState> = combine(_backup, host.tripRecording) { s, rec -> s.copy(recording = rec) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BackupUiState())

    private val _restore = MutableStateFlow(RestoreUiState())
    val restore: StateFlow<RestoreUiState> = combine(_restore, host.tripRecording) { s, rec -> s.copy(recording = rec) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RestoreUiState())

    private var backupJob: Job? = null
    private var restoreJob: Job? = null
    private var pendingBytes: ByteArray? = null
    private var pendingName = ""

    private var fileBytes: ByteArray? = null
    private var header: BackupHeader? = null
    private var contents: BackupContents? = null

    // ============================================================ Fazer backup

    /** Ao abrir a tela: conta o que vai no backup (leitura em E/S). */
    fun onBackupOpened() {
        // Idempotente: recomposição/rotação no meio do fluxo (seletor aberto, gravando, "Backup salvo") não reinicia nada.
        if (backupJob?.isActive == true || _backup.value.step != BackupStep.Editing) return
        _backup.update { it.copy(pinSaved = pinVault.hasSavedPin.value, hasScooter = preferencesStore.current.scooterAddress != null) }
        backupJob = viewModelScope.launch {
            val trips = try {
                withContext(ioDispatcher) { store.snapshot(emptyMap(), null).let { c -> try { BackupCodec.countTrips(c.tripsFile) } finally { c.wipe() } } }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            _backup.update { it.copy(tripCount = trips ?: 0, countFailed = trips == null) }
        }
    }

    /**
     * "Salvar Backup": lê o PIN do cofre (biometria) se houver, cifra em E/S e pede à tela o seletor de destino.
     * [password] é zerado ao terminar. Com [includePin] = false salva sem o PIN (oferecido só se o cofre não pôde ser lido).
     */
    fun onSaveClicked(password: CharArray, includePin: Boolean = true) {
        val s = _backup.value
        if (backupJob?.isActive == true || s.step != BackupStep.Editing || host.tripRecording.value || password.size < BackupCodec.MIN_PASSWORD) {
            password.fill('\u0000'); return
        }
        _backup.update { it.copy(failure = null) }
        backupJob = viewModelScope.launch {
            var pin: CharArray? = null
            try {
                if (includePin && pinVault.hasSavedPin.value) {
                    _backup.update { it.copy(step = BackupStep.ReadingPin) }
                    when (val r = pinVault.load()) {
                        is PinLoadResult.Loaded -> pin = r.pin.let { p -> pinChars(p) }
                        PinLoadResult.NotSaved -> Unit
                        PinLoadResult.Cancelled -> return@launch failBackup(BackupFailure.PinCancelled)
                        PinLoadResult.Unavailable, PinLoadResult.Failed -> return@launch failBackup(BackupFailure.PinUnreadable)
                    }
                }
                _backup.update { it.copy(step = BackupStep.Encrypting) }
                val now = wallClockMs()
                val bytes = try {
                    withContext(ioDispatcher) {
                        val c = store.snapshot(BackupPreferences.snapshot(preferencesStore.current), pin)
                        try { BackupCodec.encode(c, password, now, appVersion, random) } finally { c.wipe() }
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: BackupException) {
                    return@launch failBackup(if (e.error == BackupError.TooLarge) BackupFailure.TooLarge else BackupFailure.PrepareFailed)
                } catch (_: Exception) { return@launch failBackup(BackupFailure.PrepareFailed) }
                pendingBytes = bytes
                pendingName = BackupFormat.fileName(now, zone)
                _backup.update { it.copy(step = BackupStep.AwaitingDestination, suggestedName = pendingName, destinationRequest = it.destinationRequest + 1) }
            } finally {
                password.fill('\u0000'); pin?.fill('\u0000')
            }
        }
    }

    private fun pinChars(p: Pin): CharArray {
        val b = p.toAsciiBytes()
        try { return CharArray(b.size) { b[it].toInt().toChar() } } finally { b.fill(0); p.wipe() }
    }

    private fun failBackup(f: BackupFailure) { discardPending(); _backup.update { it.copy(step = BackupStep.Editing, failure = f) } }

    /** Resultado do seletor "Criar documento": `null` = cancelado (nada é gravado). */
    fun onDestinationChosen(uri: String?) {
        val bytes = pendingBytes
        if (_backup.value.step != BackupStep.AwaitingDestination || bytes == null) return
        if (uri == null) { failBackupQuiet(); return }
        _backup.update { it.copy(step = BackupStep.Writing) }
        backupJob = viewModelScope.launch {
            val ok = try { withContext(ioDispatcher) { documents.write(uri, bytes) } } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            val name = if (ok) (try { withContext(ioDispatcher) { documents.displayName(uri) } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }) ?: pendingName else pendingName
            val size = bytes.size.toLong()
            discardPending()
            if (!ok) try { withContext(NonCancellable + ioDispatcher) { documents.delete(uri) } } catch (_: Exception) { /* melhor esforço: não mascara WriteFailed */ }
            if (ok) _backup.update { it.copy(step = BackupStep.Saved, failure = null, saved = BackupSavedUi(name, BackupFormat.size(size))) }
            else _backup.update { it.copy(step = BackupStep.Editing, failure = BackupFailure.WriteFailed) }
        }
    }

    private fun failBackupQuiet() { discardPending(); _backup.update { it.copy(step = BackupStep.Editing) } }

    /** "Concluir"/voltar: limpa o estado do fluxo. */
    fun onBackupExit() {
        if (_backup.value.busy) return
        discardPending(); _backup.value = BackupUiState()
    }

    private fun discardPending() { pendingBytes?.fill(0); pendingBytes = null }

    // ============================================================ Restaurar

    /** Ao abrir a tela: abre o seletor uma vez (só se recém-aberta). */
    fun onRestoreOpened() { if (_restore.value.step == RestoreStep.Idle) startPicking() }

    /** "Escolher arquivo" / "Escolher outro arquivo". */
    fun onPickAgain() { if (!_restore.value.busy) startPicking() }

    private fun startPicking() {
        discardRestore()
        _restore.update { RestoreUiState(step = RestoreStep.Picking, pickRequest = it.pickRequest + 1) }
    }

    /** Resultado do seletor "Abrir documento": `null` = cancelado. */
    fun onDocumentPicked(uri: String?) {
        if (_restore.value.step != RestoreStep.Picking) return
        if (uri == null) { _restore.update { it.copy(step = RestoreStep.NoFile) }; return }
        _restore.update { it.copy(step = RestoreStep.Reading) }
        restoreJob = viewModelScope.launch {
            val read = try { withContext(ioDispatcher) { documents.read(uri) } } catch (e: CancellationException) { throw e } catch (_: Exception) { DocumentRead.Failed }
            when (read) {
                DocumentRead.TooLarge -> failRestore(RestoreFailure.TooLarge)
                DocumentRead.Failed -> failRestore(RestoreFailure.ReadFailed)
                is DocumentRead.Data -> when (val h = BackupCodec.peek(read.bytes)) {
                    is BackupResult.Err -> { read.bytes.fill(0); failRestore(when (h.error) {
                        BackupError.UnsupportedVersion -> RestoreFailure.Unsupported
                        BackupError.TooLarge -> RestoreFailure.TooLarge
                        else -> RestoreFailure.NotABackup
                    }) }
                    is BackupResult.Ok -> {
                        fileBytes = read.bytes; header = h.value
                        val file = RestoreFileUi(read.name ?: "backup.ixbak", BackupFormat.size(read.bytes.size.toLong()), BackupFormat.created(h.value.createdAtMs, zone))
                        _restore.update { it.copy(step = RestoreStep.NeedsPassword, file = file, wrongPassword = false, failure = null) }
                    }
                }
            }
        }
    }

    /** Senha digitada: decifra em E/S. [password] é zerado ao terminar. */
    fun onPasswordSubmitted(password: CharArray) {
        val bytes = fileBytes
        if (_restore.value.step != RestoreStep.NeedsPassword || bytes == null || header == null) { password.fill('\u0000'); return }
        _restore.update { it.copy(step = RestoreStep.Decrypting, wrongPassword = false) }
        restoreJob = viewModelScope.launch {
            try {
                val r = try { withContext(ioDispatcher) { BackupCodec.decode(bytes, password) } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                when (r) {
                    null -> failRestore(RestoreFailure.ReadFailed)
                    is BackupResult.Err -> when (r.error) {
                        BackupError.WrongPasswordOrCorrupt -> _restore.update { it.copy(step = RestoreStep.NeedsPassword, wrongPassword = true) }
                        BackupError.UnsupportedVersion -> failRestore(RestoreFailure.Unsupported)
                        BackupError.TooLarge -> failRestore(RestoreFailure.TooLarge)
                        else -> failRestore(RestoreFailure.InvalidContent)
                    }
                    is BackupResult.Ok -> {
                        contents = r.value
                        val prefs = r.value.preferences
                        val summary = RestoreSummaryUi(
                            tripCount = BackupCodec.countTrips(r.value.tripsFile),
                            hasScooter = Preferences.normalizeAddress(prefs["scooterAddress"]) != null,
                            hasPin = r.value.pin != null,
                        )
                        fileBytes?.fill(0); fileBytes = null
                        _restore.update { it.copy(step = RestoreStep.Review, summary = summary) }
                    }
                }
            } finally { password.fill('\u0000') }
        }
    }

    /** "Restaurar" (depois do aviso de substituição): aplica tudo ou nada; o PIN só por último, com biometria. */
    fun onRestoreConfirmed() {
        val c = contents
        if (_restore.value.step != RestoreStep.Review || c == null || host.tripRecording.value) return
        _restore.update { it.copy(step = RestoreStep.Applying) }
        // Não cancelável: depois de `apply` as preferências e o histórico têm de acompanhar (sem estado pela metade).
        restoreJob = viewModelScope.launch(NonCancellable) {
            var pin: Pin? = null
            try {
                host.prepareForRestore()
                when (val r = withContext(ioDispatcher) { try { store.apply(c) } catch (_: Exception) { BackupApplyResult.IoFailure } }) {
                    BackupApplyResult.Invalid -> failRestore(RestoreFailure.InvalidContent)
                    BackupApplyResult.IoFailure -> failRestore(RestoreFailure.WriteFailed)
                    is BackupApplyResult.Applied -> {
                        val prefsOk = try {
                            preferencesStore.update { BackupPreferences.toPreferences(r.preferences).copy(restorePromptDone = true) }; true
                        } catch (_: Exception) { false }
                        try { host.reloadAfterRestore() } catch (_: Exception) { /* o histórico aparece na próxima abertura */ }
                        if (!prefsOk) return@launch failRestore(RestoreFailure.PreferencesFailed)
                        val outcome = c.pin?.let { chars ->
                            pin = Pin.parse(CharBuffer.wrap(chars))
                            pin?.let { p -> when (try { pinVault.save(p) } catch (_: Exception) { PinSaveResult.Failed }) {
                                PinSaveResult.Saved -> PinOutcome.Restored
                                PinSaveResult.Cancelled -> PinOutcome.Cancelled
                                PinSaveResult.Unavailable -> PinOutcome.NoBiometrics
                                PinSaveResult.Failed -> PinOutcome.Failed
                            } } ?: PinOutcome.InvalidInBackup
                        } ?: run {
                            // Restaurar SUBSTITUI: um PIN antigo no cofre não pode sobreviver a um backup sem PIN (o patinete restaurado pode ser outro).
                            if (pinVault.hasSavedPin.value) { try { pinVault.forget(); PinOutcome.NotInBackupOldRemoved } catch (_: Exception) { PinOutcome.NotInBackup } }
                            else PinOutcome.NotInBackup
                        }
                        val done = RestoreDoneUi(r.tripCount, Preferences.normalizeAddress(r.preferences["scooterAddress"]) != null, outcome)
                        _restore.update { it.copy(step = RestoreStep.Done, done = done, failure = null) }
                    }
                }
            } catch (_: Exception) {
                failRestore(RestoreFailure.Interrupted)
            } finally {
                pin?.wipe(); discardRestore()
            }
        }
    }

    private fun failRestore(f: RestoreFailure) { discardRestore(); _restore.update { it.copy(step = RestoreStep.Failed, failure = f) } }

    /** "Abrir o app"/voltar: limpa o fluxo (ignorado enquanto ocupado). */
    fun onRestoreExit() {
        if (_restore.value.busy) return
        discardRestore(); _restore.value = RestoreUiState(pickRequest = _restore.value.pickRequest)
    }

    private fun discardRestore() {
        fileBytes?.fill(0); fileBytes = null; header = null
        contents?.wipe(); contents = null
    }

    override fun onCleared() {
        discardPending(); discardRestore()
        super.onCleared()
    }
}
