package br.dev.evaldo.meuscooter.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import br.dev.evaldo.meuscooter.domain.TripSummary
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.ui.telemetry.TelemetryScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S4: textos esperados de design/telemetry.py e design/history.py (diálogo "Zerar a viagem atual?"). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TelemetryScreenTest {
    @get:Rule val rule = createComposeRule()

    private val state = TelemetryUiMapper().map(
        Telemetry(receivedAtMs = 1, speedKmh = 18f, batteryPercent = 82, totalKm = 1248.4f, voltageV = 54.9f, currentA = 8.4f, motor1TempC = 41, motor2TempC = 39),
        TripSummary(2.5f, 760_000, 16.2f, 27f),
    )

    private var backs = 0
    private var zeros = 0
    private var histories = 0

    private fun show(s: TelemetryUiState = state, fontScale: Float = 1f) = rule.setContent {
        MeuPatineteTheme(darkTheme = false) {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                TelemetryScreen(s, onBack = { backs++ }, onZeroTrip = { zeros++ }, onOpenHistory = { histories++ })
            }
        }
    }

    @Test fun `mostra velocidade, bateria e consumo`() {
        show()
        rule.onNodeWithText("Telemetria").assertIsDisplayed()
        rule.onNodeWithTag("speed-value").assertIsDisplayed()
        rule.onNodeWithText("18").assertIsDisplayed()
        rule.onNodeWithText("82%").assertIsDisplayed()
        rule.onNodeWithText("461 W").assertIsDisplayed()
        rule.onNodeWithText("25,6 Wh/km").assertIsDisplayed()
    }

    @Test fun `rolando mostra temperaturas, odometros e viagem atual`() {
        show()
        rule.onNodeWithText("Viagem atual").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("12:40").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("1.248 km").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Velocidade máxima").performScrollTo().assertIsDisplayed()
    }

    @Test fun `voltar aciona onBack`() {
        show()
        rule.onNodeWithContentDescription("Voltar").performClick()
        assertEquals(1, backs)
    }

    @Test fun `zerar viagem pede confirmacao e so zera ao confirmar`() {
        show()
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Zerar viagem").performClick()
        rule.onNodeWithText("Zerar a viagem atual?").assertIsDisplayed()
        rule.onNodeWithText("Ela será salva no histórico e o contador recomeça do zero. Nada é enviado ao patinete.").assertIsDisplayed()
        assertEquals(0, zeros)
        rule.onNodeWithText("Zerar").performClick()
        assertEquals(1, zeros)
    }

    @Test fun `cancelar o dialogo nao zera`() {
        show()
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Zerar viagem").performClick()
        rule.onNodeWithText("Cancelar").performClick()
        assertEquals(0, zeros)
        rule.onNodeWithText("Zerar a viagem atual?").assertDoesNotExist()
    }

    @Test fun `menu tem Ver historico`() {
        show()
        rule.onNodeWithContentDescription("Mais opções").performClick()
        rule.onNodeWithText("Ver histórico").performClick()
        assertEquals(1, histories)
    }

    @Test fun `motor 2 sem leitura mostra a nota`() {
        show(TelemetryUiMapper().map(Telemetry(receivedAtMs = 1, speedKmh = 0f, motor1TempC = 22), null))
        rule.onNodeWithTag("motor2-note").performScrollTo().assertIsDisplayed()
    }

    @Test fun `escala de fonte 2_0 continua exibindo o essencial`() {
        show(fontScale = 2f)
        rule.onNodeWithTag("speed-value").assertIsDisplayed()
        rule.onNodeWithContentDescription("Voltar").assertIsDisplayed()
    }
}
