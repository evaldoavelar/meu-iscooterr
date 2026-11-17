package br.dev.evaldo.meuscooter.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import br.dev.evaldo.meuscooter.data.FakePinVault
import br.dev.evaldo.meuscooter.data.FakeScooterTransport
import br.dev.evaldo.meuscooter.data.SimulatedBluetoothEnvironment
import br.dev.evaldo.meuscooter.domain.InMemoryPreferencesStore
import br.dev.evaldo.meuscooter.domain.Preferences
import br.dev.evaldo.meuscooter.domain.backup.BackupApplyResult
import br.dev.evaldo.meuscooter.domain.backup.BackupCodec
import br.dev.evaldo.meuscooter.domain.backup.BackupContents
import br.dev.evaldo.meuscooter.domain.backup.BackupStore
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S52: tela "Já usou o app antes?", Preferências > Sistema e o fluxo de restauração dentro do AppRoot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class BackupAppRootTest {
    @get:Rule val rule = createComposeRule()

    private val trips = "TRIPS2|3\n1|1700000000000|1.5|600000|15.0|25.0|-|80|70|-\n"
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val prefsStore = InMemoryPreferencesStore()
    private var applied: String? = null
    private var restarts = 0

    private val store = object : BackupStore {
        override fun snapshot(preferences: Map<String, String>, pin: CharArray?) = BackupContents(preferences, trips.toByteArray(), emptyMap(), pin)
        override fun apply(contents: BackupContents): BackupApplyResult {
            applied = contents.tripsFile?.let { String(it) }
            return BackupApplyResult.Applied(contents.preferences, 1, 0)
        }
    }
    private val docs = object : BackupDocuments {
        val files = mutableMapOf<String, ByteArray>()
        override suspend fun read(uri: String) = files[uri]?.let { DocumentRead.Data(it.copyOf(), "meu-iscooter-2026-11-17.ixbak") } ?: DocumentRead.Failed
        override suspend fun write(uri: String, bytes: ByteArray) = true
        override suspend fun displayName(uri: String): String? = null
    }

    private fun start(prefs: Preferences, withBackup: Boolean = true): Pair<HomeViewModel, BackupViewModel> {
        val store0 = InMemoryPreferencesStore(prefs)
        val vm = HomeViewModel(
            { scope, clock -> FakeScooterTransport(scope, clock) }, Clock { System.nanoTime() / 1_000_000 }, null,
            preferencesStore = store0, bluetoothEnvironment = SimulatedBluetoothEnvironment(),
        ) { "00:00" }
        val backup = BackupViewModel(store, docs, FakePinVault(), store0, vm, "0.1.0")
        rule.setContent { MeuPatineteTheme(darkTheme = false) { AppRoot(vm, backup = if (withBackup) backup else null, onRestartApp = { restarts++ }) } }
        return vm to backup
    }

    /** O estado do ViewModel passa por fluxos combinados (stateIn): drena o looper principal a cada checagem. */
    private fun waitFor(ms: Long = 5_000, cond: () -> Boolean) = rule.waitUntil(ms) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); cond() }

    @Test fun `primeira abertura sem cadastro pergunta se ja usou o app`() {
        start(Preferences())
        rule.onNodeWithText("Já usou o app antes?").assertIsDisplayed()
        rule.onNodeWithText("Vamos conectar seu patinete").assertDoesNotExist()
    }

    @Test fun `Comecar do zero grava restorePromptDone e segue o primeiro uso`() {
        val (vm, _) = start(Preferences())
        rule.onNodeWithTag("prompt-fresh").performClick()
        waitFor { vm.preferences.value.restorePromptDone }
        rule.onNodeWithText("Vamos conectar seu patinete").assertIsDisplayed()
        rule.onNodeWithText("Já usou o app antes?").assertDoesNotExist()
    }

    @Test fun `a pergunta nao aparece se ja respondida, se ja ha patinete ou onboarding concluido, nem sem o backup`() {
        start(Preferences(restorePromptDone = true))
        rule.onNodeWithText("Já usou o app antes?").assertDoesNotExist()
        rule.onNodeWithText("Vamos conectar seu patinete").assertIsDisplayed()
    }

    @Test fun `com patinete ja cadastrado ou onboarding feito a pergunta nao aparece`() {
        start(Preferences(onboardingDone = true))
        rule.onNodeWithText("Já usou o app antes?").assertDoesNotExist()
        rule.onNodeWithTag("main-button").assertIsDisplayed()
    }

    @Test fun `sem o ViewModel de backup nada muda no primeiro uso`() {
        start(Preferences(), withBackup = false)
        rule.onNodeWithText("Já usou o app antes?").assertDoesNotExist()
        rule.onNodeWithText("Vamos conectar seu patinete").assertIsDisplayed()
    }

    @Test fun `Restaurar backup abre a tela de restaurar e voltar retorna a pergunta`() {
        start(Preferences())
        rule.onNodeWithTag("prompt-restore").performClick()
        rule.onNodeWithText("Restaurar backup").assertIsDisplayed()
        rule.onNodeWithTag("backup-back").performClick()
        rule.onNodeWithText("Já usou o app antes?").assertIsDisplayed()
    }

    @Test fun `Preferencias Sistema abre Fazer backup e Restaurar backup`() {
        start(Preferences(onboardingDone = true))
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Preferências").performClick()
        rule.onNodeWithTag("settings-backup").performScrollTo().performClick()
        rule.onNodeWithTag("backup-save").assertIsDisplayed()
        rule.onNodeWithTag("backup-back").performClick()
        rule.onNodeWithTag("settings-restore").performScrollTo().performClick()
        rule.onNodeWithTag("restore-hint").assertIsDisplayed()
        rule.onNodeWithTag("backup-back").performClick()
        rule.onNodeWithText("Preferências").assertIsDisplayed()
    }

    @Test fun `restaurar na primeira abertura - a tela Tudo restaurado nao some quando onboardingDone vira true e Abrir o app reinicia`() {
        val (vm, backup) = start(Preferences())
        rule.onNodeWithTag("prompt-restore").performClick()
        val bytes = BackupCodec.encode(
            BackupContents(mapOf("theme" to "Dark", "onboardingDone" to "true", "scooterAddress" to mac), trips.toByteArray(), emptyMap(), "1357".toCharArray()),
            "senha-forte-1".toCharArray(), 1794915600000L, "0.1.0", SecureRandom(),
        )
        docs.files["content://b"] = bytes
        waitFor { backup.restore.value.step == RestoreStep.Picking }
        rule.runOnIdle { backup.onDocumentPicked("content://b") }
        waitFor { backup.restore.value.step == RestoreStep.NeedsPassword }
        rule.onNodeWithTag("restore-password").performTextInput("senha-forte-1")
        rule.onNodeWithTag("restore-unlock").performClick()
        waitFor(20_000) { backup.restore.value.step == RestoreStep.Review }
        rule.onNodeWithText("Substitui o que há neste celular").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("restore-confirm").performClick()
        waitFor(20_000) { backup.restore.value.step == RestoreStep.Done }
        assertTrue(vm.preferences.value.onboardingDone) // as preferências já trocaram...
        rule.onNodeWithText("Tudo restaurado").assertIsDisplayed() // ...e a tela continua
        assertEquals(trips, applied)
        assertEquals(0, restarts)
        rule.onNodeWithTag("restore-open-app").performClick()
        assertEquals(1, restarts)
        rule.onNodeWithTag("main-button").assertIsDisplayed()
        assertFalse(rule.onAllNodesWithText("Tudo restaurado").fetchSemanticsNodes().isNotEmpty())
    }
}
