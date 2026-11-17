package br.dev.evaldo.meuscooter.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import br.dev.evaldo.meuscooter.ui.backup.BackupActions
import br.dev.evaldo.meuscooter.ui.backup.BackupScreen
import br.dev.evaldo.meuscooter.ui.backup.RestoreActions
import br.dev.evaldo.meuscooter.ui.backup.RestorePromptScreen
import br.dev.evaldo.meuscooter.ui.backup.RestoreScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Telas de backup/restauração (mockups backup-criar, restaurar, primeira-abertura): textos do mockup, estados, callbacks e fonte 2,0. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class BackupScreensTest {
    @get:Rule val rule = createComposeRule()
    private val calls = mutableListOf<String>()

    private fun show(fontScale: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) = rule.setContent {
        MeuPatineteTheme(darkTheme = true) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale), content = content)
        }
    }

    private val backupActions = BackupActions(
        onBack = { calls += "back" }, onSave = { calls += "save:${String(it)}" }, onSaveWithoutPin = { calls += "skip:${String(it)}" }, onDone = { calls += "done" },
    )
    private val restoreActions = RestoreActions(
        onBack = { calls += "back" }, onPickAgain = { calls += "pick" }, onPassword = { calls += "pw:${String(it)}" }, onRestore = { calls += "restore" }, onOpenApp = { calls += "open" },
    )

    // ------------------------------------------------------------ Fazer backup

    @Test fun `fazer backup mostra o conteudo real e so habilita com senha valida e repetida`() {
        show { BackupScreen(BackupUiState(tripCount = 6, hasScooter = true, pinSaved = true), backupActions) }
        rule.onNodeWithText("Fazer backup").assertIsDisplayed()
        rule.onNodeWithText("6 viagens e seus trajetos").assertIsDisplayed()
        rule.onNodeWithText("iX7 Pro (endereço Bluetooth)").assertIsDisplayed()
        rule.onNodeWithText("Guardado cifrado com a senha abaixo").assertIsDisplayed()
        rule.onNodeWithText("Tema, unidades e opções").assertIsDisplayed()
        rule.onNodeWithTag("backup-save").assertIsNotEnabled()
        rule.onNodeWithTag("backup-password").performTextInput("1234567")
        rule.onNodeWithTag("backup-repeat").performTextInput("1234567")
        rule.onNodeWithTag("backup-save").assertIsNotEnabled() // 7 caracteres
        rule.onNodeWithTag("backup-password").performTextInput("8")
        rule.onNodeWithTag("backup-mismatch").assertIsDisplayed()
        rule.onNodeWithTag("backup-save").assertIsNotEnabled()
        rule.onNodeWithTag("backup-repeat").performTextInput("8")
        rule.onNodeWithTag("backup-save").assertIsEnabled().performClick()
        assertEquals(listOf("save:12345678"), calls)
    }

    @Test fun `sem PIN no cofre e sem patinete o texto diz isso`() {
        show { BackupScreen(BackupUiState(tripCount = 1, hasScooter = false, pinSaved = false), backupActions) }
        rule.onNodeWithText("PIN não guardado").assertIsDisplayed()
        rule.onNodeWithText("Nenhum patinete cadastrado").assertIsDisplayed()
        rule.onNodeWithText("1 viagem e seus trajetos").assertIsDisplayed()
    }

    @Test fun `senha fica mascarada e mostrar revela`() {
        show { BackupScreen(BackupUiState(), backupActions) }
        rule.onNodeWithTag("backup-password").performTextInput("segredo-123")
        rule.onNodeWithTag("backup-password").assertTextEquals("•••••••••••")
        rule.onNodeWithTag("backup-password-toggle").performClick()
        rule.onNodeWithTag("backup-password").assertTextEquals("segredo-123")
    }

    @Test fun `viagem em gravacao bloqueia o botao e explica`() {
        show { BackupScreen(BackupUiState(recording = true), backupActions) }
        rule.onNodeWithTag("backup-blocked").assertIsDisplayed().assertTextContains(BACKUP_BLOCKED_BY_TRIP)
        rule.onNodeWithTag("backup-password").performTextInput("12345678")
        rule.onNodeWithTag("backup-repeat").performTextInput("12345678")
        rule.onNodeWithTag("backup-save").assertIsNotEnabled()
    }

    @Test fun `erro e opcao de salvar sem o PIN aparecem e enviam a senha`() {
        show { BackupScreen(BackupUiState(failure = BackupFailure.PinUnreadable), backupActions) }
        rule.onNodeWithTag("backup-error").assertTextContains(BackupFailure.PinUnreadable.message)
        rule.onNodeWithTag("backup-password").performTextInput("12345678")
        rule.onNodeWithTag("backup-repeat").performTextInput("12345678")
        rule.onNodeWithTag("backup-skip-pin").assertIsEnabled().performClick()
        assertEquals(listOf("skip:12345678"), calls)
    }

    @Test fun `ocupado desabilita campos e botao e mostra o andamento`() {
        show { BackupScreen(BackupUiState(step = BackupStep.Encrypting), backupActions) }
        rule.onNodeWithTag("backup-working").assertTextContains("Preparando o backup…")
        rule.onNodeWithTag("backup-save").assertIsNotEnabled()
        rule.onNodeWithTag("backup-back").assertIsNotEnabled()
    }

    @Test fun `backup salvo mostra nome, tamanho e Concluir`() {
        show { BackupScreen(BackupUiState(step = BackupStep.Saved, saved = BackupSavedUi("meu-iscooter-2026-11-17.ixbak", "38 KB")), backupActions) }
        rule.onNodeWithText("Backup salvo").assertIsDisplayed()
        rule.onNodeWithTag("result-text", useUnmergedTree = true).assertTextContains("meu-iscooter-2026-11-17.ixbak · 38 KB. Guarde a senha: sem ela não dá para restaurar.")
        rule.onNodeWithTag("backup-done").performClick()
        assertEquals(listOf("done"), calls)
    }

    @Test fun `fonte 2,0 sem corte - tudo alcancavel e botao visivel`() {
        show(fontScale = 2f) { BackupScreen(BackupUiState(tripCount = 6, hasScooter = true, pinSaved = true), backupActions) }
        rule.onNodeWithText("Salvar backup…").assertIsDisplayed()
        rule.onNodeWithText("Histórico de viagens").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Preferências").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("backup-repeat").performScrollTo().assertIsDisplayed()
    }

    // ------------------------------------------------------------ Restaurar

    private val file = RestoreFileUi("meu-iscooter-2026-11-17.ixbak", "38 KB", "17/11/2026, 08:40")

    @Test fun `restaurar sem arquivo oferece escolher e a senha so aparece depois`() {
        show { RestoreScreen(RestoreUiState(step = RestoreStep.NoFile), restoreActions) }
        rule.onNodeWithTag("restore-pick").assertIsEnabled().performClick()
        assertEquals(listOf("pick"), calls)
    }

    @Test fun `pede a senha do arquivo escolhido e envia`() {
        show { RestoreScreen(RestoreUiState(step = RestoreStep.NeedsPassword, file = file), restoreActions) }
        rule.onNodeWithText("meu-iscooter-2026-11-17.ixbak").assertIsDisplayed()
        rule.onNodeWithText("38 KB · criado em 17/11/2026, 08:40").assertIsDisplayed()
        rule.onNodeWithTag("restore-unlock").assertIsNotEnabled()
        rule.onNodeWithTag("restore-password").performTextInput("senha-forte-1")
        rule.onNodeWithTag("restore-unlock").assertIsEnabled().performClick()
        assertEquals(listOf("pw:senha-forte-1"), calls)
    }

    @Test fun `senha incorreta mostra a mensagem generica`() {
        show { RestoreScreen(RestoreUiState(step = RestoreStep.NeedsPassword, file = file, wrongPassword = true), restoreActions) }
        rule.onNodeWithTag("restore-wrong-password").assertTextContains("Senha incorreta ou arquivo corrompido.")
    }

    @Test fun `resumo real, aviso de substituicao e Restaurar`() {
        show { RestoreScreen(RestoreUiState(step = RestoreStep.Review, file = file, summary = RestoreSummaryUi(6, true, true)), restoreActions) }
        rule.onNodeWithText("Dentro do backup").assertIsDisplayed()
        rule.onNodeWithText("6 viagens e seus trajetos").assertIsDisplayed()
        rule.onNodeWithText("Será guardado de novo no cofre").assertIsDisplayed()
        rule.onNodeWithText("Substitui o que há neste celular").assertIsDisplayed()
        rule.onNodeWithText("Histórico, patinete e PIN atuais serão trocados pelos do backup.").assertIsDisplayed()
        rule.onNodeWithTag("restore-confirm").assertIsEnabled().performClick()
        assertEquals(listOf("restore"), calls)
    }

    @Test fun `viagem em gravacao desabilita Restaurar`() {
        show { RestoreScreen(RestoreUiState(step = RestoreStep.Review, file = file, summary = RestoreSummaryUi(0, false, false), recording = true), restoreActions) }
        rule.onNodeWithTag("restore-blocked").assertTextContains(RESTORE_BLOCKED_BY_TRIP)
        rule.onNodeWithTag("restore-confirm").assertIsNotEnabled()
        rule.onNodeWithText("Nenhum patinete").assertIsDisplayed(); rule.onNodeWithText("Não está no backup").assertIsDisplayed()
    }

    @Test fun `falha mostra a mensagem em portugues e permite escolher outro arquivo`() {
        show { RestoreScreen(RestoreUiState(step = RestoreStep.Failed, failure = RestoreFailure.Unsupported), restoreActions) }
        rule.onNodeWithTag("restore-error").assertTextContains(RestoreFailure.Unsupported.message)
        rule.onNodeWithTag("restore-pick").performClick()
        assertEquals(listOf("pick"), calls)
    }

    @Test fun `tudo restaurado mostra o resumo e Abrir o app`() {
        show { RestoreScreen(RestoreUiState(step = RestoreStep.Done, done = RestoreDoneUi(6, true, PinOutcome.Restored)), restoreActions) }
        rule.onNodeWithText("Tudo restaurado").assertIsDisplayed()
        rule.onNodeWithTag("result-text", useUnmergedTree = true).assertTextContains("6 viagens, o iX7 Pro e o PIN foram recuperados. Pode conectar ao patinete.")
        rule.onNodeWithTag("restore-open-app").performClick()
        assertEquals(listOf("open"), calls)
    }

    @Test fun `sem biometria restaura o resto e avisa do PIN na tela final`() {
        show { RestoreScreen(RestoreUiState(step = RestoreStep.Done, done = RestoreDoneUi(2, true, PinOutcome.NoBiometrics)), restoreActions) }
        rule.onNodeWithText("Restaurado, sem o PIN").assertIsDisplayed()
        rule.onNodeWithTag("result-note", useUnmergedTree = true).assertTextContains("PIN não restaurado: este celular não tem biometria.")
    }

    @Test fun `aplicando desabilita tudo e avisa para nao fechar`() {
        show { RestoreScreen(RestoreUiState(step = RestoreStep.Applying, file = file, summary = RestoreSummaryUi(1, true, false)), restoreActions) }
        rule.onNodeWithTag("restore-working").assertTextContains("não feche o app", substring = true)
        rule.onNodeWithTag("restore-confirm").assertIsNotEnabled()
        rule.onNodeWithTag("backup-back").assertIsNotEnabled()
    }

    @Test fun `restaurar com fonte 2,0 - botao e conteudo alcancaveis`() {
        show(fontScale = 2f) { RestoreScreen(RestoreUiState(step = RestoreStep.Review, file = file, summary = RestoreSummaryUi(6, true, true)), restoreActions) }
        rule.onNodeWithText("Restaurar").assertIsDisplayed()
        rule.onNodeWithText("Substitui o que há neste celular").performScrollTo().assertIsDisplayed()
    }

    // ------------------------------------------------------------ Primeira abertura

    @Test fun `ja usou o app antes - dois botoes chamam os callbacks`() {
        show { RestorePromptScreen(onRestore = { calls += "restore" }, onStartFresh = { calls += "fresh" }) }
        rule.onNodeWithText("Já usou o app antes?").assertIsDisplayed()
        rule.onNodeWithText("Se você tem um backup, recupere viagens, patinete e PIN. Senão, comece do zero.").assertIsDisplayed()
        rule.onNodeWithTag("prompt-fresh").performClick(); rule.onNodeWithTag("prompt-restore").performClick()
        assertEquals(listOf("fresh", "restore"), calls)
    }

    @Test fun `ja usou o app antes com fonte 2,0 mantem os dois botoes visiveis`() {
        show(fontScale = 2f) { RestorePromptScreen({}, {}) }
        rule.onNodeWithText("Começar do zero").assertIsDisplayed(); rule.onNodeWithText("Restaurar backup").assertIsDisplayed()
    }
}
