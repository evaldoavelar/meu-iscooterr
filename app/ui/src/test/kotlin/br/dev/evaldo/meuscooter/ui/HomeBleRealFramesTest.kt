package br.dev.evaldo.meuscooter.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import br.dev.evaldo.meuscooter.data.BleScooterTransport
import br.dev.evaldo.meuscooter.data.FakeGattClient
import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.ui.home.HomeActions
import br.dev.evaldo.meuscooter.ui.home.HomeScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ponta a ponta sem BLE real: `BleScooterTransport` + `FakeGattClient` alimentado com os quadros reais da captura S2
 * (mesmos literais de BleScooterTransportTest) -> mapper -> Home. Regressão do travamento da 4.3b
 * (`size 0 must be greater than zero`: catálogo de modos vazio + campos nulos).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeBleRealFramesTest {
    @get:Rule val rule = createComposeRule()

    private val meter1 = hex("1aa120031200000064000000320000000023232302030853f81ff1")
    private val meter2 = hex("1aa121031200004a1500000000180000000000240f0308ec4f1ff1")
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun `quadros reais Meter1 e Meter2 renderizam a Home sem excecao com modos vazios e campos nulos`() = runTest {
        val gatt = FakeGattClient(Clock { currentTime })
        val transport = BleScooterTransport(backgroundScope, Clock { currentTime }, gatt, "AA:BB:CC:DD:EE:FF")
        var last: Telemetry? = null
        backgroundScope.launch { transport.telemetry.collect { last = it } }
        backgroundScope.launch { transport.connect() }
        testScheduler.advanceTimeBy(50); testScheduler.runCurrent()
        gatt.emit(meter1); gatt.emit(meter2)
        testScheduler.runCurrent()

        val t = last!!
        assertTrue(transport.speedModes.options().isEmpty())
        assertNull(t.voltageV); assertNull(t.currentA)   // não decodificados: nulos de verdade
        val ui = HomeUiMapper(transport.speedModes.options(), null) { "14:32" }
            .map(HomeState(ConnectionStatus.Connected, t, currentTime, emptyMap(), emptyMap(), meter1AtMs = t.receivedAtMs))
        assertTrue(ui.mode.options.isEmpty())

        rule.setContent { MeuPatineteTheme { HomeScreen(ui, HomeActions()) } }
        rule.onNodeWithTag("motors-card").assertIsDisplayed()
        rule.onNodeWithTag("mode-card").assertIsDisplayed().assertIsNotEnabled()
        assertEquals("100%", ui.battery)
    }
}
