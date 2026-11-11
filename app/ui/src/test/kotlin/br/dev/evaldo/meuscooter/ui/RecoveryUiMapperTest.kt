package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.RecoveryReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S1/ui: textos EXATOS de design/screens.py (`status`) e design/build.py (Recuperacao-*). */
class RecoveryUiMapperTest {
    @Test fun `fora de alcance`() {
        val u = RecoveryUiMapper.map(RecoveryReason.OutOfRange)
        assertEquals(StatusIconKind.Scooter, u.icon); assertEquals(StatusTone.Pending, u.tone)
        assertEquals("Patinete não encontrado", u.title)
        assertEquals("Ele pode estar desligado ou longe. Ligue e chegue perto: o app reconecta sozinho.", u.text)
        assertEquals("Tentar agora", u.primaryLabel); assertNull(u.secondaryLabel); assertFalse(u.primaryTonal)
    }

    @Test fun `bluetooth desligado`() {
        val u = RecoveryUiMapper.map(RecoveryReason.BluetoothOff)
        assertEquals(StatusIconKind.Bluetooth, u.icon); assertEquals(StatusTone.Pending, u.tone)
        assertEquals("Bluetooth desligado", u.title)
        assertEquals("O app precisa do Bluetooth para falar com o patinete.", u.text)
        assertEquals("Ativar Bluetooth", u.primaryLabel); assertNull(u.secondaryLabel)
    }

    @Test fun `localizacao desligada`() {
        val u = RecoveryUiMapper.map(RecoveryReason.LocationOff)
        assertEquals(StatusIconKind.Location, u.icon); assertEquals(StatusTone.Pending, u.tone)
        assertEquals("Localização desligada", u.title)
        assertEquals("Ligue a localização do aparelho para procurar o patinete", u.text)
        assertEquals("Abrir configurações", u.primaryLabel); assertNull(u.secondaryLabel)
    }

    @Test fun `permissao negada tem acao secundaria`() {
        val u = RecoveryUiMapper.map(RecoveryReason.PermissionDenied)
        assertEquals(StatusIconKind.Alert, u.icon); assertEquals(StatusTone.Error, u.tone)
        assertEquals("Falta uma permissão", u.title)
        assertEquals("O Android pede acesso a “Dispositivos próximos” para achar o patinete. Usamos só para isso.", u.text)
        assertEquals("Permitir", u.primaryLabel); assertEquals("Abrir configurações do Android", u.secondaryLabel)
    }

    @Test fun `reconectando mostra a tentativa e so oferece cancelar`() {
        val u = RecoveryUiMapper.map(RecoveryReason.Reconnecting(2))
        assertEquals(StatusIconKind.Spin, u.icon); assertEquals(StatusTone.Primary, u.tone)
        assertEquals("Reconectando…", u.title)
        assertEquals("Tentativa 2 de 5. Mantenha o patinete ligado e por perto.", u.text)
        assertEquals("Cancelar", u.primaryLabel); assertTrue(u.primaryTonal); assertNull(u.secondaryLabel)
    }
}
