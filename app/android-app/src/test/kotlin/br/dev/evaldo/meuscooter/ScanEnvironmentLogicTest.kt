package br.dev.evaldo.meuscooter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 0006 (S41): Lógica pura de decisão sobre exigência de localização para varredura BLE conforme versão do Android.
 * Em API < 31, a varredura BLE exige localização ativada no sistema.
 * Em API >= 31, a permissão BLUETOOTH_SCAN desacopla a varredura da localização.
 */
class ScanEnvironmentLogicTest {

    @Test
    fun `em API 31 ou superior a localizacao nao bloqueia mesmo desligada`() {
        assertTrue(ScanEnvironmentLogic.isLocationReady(sdkInt = 31, locationEnabled = false))
        assertTrue(ScanEnvironmentLogic.isLocationReady(sdkInt = 33, locationEnabled = false))
        assertTrue(ScanEnvironmentLogic.isLocationReady(sdkInt = 35, locationEnabled = false))
    }

    @Test
    fun `em API 31 ou superior com localizacao ligada esta pronta`() {
        assertTrue(ScanEnvironmentLogic.isLocationReady(sdkInt = 31, locationEnabled = true))
        assertTrue(ScanEnvironmentLogic.isLocationReady(sdkInt = 34, locationEnabled = true))
    }

    @Test
    fun `em API menor que 31 localizacao desligada bloqueia a varredura`() {
        assertFalse(ScanEnvironmentLogic.isLocationReady(sdkInt = 30, locationEnabled = false))
        assertFalse(ScanEnvironmentLogic.isLocationReady(sdkInt = 29, locationEnabled = false))
        assertFalse(ScanEnvironmentLogic.isLocationReady(sdkInt = 26, locationEnabled = false))
    }

    @Test
    fun `em API menor que 31 localizacao ligada esta pronta para varredura`() {
        assertTrue(ScanEnvironmentLogic.isLocationReady(sdkInt = 30, locationEnabled = true))
        assertTrue(ScanEnvironmentLogic.isLocationReady(sdkInt = 28, locationEnabled = true))
        assertTrue(ScanEnvironmentLogic.isLocationReady(sdkInt = 26, locationEnabled = true))
    }
}
