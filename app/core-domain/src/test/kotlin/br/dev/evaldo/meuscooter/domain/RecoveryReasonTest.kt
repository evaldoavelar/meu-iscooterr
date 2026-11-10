package br.dev.evaldo.meuscooter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** S1: motivos de recuperação (design/screens.py `status`). Prioridade: permissão > Bluetooth > fora de alcance > reconectando. */
class RecoveryReasonTest {
    private val ok = BluetoothState(bluetoothEnabled = true, permissionGranted = true)

    @Test fun `sem problema nenhum nao ha motivo`() {
        assertNull(RecoveryReason.from(ok, outOfRange = false, reconnectAttempt = null))
    }

    @Test fun `permissao negada`() {
        assertEquals(RecoveryReason.PermissionDenied, RecoveryReason.from(ok.copy(permissionGranted = false), false, null))
    }

    @Test fun `bluetooth desligado`() {
        assertEquals(RecoveryReason.BluetoothOff, RecoveryReason.from(ok.copy(bluetoothEnabled = false), false, null))
    }

    @Test fun `localizacao desligada`() {
        assertEquals(RecoveryReason.LocationOff, RecoveryReason.from(ok.copy(locationEnabled = false), false, null))
    }

    @Test fun `fora de alcance`() {
        assertEquals(RecoveryReason.OutOfRange, RecoveryReason.from(ok, outOfRange = true, reconnectAttempt = null))
    }

    @Test fun `reconectando guarda a tentativa e o maximo 5`() {
        val r = RecoveryReason.from(ok, false, 2) as RecoveryReason.Reconnecting
        assertEquals(2, r.attempt); assertEquals(5, r.max)
        assertEquals("Tentativa 2 de 5", r.attemptLabel)
    }

    @Test fun `permissao tem prioridade sobre bluetooth`() {
        val none = BluetoothState(bluetoothEnabled = false, permissionGranted = false)
        assertEquals(RecoveryReason.PermissionDenied, RecoveryReason.from(none, true, 1))
    }

    @Test fun `bluetooth tem prioridade sobre fora de alcance e reconectando`() {
        val off = ok.copy(bluetoothEnabled = false)
        assertEquals(RecoveryReason.BluetoothOff, RecoveryReason.from(off, true, 3))
    }

    @Test fun `bluetooth tem prioridade sobre localizacao desligada`() {
        val off = ok.copy(bluetoothEnabled = false, locationEnabled = false)
        assertEquals(RecoveryReason.BluetoothOff, RecoveryReason.from(off, false, null))
    }

    @Test fun `localizacao desligada tem prioridade sobre fora de alcance e reconectando`() {
        val off = ok.copy(locationEnabled = false)
        assertEquals(RecoveryReason.LocationOff, RecoveryReason.from(off, true, 3))
    }

    @Test fun `fora de alcance tem prioridade sobre reconectando`() {
        assertEquals(RecoveryReason.OutOfRange, RecoveryReason.from(ok, true, 5))
    }

    @Test fun `ambiente pronto exige bluetooth, permissao e localizacao`() {
        assertEquals(true, ok.ready)
        assertEquals(false, ok.copy(bluetoothEnabled = false).ready)
        assertEquals(false, ok.copy(permissionGranted = false).ready)
        assertEquals(false, ok.copy(locationEnabled = false).ready)
    }
}
