package br.dev.evaldo.meuscooter.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/** Ponte entre o backup e o resto do app (implementada pelo [HomeViewModel]; simulada nos testes). */
interface BackupHost {
    /** Há viagem em curso (gravando ou pausada): backup e restauração ficam bloqueados. */
    val tripRecording: StateFlow<Boolean>

    /** Antes de trocar o estado: desconecta, descarta viagem em curso e derruba o serviço em primeiro plano. */
    suspend fun prepareForRestore()

    /** Depois de trocar `trips.v1`/`routes/`: relê o histórico. */
    suspend fun reloadAfterRestore()
}

/** Leitura/escrita dos arquivos que o usuário escolhe no seletor do Android (Storage Access Framework). URIs viajam como texto. */
interface BackupDocuments {
    suspend fun read(uri: String): DocumentRead
    suspend fun write(uri: String, bytes: ByteArray): Boolean
    suspend fun displayName(uri: String): String?
    /** Melhor esforço: apaga o destino criado pelo seletor quando a gravação falhou (evita um .ixbak truncado). Nunca lança. */
    suspend fun delete(uri: String) {}
}

sealed interface DocumentRead {
    class Data(val bytes: ByteArray, val name: String?) : DocumentRead {
        override fun toString() = "Data(<redigido>)"
    }
    /** Maior que o teto de um backup (lido só até o teto + 1). */
    data object TooLarge : DocumentRead
    data object Failed : DocumentRead
}

// ---------------------------------------------------------------- Fazer backup

enum class BackupStep { Editing, ReadingPin, Encrypting, AwaitingDestination, Writing, Saved }

enum class BackupFailure(val message: String) {
    PinCancelled("Backup não criado: a digital não foi confirmada."),
    PinUnreadable("Não foi possível ler o PIN do cofre. Tente de novo ou salve sem o PIN."),
    TooLarge("O histórico é grande demais para caber em um backup."),
    PrepareFailed("Não foi possível preparar o backup. Nada foi salvo."),
    WriteFailed("Não foi possível gravar o arquivo. Escolha outro local e tente de novo."),
}

const val BACKUP_BLOCKED_BY_TRIP = "Há uma viagem em gravação. Pare a viagem para fazer o backup."
const val RESTORE_BLOCKED_BY_TRIP = "Há uma viagem em gravação. Pare a viagem para restaurar."

data class BackupSavedUi(val fileName: String, val sizeLabel: String)

data class BackupUiState(
    val step: BackupStep = BackupStep.Editing,
    val tripCount: Int = 0,
    /** Falha ao ler o histórico para contar (não mostrar "0 viagens" como se fosse verdade). */
    val countFailed: Boolean = false,
    val hasScooter: Boolean = false,
    val pinSaved: Boolean = false,
    val recording: Boolean = false,
    val failure: BackupFailure? = null,
    /** Contador: cada incremento pede à tela o seletor "Criar documento" (com [suggestedName]). */
    val destinationRequest: Int = 0,
    val suggestedName: String = "",
    val saved: BackupSavedUi? = null,
) {
    val busy: Boolean get() = step != BackupStep.Editing && step != BackupStep.Saved
    /** Depois de falha ao ler o PIN do cofre, oferece salvar sem ele. */
    val canSkipPin: Boolean get() = failure == BackupFailure.PinUnreadable
}

// ---------------------------------------------------------------- Restaurar

/** Idle = recém-aberta (o seletor abre sozinho); NoFile = seletor cancelado. */
enum class RestoreStep { Idle, Picking, NoFile, Reading, NeedsPassword, Decrypting, Review, Applying, Done, Failed }

enum class RestoreFailure(val message: String) {
    NotABackup("Este arquivo não é um backup válido ou está corrompido."),
    Unsupported("Este backup é de uma versão mais nova do app e não pode ser aberto."),
    TooLarge("O arquivo é grande demais para ser um backup deste app."),
    ReadFailed("Não foi possível ler o arquivo. Escolha-o de novo."),
    InvalidContent("O conteúdo do backup é inválido. Nada foi alterado."),
    WriteFailed("Não foi possível gravar a restauração. Nada foi alterado."),
    PreferencesFailed("O histórico foi restaurado, mas as preferências não puderam ser gravadas."),
    Interrupted("A restauração foi interrompida. Tente de novo."),
}

