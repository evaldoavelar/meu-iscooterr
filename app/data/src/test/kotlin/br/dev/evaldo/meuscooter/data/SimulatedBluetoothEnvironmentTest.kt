package br.dev.evaldo.meuscooter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S2: ambiente Bluetooth SIMULADO (nenhuma chamada Android real). */
class SimulatedBluetoothEnvironmentTest {
    @Test fun `comeca ligado e com permissao`() {
        val e = SimulatedBluetoothEnvironment()
        assertTrue(e.bluetoothEnabled.value); assertTrue(e.permissionGranted.value)
    }

    @Test fun `estado inicial pode ser definido`() {
        val e = SimulatedBluetoothEnvironment(bluetoothEnabled = false, permissionGranted = false)
        assertFalse(e.bluetoothEnabled.value); assertFalse(e.permissionGranted.value)
    }

    @Test fun `ativar bluetooth e permitir ligam os fluxos`() {
        val e = SimulatedBluetoothEnvironment(false, false)
        e.requestEnableBluetooth(); assertTrue(e.bluetoothEnabled.value); assertFalse(e.permissionGranted.value)
        e.requestPermission(); assertTrue(e.permissionGranted.value)
    }

    @Test fun `testes controlam o ambiente`() {
        val e = SimulatedBluetoothEnvironment()
        e.setBluetoothEnabled(false); e.setPermissionGranted(false)
        assertFalse(e.bluetoothEnabled.value); assertFalse(e.permissionGranted.value)
    }

    @Test fun `abrir configuracoes so conta a chamada (simulado)`() {
        val e = SimulatedBluetoothEnvironment()
        e.openSystemSettings(); e.openSystemSettings()
        assertEquals(2, e.settingsOpenedCount)
    }
}
