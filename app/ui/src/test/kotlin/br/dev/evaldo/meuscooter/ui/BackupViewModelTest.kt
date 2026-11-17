package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.data.FakePinVault
import br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore
import br.dev.evaldo.meuscooter.domain.PinLoadResult
import br.dev.evaldo.meuscooter.domain.PinSaveResult
import br.dev.evaldo.meuscooter.domain.PinVault
import br.dev.evaldo.meuscooter.domain.Preferences
import br.dev.evaldo.meuscooter.domain.PreferencesStore
import br.dev.evaldo.meuscooter.domain.ThemeMode
import br.dev.evaldo.meuscooter.domain.Units
import br.dev.evaldo.meuscooter.domain.backup.BackupApplyResult
import br.dev.evaldo.meuscooter.domain.backup.BackupCodec
import br.dev.evaldo.meuscooter.domain.backup.BackupContents
import br.dev.evaldo.meuscooter.domain.backup.BackupError
import br.dev.evaldo.meuscooter.domain.backup.BackupException
import br.dev.evaldo.meuscooter.domain.backup.BackupResult
import br.dev.evaldo.meuscooter.domain.backup.BackupStore
import br.dev.evaldo.meuscooter.protocol.Pin
import java.security.SecureRandom
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Máquina de estados do backup/restauração com fakes (loja, documentos, host, cofre). PIN sintético 1357; senha sintética. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val tripsText = "TRIPS2|3\n1|1700000000000|1.5|600000|15.0|25.0|-|80|70|-\n2|1700100000000|2.5|900000|10.0|20.0|-|-|-|-\n"
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val password = "senha-forte-1"
    private val now = 1794915600000L // 2026-11-17 11:40 UTC = 08:40 em São Paulo
    private val zone = ZoneId.of("America/Sao_Paulo")

    private class FakeStore(var trips: ByteArray? = null, var result: BackupApplyResult? = null) : BackupStore {
        var snapshotError: Exception? = null
        val events = mutableListOf<String>()
        var appliedPrefs: Map<String, String>? = null
        var appliedTrips: String? = null
        var appliedPin: String? = null
        var applyThrows = false
        override fun snapshot(preferences: Map<String, String>, pin: CharArray?): BackupContents {
            snapshotError?.let { throw it }
            return BackupContents(preferences, trips?.copyOf(), emptyMap(), pin?.copyOf())
        }
        override fun apply(contents: BackupContents): BackupApplyResult {
            events += "apply"
            if (applyThrows) throw java.io.IOException("x")
            appliedPrefs = contents.preferences; appliedTrips = contents.tripsFile?.let { String(it) }; appliedPin = contents.pin?.let { String(it) }
            return result ?: BackupApplyResult.Applied(contents.preferences, BackupCodec.countTrips(contents.tripsFile), contents.routes.size)
        }
    }

    private class FakeDocs : BackupDocuments {
        val files = mutableMapOf<String, ByteArray>()
        var writeOk = true
        var readResult: DocumentRead? = null
        override suspend fun read(uri: String) = readResult ?: files[uri]?.let { DocumentRead.Data(it.copyOf(), "meu-iscooter-2026-11-17.ixbak") } ?: DocumentRead.Failed
        override suspend fun write(uri: String, bytes: ByteArray): Boolean { if (writeOk) files[uri] = bytes.copyOf(); return writeOk }
        override suspend fun displayName(uri: String): String? = "escolhido.ixbak"
    }

    private class FakeHost(var events: MutableList<String>) : BackupHost {
        override val tripRecording = MutableStateFlow(false)
        var reloads = 0
        override suspend fun prepareForRestore() { events += "prepare" }
        override suspend fun reloadAfterRestore() { reloads++; events += "reload" }
    }

    private class ScriptedVault(val load: PinLoadResult, val save: PinSaveResult = PinSaveResult.Saved, saved: Boolean = true) : PinVault {
        override val available = true
        override val hasSavedPin: StateFlow<Boolean> = MutableStateFlow(saved)
        override suspend fun save(pin: Pin) = save
        override suspend fun load() = load
        override suspend fun forget() = Unit
    }

    private val store = FakeStore(tripsText.toByteArray())
    private val docs = FakeDocs()
    private val host = FakeHost(store.events)
    private var prefs: PreferencesStore = InMemoryPreferencesStore(Preferences(theme = ThemeMode.Dark, scooterAddress = mac, onboardingDone = true))

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(vault: PinVault = PinVault.None) = BackupViewModel(store, docs, vault, prefs, host, "0.1.0", { now }, zone, SecureRandom(), Dispatchers.Unconfined)
    private fun vaultWithPin(pin: String = "1357") = FakePinVault().also { kotlinx.coroutines.runBlocking { it.save(Pin.of(pin)) } }
    private fun pw(s: String = password) = s.toCharArray()

    private fun backupBytes(pin: String? = "1357", trips: String? = tripsText, prefsMap: Map<String, String> = mapOf("theme" to "Light", "units" to "Imperial", "onboardingDone" to "true", "scooterAddress" to mac)) =
        BackupCodec.encode(BackupContents(prefsMap, trips?.toByteArray(), emptyMap(), pin?.toCharArray()), pw(), now, "0.1.0", SecureRandom())

    // ---------------------------------------------------------------- Fazer backup

    @Test fun `caminho feliz - cofre com PIN, cifra, grava no destino e mostra o resultado`() {
        val vault = vaultWithPin(); val v = vm(vault)
        v.onBackupOpened()
        assertEquals(2, v.backup.value.tripCount); assertTrue(v.backup.value.pinSaved); assertTrue(v.backup.value.hasScooter)
        val p = pw(); v.onSaveClicked(p)
        assertEquals(1, vault.loads)
        assertTrue(p.all { it == '\u0000' }) // senha do chamador zerada
        val s = v.backup.value
        assertEquals(BackupStep.AwaitingDestination, s.step); assertEquals("meu-iscooter-2026-11-17.ixbak", s.suggestedName); assertEquals(1, s.destinationRequest)
        v.onDestinationChosen("content://x/1")
        val written = docs.files.getValue("content://x/1")
        val decoded = (BackupCodec.decode(written, pw()) as BackupResult.Ok).value
        assertEquals("1357", String(decoded.pin!!)); assertEquals(tripsText, String(decoded.tripsFile!!))
        assertEquals("Dark", decoded.preferences["theme"]); assertEquals(mac, decoded.preferences["scooterAddress"])
        assertFalse("restorePromptDone" in decoded.preferences)
        val done = v.backup.value
        assertEquals(BackupStep.Saved, done.step); assertEquals("escolhido.ixbak", done.saved!!.fileName)
        assertEquals("${written.size} B", done.saved!!.sizeLabel) // < 1 KiB: em bytes
        v.onBackupExit(); assertEquals(BackupStep.Editing, v.backup.value.step); assertNull(v.backup.value.saved)
    }

    @Test fun `sem PIN no cofre nao chama o cofre e o backup sai sem PIN`() {
        val vault = FakePinVault(); val v = vm(vault)
        v.onBackupOpened(); assertFalse(v.backup.value.pinSaved)
        v.onSaveClicked(pw()); v.onDestinationChosen("u")
        assertEquals(0, vault.loads)
        assertNull((BackupCodec.decode(docs.files.getValue("u"), pw()) as BackupResult.Ok).value.pin)
    }

    @Test fun `biometria cancelada nao cria backup nem grava nada`() {
        val vault = vaultWithPin().also { it.authenticates = false }; val v = vm(vault)
        v.onBackupOpened(); v.onSaveClicked(pw())
        assertEquals(BackupFailure.PinCancelled, v.backup.value.failure); assertEquals(BackupStep.Editing, v.backup.value.step)
        assertEquals(0, v.backup.value.destinationRequest); assertTrue(docs.files.isEmpty())
    }

    @Test fun `cofre ilegivel oferece salvar sem o PIN e depois salva sem ele`() {
        for (load in listOf(PinLoadResult.Failed, PinLoadResult.Unavailable)) {
            docs.files.clear()
            val v = vm(ScriptedVault(load)); v.onBackupOpened(); v.onSaveClicked(pw())
            assertEquals(BackupFailure.PinUnreadable, v.backup.value.failure); assertTrue(v.backup.value.canSkipPin)
            v.onSaveClicked(pw(), includePin = false)
            assertNull(v.backup.value.failure); assertEquals(BackupStep.AwaitingDestination, v.backup.value.step)
            v.onDestinationChosen("u")
            assertNull((BackupCodec.decode(docs.files.getValue("u"), pw()) as BackupResult.Ok).value.pin)
        }
    }

    @Test fun `PIN no cofre que some entre a abertura e o toque (NotSaved) salva sem PIN`() {
        val v = vm(ScriptedVault(PinLoadResult.NotSaved)); v.onBackupOpened(); v.onSaveClicked(pw()); v.onDestinationChosen("u")
        assertNull((BackupCodec.decode(docs.files.getValue("u"), pw()) as BackupResult.Ok).value.pin)
    }

    @Test fun `senha curta ou viagem em gravacao sao ignoradas e a senha e zerada`() {
        val v = vm(); v.onBackupOpened()
        val short = pw("1234567"); v.onSaveClicked(short)
        assertTrue(short.all { it == '\u0000' }); assertEquals(BackupStep.Editing, v.backup.value.step); assertEquals(0, v.backup.value.destinationRequest)
        host.tripRecording.value = true
        assertTrue(v.backup.value.recording)
        val ok = pw(); v.onSaveClicked(ok)
        assertTrue(ok.all { it == '\u0000' }); assertEquals(BackupStep.Editing, v.backup.value.step); assertEquals(0, v.backup.value.destinationRequest)
    }

    @Test fun `destino cancelado volta ao formulario sem gravar`() {
        val v = vm(); v.onBackupOpened(); v.onSaveClicked(pw()); v.onDestinationChosen(null)
        assertEquals(BackupStep.Editing, v.backup.value.step); assertNull(v.backup.value.failure); assertTrue(docs.files.isEmpty())
        v.onDestinationChosen("u") // fora de hora: ignorado
        assertTrue(docs.files.isEmpty())
    }

    @Test fun `falha ao gravar mostra o erro e nao mostra backup salvo`() {
        docs.writeOk = false
        val v = vm(); v.onBackupOpened(); v.onSaveClicked(pw()); v.onDestinationChosen("u")
        assertEquals(BackupFailure.WriteFailed, v.backup.value.failure); assertEquals(BackupStep.Editing, v.backup.value.step); assertNull(v.backup.value.saved)
    }

    @Test fun `falha ao preparar e arquivo grande demais tem mensagens proprias`() {
        store.snapshotError = java.io.IOException("x")
        val v = vm(); v.onBackupOpened(); v.onSaveClicked(pw())
        assertEquals(BackupFailure.PrepareFailed, v.backup.value.failure)
        store.snapshotError = BackupException(BackupError.TooLarge)
        v.onSaveClicked(pw()); assertEquals(BackupFailure.TooLarge, v.backup.value.failure)
        store.snapshotError = BackupException(BackupError.InvalidContent)
        v.onSaveClicked(pw()); assertEquals(BackupFailure.PrepareFailed, v.backup.value.failure)
    }

    // ---------------------------------------------------------------- Restaurar

    private fun picked(bytes: ByteArray = backupBytes(), vault: PinVault = FakePinVault()): BackupViewModel {
        docs.files["content://b"] = bytes
        val v = vm(vault)
        v.onRestoreOpened()
        assertEquals(RestoreStep.Picking, v.restore.value.step); assertEquals(1, v.restore.value.pickRequest)
        v.onDocumentPicked("content://b")
        return v
    }

    @Test fun `caminho feliz - mostra arquivo, pede senha, resumo real, aplica na ordem e guarda o PIN`() {
        val vault = FakePinVault(); val v = picked(vault = vault)
        val f = v.restore.value
        assertEquals(RestoreStep.NeedsPassword, f.step)
        assertEquals("meu-iscooter-2026-11-17.ixbak", f.file!!.name); assertEquals("17/11/2026, 08:40", f.file!!.createdLabel)
        val p = pw(); v.onPasswordSubmitted(p)
        assertTrue(p.all { it == '\u0000' })
        assertEquals(RestoreStep.Review, v.restore.value.step)
        assertEquals(RestoreSummaryUi(2, hasScooter = true, hasPin = true), v.restore.value.summary)
        assertTrue(store.events.isEmpty()) // nada tocado antes de confirmar
        v.onRestoreConfirmed()
        assertEquals(listOf("prepare", "apply", "reload"), store.events)
        assertEquals(tripsText, store.appliedTrips); assertEquals("1357", store.appliedPin)
        val cur = prefs.current
        assertEquals(ThemeMode.Light, cur.theme); assertEquals(Units.Imperial, cur.units); assertEquals(mac, cur.scooterAddress)
        assertTrue(cur.onboardingDone); assertTrue(cur.restorePromptDone)
        assertEquals(1, vault.saves)
        val d = v.restore.value
        assertEquals(RestoreStep.Done, d.step); assertEquals(RestoreDoneUi(2, true, PinOutcome.Restored), d.done)
        assertEquals("Tudo restaurado", BackupFormat.restoredTitle(d.done!!))
        v.onRestoreExit(); assertEquals(RestoreStep.Idle, v.restore.value.step)
    }

    @Test fun `senha incorreta fica na tela da senha sem abrir o resumo e a certa depois funciona`() {
        val v = picked()
        val wrong = pw("outra-senha-xx"); v.onPasswordSubmitted(wrong)
        assertTrue(wrong.all { it == '\u0000' })
        assertEquals(RestoreStep.NeedsPassword, v.restore.value.step); assertTrue(v.restore.value.wrongPassword); assertNull(v.restore.value.summary)
        v.onPasswordSubmitted(pw()); assertEquals(RestoreStep.Review, v.restore.value.step); assertFalse(v.restore.value.wrongPassword)
    }

    @Test fun `sem biometria ou cofre None restaura o resto e avisa do PIN`() {
        val v = picked(vault = PinVault.None); v.onPasswordSubmitted(pw()); v.onRestoreConfirmed()
        val d = v.restore.value
        assertEquals(RestoreStep.Done, d.step); assertEquals(PinOutcome.NoBiometrics, d.done!!.pin)
        assertEquals("PIN não restaurado: este celular não tem biometria.", d.done!!.pin.note)
        assertEquals(tripsText, store.appliedTrips); assertTrue(prefs.current.onboardingDone)
        assertEquals("Restaurado, sem o PIN", BackupFormat.restoredTitle(d.done!!))
    }

    @Test fun `biometria cancelada ou falha ao guardar o PIN nao desfazem o resto`() {
        for ((save, expected) in listOf(PinSaveResult.Cancelled to PinOutcome.Cancelled, PinSaveResult.Failed to PinOutcome.Failed)) {
            val v = picked(vault = ScriptedVault(PinLoadResult.NotSaved, save)); v.onPasswordSubmitted(pw()); v.onRestoreConfirmed()
            assertEquals(expected, v.restore.value.done!!.pin); assertEquals(RestoreStep.Done, v.restore.value.step)
        }
    }

    @Test fun `backup sem PIN e com PIN invalido`() {
        val a = picked(backupBytes(pin = null)); a.onPasswordSubmitted(pw()); assertFalse(a.restore.value.summary!!.hasPin); a.onRestoreConfirmed()
        assertEquals(PinOutcome.NotInBackup, a.restore.value.done!!.pin); assertNull(PinOutcome.NotInBackup.note)
        val b = picked(backupBytes(pin = "12")); b.onPasswordSubmitted(pw()); b.onRestoreConfirmed()
        assertEquals(PinOutcome.InvalidInBackup, b.restore.value.done!!.pin)
    }

    @Test fun `arquivo que nao e backup, versao nova, grande demais e ilegivel tem mensagens proprias`() {
        assertEquals(RestoreFailure.NotABackup, picked(ByteArray(80) { it.toByte() }).restore.value.failure)
        val newer = backupBytes().also { it[4] = 2 }
        assertEquals(RestoreFailure.Unsupported, picked(newer).restore.value.failure)
        docs.readResult = DocumentRead.TooLarge
        assertEquals(RestoreFailure.TooLarge, picked().restore.value.failure)
        docs.readResult = DocumentRead.Failed
        assertEquals(RestoreFailure.ReadFailed, picked().restore.value.failure)
        docs.readResult = null
        val v = vm(); v.onRestoreOpened(); v.onDocumentPicked("content://nao-existe")
        assertEquals(RestoreFailure.ReadFailed, v.restore.value.failure); assertEquals(RestoreStep.Failed, v.restore.value.step)
        v.onPickAgain(); assertEquals(RestoreStep.Picking, v.restore.value.step); assertEquals(2, v.restore.value.pickRequest); assertNull(v.restore.value.failure)
    }

    @Test fun `backup adulterado tem o mesmo resultado da senha errada`() {
        val tampered = backupBytes().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() }
        val t = picked(tampered); t.onPasswordSubmitted(pw())
        assertTrue(t.restore.value.wrongPassword); assertEquals(RestoreStep.NeedsPassword, t.restore.value.step) // mesmo resultado genérico da senha errada
    }

    @Test fun `apply recusa ou falha - nada muda e as mensagens sao as certas`() {
        store.result = BackupApplyResult.Invalid
        val a = picked(); a.onPasswordSubmitted(pw()); a.onRestoreConfirmed()
        assertEquals(RestoreFailure.InvalidContent, a.restore.value.failure)
        store.result = BackupApplyResult.IoFailure
        val b = picked(); b.onPasswordSubmitted(pw()); b.onRestoreConfirmed()
        assertEquals(RestoreFailure.WriteFailed, b.restore.value.failure)
        store.result = null; store.applyThrows = true
        val c = picked(); c.onPasswordSubmitted(pw()); c.onRestoreConfirmed()
        assertEquals(RestoreFailure.WriteFailed, c.restore.value.failure)
        assertEquals(Preferences(theme = ThemeMode.Dark, scooterAddress = mac, onboardingDone = true), prefs.current) // preferências intactas
        assertEquals(0, host.reloads)
    }

    @Test fun `viagem em gravacao bloqueia a restauracao`() {
        val v = picked(); v.onPasswordSubmitted(pw())
        host.tripRecording.value = true
        assertTrue(v.restore.value.recording)
        v.onRestoreConfirmed()
        assertEquals(RestoreStep.Review, v.restore.value.step); assertTrue(store.events.isEmpty())
        host.tripRecording.value = false
        v.onRestoreConfirmed(); assertEquals(RestoreStep.Done, v.restore.value.step)
    }

    @Test fun `seletor cancelado deixa a tela sem arquivo e nao reabre sozinho`() {
        val v = vm(); v.onRestoreOpened(); v.onDocumentPicked(null)
        assertEquals(RestoreStep.NoFile, v.restore.value.step)
        v.onRestoreOpened(); assertEquals(RestoreStep.NoFile, v.restore.value.step) // rotação: não reabre
        v.onPickAgain(); assertEquals(RestoreStep.Picking, v.restore.value.step)
        v.onDocumentPicked("content://nada"); v.onDocumentPicked("outro") // segundo é ignorado (não está mais em Picking)
    }

    @Test fun `falha ao gravar as preferencias apos aplicar o historico e informada`() {
        val inner = InMemoryPreferencesStore(Preferences())
        prefs = object : PreferencesStore by inner { override suspend fun update(transform: (Preferences) -> Preferences) { throw java.io.IOException("x") } }
        val v = picked(); v.onPasswordSubmitted(pw()); v.onRestoreConfirmed()
        assertEquals(RestoreFailure.PreferencesFailed, v.restore.value.failure)
        assertEquals(1, host.reloads) // o histórico já trocado é relido
    }

    @Test fun `ao sair no meio o conteudo decifrado e descartado`() {
        val v = picked(); v.onPasswordSubmitted(pw()); assertEquals(RestoreStep.Review, v.restore.value.step)
        v.onRestoreExit()
        assertEquals(RestoreStep.Idle, v.restore.value.step); assertNull(v.restore.value.summary)
        v.onRestoreConfirmed(); assertTrue(store.events.isEmpty()) // nada a aplicar
    }

    @Test fun `ids e textos auxiliares`() {
        assertNotNull(BackupFailure.entries.all { it.message.isNotBlank() }.also { assertTrue(it) })
        assertTrue(RestoreFailure.entries.all { it.message.isNotBlank() })
        assertTrue(DocumentRead.Data(byteArrayOf(1), "n").toString().contains("redigido"))
        assertArrayEquals(byteArrayOf(1), (DocumentRead.Data(byteArrayOf(1), "n")).bytes)
    }
}