const val WRONG_PASSWORD_MESSAGE = "Senha incorreta ou arquivo corrompido."

enum class PinOutcome(val note: String?) {
    NotInBackup(null),
    /** O backup não tinha PIN e o cofre tinha um: foi removido (restaurar substitui). */
    NotInBackupOldRemoved("O backup não tinha PIN; o PIN anterior deste celular foi removido. Informe o PIN ao desbloquear."),
    Restored(null),
    NoBiometrics("PIN não restaurado: este celular não tem biometria."),
    Cancelled("PIN não restaurado: a digital não foi confirmada. Digite o PIN ao desbloquear."),
    Failed("PIN não restaurado: não foi possível guardá-lo no cofre."),
    InvalidInBackup("PIN não restaurado: o PIN do backup é inválido."),
}

data class RestoreFileUi(val name: String, val sizeLabel: String, val createdLabel: String)
data class RestoreSummaryUi(val tripCount: Int, val hasScooter: Boolean, val hasPin: Boolean)
data class RestoreDoneUi(val tripCount: Int, val hasScooter: Boolean, val pin: PinOutcome)

data class RestoreUiState(
    val step: RestoreStep = RestoreStep.Idle,
    val file: RestoreFileUi? = null,
    val summary: RestoreSummaryUi? = null,
    val failure: RestoreFailure? = null,
    val wrongPassword: Boolean = false,
    val done: RestoreDoneUi? = null,
    val recording: Boolean = false,
    /** Contador: cada incremento pede à tela o seletor "Abrir documento". */
    val pickRequest: Int = 0,
) {
    val busy: Boolean get() = step == RestoreStep.Reading || step == RestoreStep.Decrypting || step == RestoreStep.Applying
}

// ---------------------------------------------------------------- Regras puras

enum class PasswordCheck { Ok, TooShort, Mismatch }

object BackupFormat {
    const val MIN_PASSWORD = br.dev.evaldo.meuscooter.domain.backup.BackupCodec.MIN_PASSWORD
    private val PT_BR = Locale.forLanguageTag("pt-BR")

    /** Senha + repetição: vazio/curto antes de "não confere", para a dica certa aparecer primeiro. */
    fun check(password: CharSequence, repeat: CharSequence): PasswordCheck = when {
        password.length < MIN_PASSWORD -> PasswordCheck.TooShort
        !password.contentEquals(repeat) -> PasswordCheck.Mismatch
        else -> PasswordCheck.Ok
    }

    /** `meu-iscooter-AAAA-MM-DD.ixbak` com a data local. */
    fun fileName(nowMs: Long, zone: ZoneId): String =
        "meu-iscooter-${Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()}.ixbak"

    /** "512 B", "38 KB", "1,5 MB". */
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> String.format(PT_BR, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    /** "17/11/2026, 08:40". */
    fun created(ms: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm", PT_BR).format(Instant.ofEpochMilli(ms).atZone(zone))

    fun trips(n: Int): String = if (n == 1) "1 viagem" else "$n viagens"

    /** "6 viagens, o iX7 Pro e o PIN foram recuperados. Pode conectar ao patinete." */
    fun restoredText(d: RestoreDoneUi): String {
        val parts = buildList {
            add(if (d.tripCount == 0) "nenhuma viagem" else trips(d.tripCount))
            if (d.hasScooter) add("o iX7 Pro")
            if (d.pin == PinOutcome.Restored) add("o PIN")
        }
        val list = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " e " + parts.last()
        val verb = when { parts.size > 1 -> "foram recuperados"; d.tripCount == 1 || d.tripCount == 0 -> "foi recuperada"; else -> "foram recuperadas" }
        return "${list.replaceFirstChar { it.uppercase() }} $verb." + if (d.hasScooter) " Pode conectar ao patinete." else ""
    }

    fun restoredTitle(d: RestoreDoneUi): String =
        when (d.pin) {
            PinOutcome.NotInBackup, PinOutcome.Restored -> "Tudo restaurado"
            PinOutcome.NotInBackupOldRemoved -> "Restaurado"
            else -> "Restaurado, sem o PIN"
        }
}
