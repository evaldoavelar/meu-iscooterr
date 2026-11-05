package br.dev.evaldo.meuscooter.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.domain.UnlockStatus
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import br.dev.evaldo.meuscooter.protocol.UnlockRefusal
import br.dev.evaldo.meuscooter.protocol.UnlockResult
import br.dev.evaldo.meuscooter.ui.home.HomeActions
import br.dev.evaldo.meuscooter.ui.home.HomeScreen
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

/** Home no modo de desbloqueio por PIN (etapa 4.4). PIN sintético 1357. Textos: regra 5 e plano Fase 4 §7.4. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HomePinUnlockUiTest {
    @get:Rule val rule = createComposeRule()

    private val mapper = HomeUiMapper(emptyList(), "iX7 Pro") { "14:32" }

    private fun tel(locked: Boolean? = true, speed: Float? = 0f) =
        Telemetry(receivedAtMs = 1_000, locked = locked, speedKmh = speed, batteryPercent = 100, speedLimitKmh = 50)

    private fun pinState(t: Telemetry = tel(), unlock: UnlockStatus? = null, now: Long = 1_000) = HomeState(
        connection = ConnectionStatus.Connected, telemetry = t, nowMs = now, meter1AtMs = t.receivedAtMs, pinUnlock = true, unlock = unlock,
    )

    // ---------- Mapper ----------

    @Test fun lockedAndStillOffersUnlockThatAsksForThePin() {
        val b = mapper.map(pinState()).mainButton
        assertEquals(MainButtonKind.Unlock, b.kind)
        assertEquals("Desbloquear", b.label)
        assertTrue(b.enabled)
        assertTrue(b.asksPin)
    }

    @Test fun unknownSpeedOrMovementOrStaleMeter1DisableTheUnlock() {
        val unknown = mapper.map(pinState(tel(speed = null))).mainButton
        assertFalse(unknown.enabled)
        assertEquals("Velocidade desconhecida — aguarde o patinete", unknown.disabledReason)
        assertFalse(mapper.map(pinState(tel(speed = 5f))).mainButton.enabled)
        assertFalse(mapper.map(pinState(now = 3_500)).mainButton.enabled)
    }

    @Test fun whenUnlockedTheLockButtonSendsNothing() {
        val b = mapper.map(pinState(tel(locked = false))).mainButton
        assertEquals(MainButtonKind.Lock, b.kind)
        assertFalse(b.enabled)
        assertFalse(b.asksPin)
        assertEquals("Bloqueio pelo app ainda não disponível", b.disabledReason)
    }

    @Test fun waitingIsSentAndWaitingNeverDone() {
        val ui = mapper.map(pinState(unlock = UnlockStatus.Waiting(1_000)))
        assertEquals(MainButtonKind.Pending, ui.mainButton.kind)
        assertEquals("Enviado — aguardando o patinete…", ui.mainButton.label)
        assertFalse(ui.mainButton.enabled)
    }

    @Test fun unlockedOnlyFromTheConfirmedResult() {
        val ui = mapper.map(pinState(tel(locked = false), unlock = UnlockStatus.Finished(UnlockResult.Unlocked, 1_000)))
        assertEquals(MainButtonKind.Confirmed, ui.mainButton.kind)
        assertEquals("Desbloqueado às 14:32", ui.mainButton.label)
    }

    @Test fun failuresAreShownInTheConnectionChipAndTheUnlockCanBeTriedAgainByTheUser() {
        val wrong = mapper.map(pinState(unlock = UnlockStatus.Finished(UnlockResult.PinRejected, 1_000)))
        assertEquals(ConnectionKind.Error, wrong.connection.kind)
        assertEquals("PIN incorreto", wrong.connection.label)
        assertEquals(MainButtonKind.Unlock, wrong.mainButton.kind)
        assertTrue(wrong.mainButton.enabled)

        val none = mapper.map(pinState(unlock = UnlockStatus.Finished(UnlockResult.Unconfirmed(UnconfirmedCause.Timeout), 1_000)))
        assertEquals("Sem confirmação — estado de bloqueio desconhecido", none.connection.label)
        val lost = mapper.map(pinState(unlock = UnlockStatus.Finished(UnlockResult.Unconfirmed(UnconfirmedCause.LinkLost), 1_000)).copy(connection = ConnectionStatus.Disconnected))
        assertEquals("Sem confirmação — estado de bloqueio desconhecido", lost.connection.label)

        val refused = mapper.map(pinState(unlock = UnlockStatus.Finished(UnlockResult.Refused(UnlockRefusal.LinkNotReady), 1_000)))
        assertEquals("Desbloqueio não enviado", refused.connection.label)
    }

    @Test fun withoutPinModeNothingChanges() {
        val b = mapper.map(pinState().copy(pinUnlock = false)).mainButton
        assertEquals(MainButtonKind.Unlock, b.kind)
        assertFalse(b.asksPin)
    }

    // ---------- Tela: diálogo de PIN ----------

    private var received: Pin? = null
    private var mainClicks = 0

    private fun show(state: HomeUiState) {
        rule.setContent {
            MeuPatineteTheme { HomeScreen(state, HomeActions(onMainButton = { mainClicks++ }, onUnlockPin = { received = it })) }
        }
    }

    private fun type(digits: String) = digits.forEach { rule.onNodeWithTag("pin-key-$it").performClick() }

    @Test fun theMainButtonOpensAMaskedFourDigitPinDialog() {
        show(mapper.map(pinState()))
        rule.onNodeWithTag("pin-dialog").assertDoesNotExist()
        rule.onNodeWithTag("main-button").performClick()
        assertEquals(0, mainClicks)
        rule.onNodeWithTag("pin-dialog").assertIsDisplayed()
        rule.onNodeWithTag("pin-confirm").assertIsNotEnabled()
        type("135")
        rule.onNodeWithTag("pin-confirm").assertIsNotEnabled() // 3 dígitos
        rule.onNodeWithContentDescription("3 de 4 dígitos").assertIsDisplayed()
        rule.onNodeWithTag("pin-backspace").performClick()
        type("579") // "13" + "579": o 5º dígito é ignorado → "1357"
        rule.onNodeWithTag("pin-confirm").assertIsEnabled()
        rule.onNodeWithText("1357").assertDoesNotExist()       // nunca exibido em claro
        rule.onNodeWithContentDescription("4 de 4 dígitos").assertIsDisplayed()
        rule.onNodeWithTag("pin-confirm").performClick()
        val pin = checkNotNull(received)
        assertTrue(pin.sameAs(Pin.of("1357")))
        rule.onNodeWithTag("pin-dialog").assertDoesNotExist()
        // Reabrir começa vazio: os dígitos não são guardados.
        rule.onNodeWithTag("main-button").performClick()
        rule.onNodeWithContentDescription("0 de 4 dígitos").assertIsDisplayed()
        rule.onNodeWithTag("pin-confirm").assertIsNotEnabled()
    }

    @Test fun cancellingSendsNothing() {
        show(mapper.map(pinState()))
        rule.onNodeWithTag("main-button").performClick()
        type("1357")
        rule.onNodeWithText("Cancelar").performClick()
        rule.onNodeWithTag("pin-dialog").assertDoesNotExist()
        assertNull(received)
    }

    @Test fun withoutPinModeTheMainButtonKeepsItsAction() {
        show(mapper.map(pinState().copy(pinUnlock = false)))
        rule.onNodeWithTag("main-button").performClick()
        assertEquals(1, mainClicks)
        rule.onNodeWithTag("pin-dialog").assertDoesNotExist()
    }
}
