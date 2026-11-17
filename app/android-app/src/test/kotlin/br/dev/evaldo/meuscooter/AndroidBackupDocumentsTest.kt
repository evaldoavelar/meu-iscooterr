package br.dev.evaldo.meuscooter

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidBackupDocumentsTest {
    @Test fun `leitura limitada devolve tudo ate o teto e null acima dele`() {
        val data = ByteArray(40_000) { (it % 251).toByte() }
        assertArrayEquals(data, AndroidBackupDocuments.readLimited(ByteArrayInputStream(data), 40_000))
        assertNull(AndroidBackupDocuments.readLimited(ByteArrayInputStream(data), 39_999))
        assertArrayEquals(ByteArray(0), AndroidBackupDocuments.readLimited(ByteArrayInputStream(ByteArray(0)), 10))
    }
}
