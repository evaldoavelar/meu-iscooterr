package br.dev.evaldo.meuscooter.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.ui.home.HomeActions
import br.dev.evaldo.meuscooter.ui.home.HomeScreen
import br.dev.evaldo.meuscooter.ui.home.PinVaultUi
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Diálogo de PIN com o cofre (F5.2b, ADR 0006 §5). PIN sintético 1357. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HomePinVaultUiTest {
    @get:Rule val rule = createComposeRule()

    private val mapper = HomeUiMapper(emptyList(), "iX7 Pro") { "14:32" }
    private val state = mapper.map(
        HomeState(
            connection = ConnectionStatus.Connected, nowMs = 1_000, pinUnlock = true,
            telemetry = Telemetry(receivedAtMs = 1_000, locked = true, speedKmh = 0f, batteryPercent = 100, speedLimitKmh = 50),
            meter1AtMs = 1_000,
        ),
    )

    private var plain: Pin? = null
    private var withSave: Pin? = null
    private var usedSaved = 0
    private var forgot = 0

    private fun show(vault: PinVaultUi, tap: Boolean = true) {
        rule.setContent {
            MeuPatineteTheme {
                HomeScreen(
                    state,
                    HomeActions(
                        onUnlockPin = { plain = it }, onUnlockPinAndSave = { withSave = it },
                        onUseSavedPin = { usedSaved++ }, onForgetPin = { forgot++ },
                    ),
                    pinVault = vault,
                )
            }
        }
        if (tap) rule.onNodeWithTag("main-button").performClick()
    }

    private fun type(digits: String) = digits.forEach { rule.onNodeWithTag("pin-key-$it").performClick() }

    @Test fun withoutVaultThereAreNoVaultControls() {
        show(PinVaultUi())
        rule.onNodeWithTag("pin-save-checkbox").assertDoesNotExist()
        rule.onNodeWithTag("pin-use-saved").assertDoesNotExist()
        rule.onNodeWithTag("pin-forget").assertDoesNotExist()
    }

    @Test fun savingIsOptInAndDefaultsToOff() {
        show(PinVaultUi(available = true))
        rule.onNodeWithTag("pin-save-checkbox").assertIsDisplayed()
        type("1357"); rule.onNodeWithTag("pin-confirm").performClick()
        assertTrue(checkNotNull(plain).sameAs(Pin.of("1357")))
        assertNull(withSave)
    }

    @Test fun checkingTheBoxSendsThePinForSaving() {
        show(PinVaultUi(available = true))
        rule.onNodeWithTag("pin-save-checkbox").performClick()
        type("1357"); rule.onNodeWithTag("pin-confirm").performClick()
        assertTrue(checkNotNull(withSave).sameAs(Pin.of("1357")))
        assertNull(plain)
    }

    @Test fun withASavedPinTheMainButtonAsksBiometricsFirstWithoutTheKeypad() {
        show(PinVaultUi(available = true, saved = true))
        assertEquals(1, usedSaved)
        rule.onNodeWithTag("pin-dialog").assertDoesNotExist()
        rule.onNodeWithTag("pin-key-1").assertDoesNotExist()
    }

    @Test fun withoutASavedPinTheKeypadOpensAsBefore() {
        show(PinVaultUi(available = true))
        assertEquals(0, usedSaved)
        rule.onNodeWithTag("pin-dialog").assertIsDisplayed()
    }

    @Test fun keypadRequestAfterBiometricsFallsBackToTheDialogWithForgetAndBiometricsShortcut() {
        var vault by mutableStateOf(PinVaultUi(available = true, saved = true))
        rule.setContent {
            MeuPatineteTheme { HomeScreen(state, HomeActions(onUseSavedPin = { usedSaved++ }, onForgetPin = { forgot++ }), pinVault = vault) }
        }
        rule.onNodeWithTag("main-button").performClick()
        rule.onNodeWithTag("pin-dialog").assertDoesNotExist()
        vault = vault.copy(keypadRequest = 1)   // biometria cancelada/falhou
        rule.waitForIdle()
        rule.onNodeWithTag("pin-dialog").assertIsDisplayed()
        rule.onNodeWithTag("pin-forget").assertIsDisplayed()
        rule.onNodeWithTag("pin-save-checkbox").assertDoesNotExist()
        rule.onNodeWithText("Usar PIN salvo").assertDoesNotExist()
        rule.onNodeWithTag("pin-use-saved").assertIsDisplayed()   // atalho "Usar biometria"
        rule.onNodeWithTag("pin-use-saved").performClick()
        assertEquals(2, usedSaved)
        rule.onNodeWithTag("pin-dialog").assertDoesNotExist()
    }

    @Test fun typingThePinAfterTheFallbackUnlocksWithIt() {
        var vault by mutableStateOf(PinVaultUi(available = true, saved = true))
        rule.setContent { MeuPatineteTheme { HomeScreen(state, HomeActions(onUnlockPin = { plain = it }), pinVault = vault) } }
        rule.onNodeWithTag("main-button").performClick()
        vault = vault.copy(keypadRequest = 1); rule.waitForIdle()
        type("1357"); rule.onNodeWithTag("pin-confirm").performClick()
        assertTrue(checkNotNull(plain).sameAs(Pin.of("1357")))
    }

    @Test fun forgetKeepsTheDialogAndCallsForget() {
        var vault by mutableStateOf(PinVaultUi(available = true, saved = true))
        rule.setContent { MeuPatineteTheme { HomeScreen(state, HomeActions(onUnlockPin = { plain = it }, onForgetPin = { forgot++ }), pinVault = vault) } }
        rule.onNodeWithTag("main-button").performClick()
        vault = vault.copy(keypadRequest = 1); rule.waitForIdle()
        rule.onNodeWithTag("pin-forget").performClick()
        assertEquals(1, forgot)
        assertFalse(plain != null)
        rule.onNodeWithTag("pin-dialog").assertIsDisplayed()
    }
}
