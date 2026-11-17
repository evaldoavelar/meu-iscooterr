package br.dev.evaldo.meuscooter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S52: regra de "Economia de energia que para o GPS". Valores de modo literais da API do Android (0 NO_CHANGE, 1 GPS off, 2 ALL off, 3 FOREGROUND_ONLY, 4 THROTTLE). */
class PowerSaveLogicTest {
    @Test fun `economia desligada nunca compromete o GPS`() {
        for (sdk in listOf(26, 27, 28, 35)) for (mode in 0..4) assertFalse("sdk=$sdk mode=$mode", PowerSaveLogic.gpsImpaired(sdk, false, mode))
    }

    @Test fun `API 28 ou maior exige modo 1, 2 ou 4`() {
        for (sdk in listOf(28, 33, 35)) {
            assertTrue(PowerSaveLogic.gpsImpaired(sdk, true, 1))
            assertTrue(PowerSaveLogic.gpsImpaired(sdk, true, 2))
            assertTrue(PowerSaveLogic.gpsImpaired(sdk, true, 4))
            assertFalse(PowerSaveLogic.gpsImpaired(sdk, true, 0))
            assertFalse(PowerSaveLogic.gpsImpaired(sdk, true, 3))
        }
    }

    @Test fun `API menor que 28 basta isPowerSaveMode`() {
        for (sdk in listOf(26, 27)) for (mode in 0..4) assertTrue("sdk=$sdk mode=$mode", PowerSaveLogic.gpsImpaired(sdk, true, mode))
    }
}
