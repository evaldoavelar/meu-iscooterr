package br.dev.evaldo.meuscooter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealBleSelectionTest {
    @Test fun realBleNeedsDebuggableBuildExplicitFlagAndAnAddress() {
        assertTrue(RealBleSelection.enabled(debuggable = true, requested = true, address = "AA:BB:CC:DD:EE:FF"))
        assertFalse("padrão: desligado", RealBleSelection.enabled(debuggable = true, requested = false, address = "AA:BB:CC:DD:EE:FF"))
        assertFalse("build de produção nunca liga", RealBleSelection.enabled(debuggable = false, requested = true, address = "AA:BB:CC:DD:EE:FF"))
        assertFalse(RealBleSelection.enabled(debuggable = true, requested = true, address = null))
        assertFalse(RealBleSelection.enabled(debuggable = true, requested = true, address = "  "))
    }

    // Bits de BluetoothGattCharacteristic.properties (especificação Bluetooth Core: Notify 0x10, Indicate 0x20).
    @Test fun cccdPrefersNotifyThenIndicateElseNothing() {
        assertEquals(CccdMode.Notify, GattProperties.cccdMode(0x10))
        assertEquals(CccdMode.Notify, GattProperties.cccdMode(0x12 or 0x20))
        assertEquals(CccdMode.Indicate, GattProperties.cccdMode(0x20))
        assertNull(GattProperties.cccdMode(0x02 or 0x04))
        assertNull(GattProperties.cccdMode(0))
    }

    @Test fun validBluetoothAddressFormat() {
        assertTrue(GattProperties.isValidAddress("AA:BB:CC:DD:EE:FF"))
        assertTrue(GattProperties.isValidAddress("aa:bb:cc:dd:ee:0f"))
        assertFalse(GattProperties.isValidAddress("AA:BB:CC:DD:EE"))
        assertFalse(GattProperties.isValidAddress("AA:BB:CC:DD:EE:GG"))
        assertFalse(GattProperties.isValidAddress(""))
    }
}
