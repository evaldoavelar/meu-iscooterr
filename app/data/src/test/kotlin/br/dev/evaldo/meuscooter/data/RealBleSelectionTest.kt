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

    /** `ble_tx` (4.3a) é chave SEPARADA: em build depurável vale com o BLE real ligado e pedida explicitamente. */
    @Test fun debugTxNeedsDebuggableRealBleAndItsOwnFlag() {
        assertTrue(RealBleSelection.txEnabled(debuggable = true, realBle = true, requestedTx = true, registered = false))
        assertFalse("padrão: desligado", RealBleSelection.txEnabled(debuggable = true, realBle = true, requestedTx = false, registered = false))
        assertFalse("sem ble_real não há TX", RealBleSelection.txEnabled(debuggable = true, realBle = false, requestedTx = true, registered = false))
        assertFalse("build de produção sem cadastro nunca liga", RealBleSelection.txEnabled(debuggable = false, realBle = true, requestedTx = true, registered = false))
        assertFalse(RealBleSelection.txEnabled(debuggable = false, realBle = false, requestedTx = false, registered = false))
    }

    /** Decisão do proprietário (2026-11-06, ADR 0006 adendo): com patinete cadastrado e BLE real o TX liga SEM extras. */
    @Test fun registeredScooterEnablesTxWithoutDebugExtras() {
        assertTrue(RealBleSelection.txEnabled(debuggable = false, realBle = true, requestedTx = false, registered = true))
        assertTrue(RealBleSelection.txEnabled(debuggable = true, realBle = true, requestedTx = false, registered = true))
        assertFalse("cadastro sem BLE real não liga TX", RealBleSelection.txEnabled(debuggable = false, realBle = false, requestedTx = false, registered = true))
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

    /** O TX por cadastro só vale para o MAC JÁ cadastrado: extra de depuração com outro MAC não herda o TX. */
    @Test fun registeredFlagOnlyForTheSavedAddress() {
        assertTrue(RealBleSelection.resolve(false, false, null, saved).registered)
        assertTrue(RealBleSelection.resolve(true, true, saved.lowercase(), saved).registered)
        assertFalse(RealBleSelection.resolve(true, true, extra, saved).registered)
        assertFalse(RealBleSelection.resolve(true, true, extra, null).registered)
        assertFalse(RealBleSelection.resolve(true, false, null, null).registered)
    }
}
