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

    /** `ble_tx` (4.3a) é chave SEPARADA: só vale em build depurável, com o BLE real já ligado e pedida explicitamente. */
    @Test fun txNeedsDebuggableRealBleAndItsOwnFlag() {
        assertTrue(RealBleSelection.txEnabled(debuggable = true, realBle = true, requestedTx = true))
        assertFalse("padrão: desligado", RealBleSelection.txEnabled(debuggable = true, realBle = true, requestedTx = false))
        assertFalse("sem ble_real não há TX", RealBleSelection.txEnabled(debuggable = true, realBle = false, requestedTx = true))
        assertFalse("build de produção nunca liga", RealBleSelection.txEnabled(debuggable = false, realBle = true, requestedTx = true))
        assertFalse(RealBleSelection.txEnabled(debuggable = false, realBle = false, requestedTx = false))
    }

    // Bits de BluetoothGattCharacteristic.properties (especificação Bluetooth Core: Notify 0x10, Indicate 0x20).
    @Test fun cccdPrefersNotifyThenIndicateElseNothing() {
        assertEquals(CccdMode.Notify, GattProperties.cccdMode(0x10))
        assertEquals(CccdMode.Notify, GattProperties.cccdMode(0x12 or 0x20))
        assertEquals(CccdMode.Indicate, GattProperties.cccdMode(0x20))
        assertNull(GattProperties.cccdMode(0x02 or 0x04))
        assertNull(GattProperties.cccdMode(0))
    }

    // Bits da especificação Bluetooth Core: Write Without Response 0x04, Write 0x08.
    @Test fun writePrefersWithResponseThenWithoutElseNothing() {
        assertEquals(WriteMode.WithResponse, GattProperties.writeMode(0x08))
        assertEquals(WriteMode.WithResponse, GattProperties.writeMode(0x08 or 0x04 or 0x10))
        assertEquals(WriteMode.WithoutResponse, GattProperties.writeMode(0x04))
        assertNull(GattProperties.writeMode(0x10 or 0x02))
        assertNull(GattProperties.writeMode(0))
    }

    @Test fun validBluetoothAddressFormat() {
        assertTrue(GattProperties.isValidAddress("AA:BB:CC:DD:EE:FF"))
        assertTrue(GattProperties.isValidAddress("aa:bb:cc:dd:ee:0f"))
        assertFalse(GattProperties.isValidAddress("AA:BB:CC:DD:EE"))
        assertFalse(GattProperties.isValidAddress("AA:BB:CC:DD:EE:GG"))
        assertFalse(GattProperties.isValidAddress(""))
    }

    private val extra = "AA:BB:CC:DD:EE:01"
    private val saved = "AA:BB:CC:DD:EE:02"

    /** F5.2: com patinete cadastrado o transporte real é o padrão, SEM chaves de depuração (ADR 0006). */
    @Test fun registeredScooterSelectsRealWithoutDebugExtras() {
        val s = RealBleSelection.resolve(debuggable = false, requestedReal = false, extraAddress = null, registeredAddress = saved)
        assertTrue(s.real); assertEquals(saved, s.address); assertFalse(s.registerExtra)
    }

    @Test fun noRegistrationAndNoExtrasKeepsTheFake() {
        val s = RealBleSelection.resolve(debuggable = true, requestedReal = false, extraAddress = null, registeredAddress = null)
        assertFalse(s.real); assertNull(s.address); assertFalse(s.registerExtra)
    }

    @Test fun debugExtrasRegisterTheAddressAndOverrideTheSavedOne() {
        val s = RealBleSelection.resolve(debuggable = true, requestedReal = true, extraAddress = extra, registeredAddress = saved)
        assertTrue(s.real); assertEquals(extra, s.address); assertTrue(s.registerExtra)
    }

    @Test fun debugExtrasWithTheSameAddressDoNotReRegister() {
        val s = RealBleSelection.resolve(debuggable = true, requestedReal = true, extraAddress = extra.lowercase(), registeredAddress = extra)
        assertTrue(s.real); assertFalse(s.registerExtra)
    }

    @Test fun extrasInProductionBuildAreIgnoredButRegistrationStillWorks() {
        val s = RealBleSelection.resolve(debuggable = false, requestedReal = true, extraAddress = extra, registeredAddress = saved)
        assertEquals(saved, s.address); assertFalse(s.registerExtra)
        assertFalse(RealBleSelection.resolve(debuggable = false, requestedReal = true, extraAddress = extra, registeredAddress = null).real)
    }

    @Test fun malformedExtraAddressIsIgnored() {
        val s = RealBleSelection.resolve(debuggable = true, requestedReal = true, extraAddress = "lixo", registeredAddress = null)
        assertFalse(s.real); assertFalse(s.registerExtra)
    }
}
