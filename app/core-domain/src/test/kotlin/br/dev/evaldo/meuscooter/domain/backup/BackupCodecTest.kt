package br.dev.evaldo.meuscooter.domain.backup

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {
    private val pw = "senha-forte-123".toCharArray()
    private val tripsText = "TRIPS2|3\n1|1700000000000|1.5|600000|15.0|25.0|-|80|70|-\n2|1700100000000|2.5|900000|10.0|20.0|-|-|-|-\n"
    private val route = byteArrayOf(1, 2, 3, 4, 5)
    private fun contents(pin: String? = "123456", prefs: Map<String, String> = mapOf("theme" to "Dark", "scooterAddress" to "AA:BB:CC:DD:EE:FF")) =
        BackupContents(prefs, tripsText.toByteArray(), mapOf("1" to route, "2" to byteArrayOf(9)), pin?.toCharArray())
    private fun enc(c: BackupContents = contents(), at: Long = 1_700_000_000_000L) = BackupCodec.encode(c, pw, at, "1.2.3", SecureRandom())
    private fun ok(r: BackupResult<BackupContents>) = (r as BackupResult.Ok).value
    private fun err(r: BackupResult<*>) = (r as BackupResult.Err).error

    @Test fun `round trip devolve conteudo igual byte a byte`() {
        val c = contents()
        val d = ok(BackupCodec.decode(enc(c), pw))
        assertTrue(c.sameAs(d))
        assertEquals("123456", String(d.pin!!))
        assertArrayEquals(route, d.routes["1"])
        assertArrayEquals(tripsText.toByteArray(), d.tripsFile)
    }

    @Test fun `senha errada e erro generico`() {
        assertEquals(BackupError.WrongPasswordOrCorrupt, err(BackupCodec.decode(enc(), "outra-senha-9".toCharArray())))
    }

    @Test fun `um bit alterado em qualquer byte do cabecalho ou do corpo invalida`() {
        val file = enc()
        val headerLen = (BackupCodec.peek(file) as BackupResult.Ok).value.headerLength
        // versão (byte 4) tem resultado próprio; os demais, genérico. Todo byte do cabeçalho e uma amostra do corpo.
        val positions = (0 until headerLen) + listOf(headerLen, headerLen + 10, file.size / 2, file.size - 1)
        for (p in positions) {
            val t = file.clone(); t[p] = (t[p].toInt() xor 1).toByte()
            val e = err(BackupCodec.decode(t, pw))
            assertTrue("byte $p: $e", e == BackupError.WrongPasswordOrCorrupt || (p == 4 && e == BackupError.UnsupportedVersion))
        }
    }

    @Test fun `versao futura e UnsupportedVersion`() {
        val t = enc(); t[4] = 2
        assertEquals(BackupError.UnsupportedVersion, err(BackupCodec.decode(t, pw)))
        assertEquals(BackupError.UnsupportedVersion, err(BackupCodec.peek(t)))
    }

    @Test fun `arquivo truncado ou vazio ou magic errado e rejeitado`() {
        val file = enc()
        for (n in listOf(0, 3, 10, 40, file.size - 1)) assertEquals("n=$n", BackupError.WrongPasswordOrCorrupt, err(BackupCodec.decode(file.copyOf(n), pw)))
        val m = file.clone(); m[0] = 'X'.code.toByte()
        assertEquals(BackupError.WrongPasswordOrCorrupt, err(BackupCodec.peek(m)))
    }

    @Test fun `arquivo acima do teto e TooLarge`() {
        assertEquals(BackupError.TooLarge, err(BackupCodec.peek(ByteArray(BackupCodec.MAX_FILE_BYTES + 1))))
    }

    @Test fun `sem PIN o backup sai sem a entrada e o resumo diz nao guardado`() {
        val d = ok(BackupCodec.decode(enc(contents(pin = null)), pw))
        assertEquals(null, d.pin)
        val s = d.summary((BackupCodec.peek(enc(contents(pin = null), 5L)) as BackupResult.Ok).value)
        assertFalse(s.hasPin); assertEquals(2, s.tripCount); assertEquals(2, s.routeCount); assertEquals(5L, s.createdAtMs); assertEquals("1.2.3", s.appVersion)
    }

    @Test fun `toString nao vaza PIN preferencias nem bytes`() {
        val c = contents(pin = "987654")
        val s = c.toString()
        assertFalse(s.contains("987654")); assertFalse(s.contains("AA:BB")); assertFalse(s.contains("TRIPS"))
        assertFalse((BackupCodec.peek(enc(c)) as BackupResult.Ok).value.toString().contains("987654"))
        assertFalse(BackupResult.Ok(c).toString().contains("987654"))
    }

    @Test fun `cabecalho declara KDF pelo menos 600000 e o layout documentado`() {
        val file = enc(at = 1_700_000_000_000L)
        val b = ByteBuffer.wrap(file)
        assertEquals("IXBK", String(file, 0, 4)); assertEquals(1, file[4].toInt())
        assertEquals(1_700_000_000_000L, b.getLong(5))
        val appLen = b.getShort(13).toInt(); assertEquals("1.2.3", String(file, 15, appLen))
        val kdfAt = 15 + appLen
        assertEquals(1, file[kdfAt].toInt())
        assertTrue(b.getInt(kdfAt + 1) >= 600_000)
        assertTrue((BackupCodec.peek(file) as BackupResult.Ok).value.kdfIterations >= 600_000)
    }

    @Test fun `dois backups do mesmo conteudo tem salt IV e corpo diferentes`() {
        val a = (BackupCodec.peek(enc()) as BackupResult.Ok).value; val b = (BackupCodec.peek(enc()) as BackupResult.Ok).value
        assertFalse(a.salt.contentEquals(b.salt)); assertFalse(a.iv.contentEquals(b.iv))
        assertFalse(enc().contentEquals(enc()))
    }

    @Test fun `implementacao independente do formato decifra o arquivo`() {
        val file = enc(contents(pin = "4321"))
        val h = (BackupCodec.peek(file) as BackupResult.Ok).value
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw, h.salt, h.kdfIterations, 256)).encoded
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, h.iv))
        c.updateAAD(file.copyOfRange(0, h.headerLength))
        val zip = c.doFinal(file, h.headerLength, file.size - h.headerLength)
        val names = mutableMapOf<String, String>()
        ZipInputStream(zip.inputStream()).use { z -> generateSequence { z.nextEntry }.forEach { names[it.name] = String(z.readBytes()) } }
        assertEquals(setOf("preferences", "trips.v1", "routes/1.route", "routes/2.route", "pin", "manifest"), names.keys)
        assertEquals("4321", names["pin"]); assertEquals("trips=2\nroutes=2\n", names["manifest"])
        assertTrue(names["preferences"]!!.lines().containsAll(listOf("theme=Dark", "scooterAddress=AA:BB:CC:DD:EE:FF")))
        // PIN nunca em claro no arquivo.
        assertFalse(String(file, Charsets.ISO_8859_1).contains("4321"))
    }

    @Test fun `senha curta na escrita e recusada`() {
        val e = runCatching { BackupCodec.encode(contents(), "1234567".toCharArray(), 0, "1", SecureRandom()) }.exceptionOrNull() as BackupException
        assertEquals(BackupError.PasswordTooShort, e.error)
        assertFalse(e.toString().contains("1234567"))
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z -> entries.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return o.toByteArray()
    }
    private fun decodeZip(zip: ByteArray) = BackupCodec.decode(BackupCodec.encrypt(zip, pw, 1L, "1", SecureRandom()), pw)
    private val okManifest = "manifest" to "trips=0\nroutes=0\n".toByteArray()

    @Test fun `zip valido montado a mao decodifica`() {
        val d = ok(decodeZip(zipOf("preferences" to "theme=Dark\n".toByteArray(), okManifest)))
        assertEquals(mapOf("theme" to "Dark"), d.preferences)
    }

    @Test fun `zip com caminho fora do diretorio e rejeitado`() {
        for (name in listOf("../x", "/etc/passwd", "routes/../../x.route", "routes/a/b.route", "extra")) {
            assertEquals(name, BackupError.InvalidContent, err(decodeZip(zipOf("preferences" to ByteArray(0), name to byteArrayOf(1), okManifest))))
        }
    }

    @Test fun `sem manifesto ou manifesto divergente e rejeitada`() {
        assertEquals(BackupError.InvalidContent, err(decodeZip(zipOf("preferences" to ByteArray(0)))))
        assertEquals(BackupError.InvalidContent, err(decodeZip(zipOf("preferences" to ByteArray(0), "manifest" to "trips=5\nroutes=0\n".toByteArray()))))
        assertEquals(BackupError.InvalidContent, err(decodeZip(ByteArray(10) { 7 })))
    }

    @Test fun `zip bomb e TooLarge`() {
        val bomb = zipOf("preferences" to ByteArray(0), "trips.v1" to ByteArray(BackupCodec.MAX_UNCOMPRESSED_BYTES + 1), okManifest)
        assertTrue("compacta bem", bomb.size < 1_000_000)
        assertEquals(BackupError.TooLarge, err(decodeZip(bomb)))
    }

    @Test fun `entradas demais e TooLarge`() {
        val many = zipOf(*((1..BackupCodec.MAX_ENTRIES + 1).map { "routes/$it.route" to ByteArray(0) }.toTypedArray()))
        assertEquals(BackupError.TooLarge, err(decodeZip(many)))
    }

    @Test fun `preferencias com quebra de linha na escrita sao recusadas`() {
        val e = runCatching { enc(contents(prefs = mapOf("a" to "x\ny"))) }.exceptionOrNull() as BackupException
        assertEquals(BackupError.InvalidContent, e.error)
    }

    @Test fun `wipe zera pin e bytes`() {
        val c = contents(); c.wipe()
        assertTrue(c.pin!!.all { it == '\u0000' }); assertTrue(c.tripsFile!!.all { it == 0.toByte() })
        assertNotEquals(0, route.size)
    }

    @Test fun `BackupPreferences ignora desconhecidas normaliza MAC e aplica padrao em invalido`() {
        val s = BackupPreferences.sanitize(mapOf("theme" to "Neon", "units" to "Imperial", "scooterAddress" to " aa:bb:cc:dd:ee:ff ", "hack" to "1", "saveRoute" to "talvez"))
        assertFalse("hack" in s); assertEquals("System", s["theme"]); assertEquals("Imperial", s["units"])
        assertEquals("AA:BB:CC:DD:EE:FF", s["scooterAddress"]); assertEquals("true", s["saveRoute"])
        assertEquals("", BackupPreferences.sanitize(mapOf("scooterAddress" to "xx"))["scooterAddress"])
    }
}
