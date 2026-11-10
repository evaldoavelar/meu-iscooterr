package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.GeoPoint
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileRouteStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val pts = listOf(GeoPoint(-23.5505, -46.6333), GeoPoint(-23.5510, -46.6340))

    @Test fun `persiste e le com nova instancia`() {
        val dir = tmp.newFolder("r")
        FileRouteStore(dir).write(7, pts)
        assertEquals(pts, FileRouteStore(dir).read(7))
    }

    @Test fun `trajeto inexistente le vazio`() {
        assertTrue(FileRouteStore(tmp.newFolder("r")).read(1).isEmpty())
    }

    @Test fun `escrever lista vazia remove o arquivo`() {
        val s = FileRouteStore(tmp.newFolder("r"))
        s.write(1, pts); s.write(1, emptyList())
        assertTrue(s.read(1).isEmpty())
    }

    @Test fun `arquivo truncado ou corrompido le vazio`() {
        val dir = tmp.newFolder("r")
        val s = FileRouteStore(dir)
        File(dir, "1.route").writeBytes(byteArrayOf(0, 0, 0, 5, 1, 2))      // diz 5 pontos, traz 2 bytes
        File(dir, "2.route").writeBytes(byteArrayOf(0x7f, 0, 0, 0))         // contagem absurda
        assertTrue(s.read(1).isEmpty()); assertTrue(s.read(2).isEmpty())
    }

    @Test fun `arquivo com NaN infinito ou fora da faixa le vazio`() {
        val dir = tmp.newFolder("r")
        val s = FileRouteStore(dir)
        fun raw(id: Int, lat: Double, lon: Double) = java.io.DataOutputStream(File(dir, "$id.route").outputStream()).use { it.writeInt(1); it.writeDouble(lat); it.writeDouble(lon) }
        raw(1, Double.NaN, 0.0); raw(2, 0.0, Double.POSITIVE_INFINITY); raw(3, 91.0, 0.0); raw(4, 0.0, 181.0)
        (1..4).forEach { assertTrue(s.read(it.toLong()).isEmpty()) }
    }

    @Test fun `delete apaga um e clear apaga todos`() {
        val dir = tmp.newFolder("r")
        val s = FileRouteStore(dir)
        s.write(1, pts); s.write(2, pts)
        s.delete(1); assertTrue(s.read(1).isEmpty()); assertEquals(pts, s.read(2))
        File(dir, "9.tmp").writeBytes(byteArrayOf(1))
        s.clear()
        assertTrue(s.read(2).isEmpty()); assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun `apaga temporarios orfaos ao abrir`() {
        val dir = tmp.newFolder("r")
        File(dir, "3.tmp").writeBytes(byteArrayOf(1))
        FileRouteStore(dir)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
