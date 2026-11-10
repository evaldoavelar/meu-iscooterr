package br.dev.evaldo.meuscooter.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import br.dev.evaldo.meuscooter.domain.RecoveryReason
import br.dev.evaldo.meuscooter.ui.recovery.RecoveryScreen
import br.dev.evaldo.meuscooter.ui.recovery.SPIN_ICON_TAG
import br.dev.evaldo.meuscooter.ui.recovery.SpinAngleKey
import br.dev.evaldo.meuscooter.ui.recovery.spinAngle
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import androidx.compose.ui.layout.boundsInRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S4: os 4 estados de design/screens.py `status`, em 360x780 dp com fonte 1,0 / 1,5 / 2,0. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class StatusScreenTest {
    @get:Rule val rule = createComposeRule()
    private val calls = mutableListOf<String>()

    private val current = mutableStateOf<RecoveryReason>(RecoveryReason.OutOfRange)

    private fun show(reason: RecoveryReason, fontScale: Float = 1f) {
        current.value = reason
        rule.setContent {
        MeuPatineteTheme(darkTheme = false) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                RecoveryScreen(RecoveryUiMapper.map(current.value), onPrimary = { calls += "primary" }, onSecondary = { calls += "secondary" })
            }
        }
        }
    }

    @Test fun `spinAngle e linear e volta a cada periodo`() {
        assertEquals(0f, spinAngle(0), 0.001f)
        assertEquals(180f, spinAngle(550), 0.001f)
        assertEquals(0f, spinAngle(1100), 0.001f)
        assertEquals(90f, spinAngle(1100 * 7 + 275), 0.001f)
    }

    @Test fun `icone giratorio avanca com o relogio de teste`() {
        rule.mainClock.autoAdvance = false
        show(RecoveryReason.Reconnecting(1))
        fun angle() = rule.onNodeWithTag(SPIN_ICON_TAG).fetchSemanticsNode().config[SpinAngleKey]
        rule.mainClock.advanceTimeBy(100)
        val a0 = angle()
        rule.mainClock.advanceTimeBy(275)
        assertEquals(a0 + 90f, angle(), 12f) // tolerância: frames de ~16 ms (~5,2 graus)
        rule.mainClock.advanceTimeBy(550)
        assertEquals(a0 + 90f + 180f, angle(), 12f)
    }

    @Test fun `so Reconectando tem icone giratorio`() {
        show(RecoveryReason.OutOfRange)
        rule.onNodeWithTag(SPIN_ICON_TAG).assertDoesNotExist()
        current.value = RecoveryReason.Reconnecting(1)
        rule.waitForIdle()
        rule.onNodeWithTag(SPIN_ICON_TAG).assertExists()
    }

    private val reasons = listOf(RecoveryReason.OutOfRange, RecoveryReason.BluetoothOff, RecoveryReason.PermissionDenied, RecoveryReason.LocationOff, RecoveryReason.Reconnecting(2))

    private fun everythingReachable(fontScale: Float) {
        show(reasons.first(), fontScale)
        for (reason in reasons) {
            calls.clear()
            current.value = reason
            rule.waitForIdle()
            val ui = RecoveryUiMapper.map(reason)
            rule.onNodeWithText(ui.title).performScrollTo().assertIsDisplayed()
            rule.onNodeWithText(ui.text).performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("status-primary").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            rule.onNodeWithText(ui.primaryLabel).performClick()
            ui.secondaryLabel?.let {
                rule.onNodeWithTag("status-secondary").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                rule.onNodeWithText(it).performClick()
            }
            assertEquals(reason.toString(), if (ui.secondaryLabel != null) listOf("primary", "secondary") else listOf("primary"), calls)
        }
    }

    @Test fun `quatro estados com textos do prototipo (fonte 1_0)`() = everythingReachable(1f)
    @Test fun `quatro estados com fonte 1_5`() = everythingReachable(1.5f)
    @Test fun `quatro estados com fonte 2_0`() = everythingReachable(2f)

    @Test fun `so a permissao tem botao secundario`() {
        show(RecoveryReason.BluetoothOff)
        rule.onNodeWithTag("status-secondary").assertDoesNotExistCompat()
    }

    @Test fun `permissao - botao extra fica acima do principal como no FigCraft`() {
        show(RecoveryReason.PermissionDenied)
        val extraTop = rule.onNodeWithTag("status-secondary").fetchSemanticsNode().boundsInRoot.top
        val mainTop = rule.onNodeWithTag("status-primary").fetchSemanticsNode().boundsInRoot.top
        assertTrue("extra ($extraTop) deve ficar acima do principal ($mainTop)", extraTop < mainTop)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()
}
