package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.GeoPoint
import br.dev.evaldo.meuscooter.domain.backup.BackupApplyResult
import br.dev.evaldo.meuscooter.domain.backup.BackupContents
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupFilesStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val trips get() = File(tmp.root, "trips.v1")
    private val routes get() = File(tmp.root, "routes")
    private fun store() = BackupFilesStore(BackupFiles(trips, routes))

    @Test fun `snapshot e apply repassam ao BackupFiles e mapeiam os tres resultados`() {
        trips.writeText("TRIPS2|2\n1|1700000000000|1.5|600000|15.0|25.0|-|80|70|-\n")
        FileRouteStore(routes).write(1, listOf(GeoPoint(-23.55, -46.63), GeoPoint(-23.56, -46.64)))
        val snap = store().snapshot(mapOf("theme" to "Dark"), "1234".toCharArray())
        assertEquals(1, snap.routes.size); assertEquals(mapOf("theme" to "Dark"), snap.preferences); assertEquals("1234", String(snap.pin!!))

        val ok = store().apply(BackupContents(mapOf("theme" to "Light", "x" to "y"), "TRIPS1\n7|1700000000000|3.0|600000|15.0|25.0|-|-|-\n".toByteArray(), emptyMap(), null))
        val applied = ok as BackupApplyResult.Applied
        assertEquals("Light", applied.preferences["theme"]); assertTrue("x" !in applied.preferences)
        assertEquals(1, applied.tripCount); assertEquals(0, applied.routeCount)
        assertEquals(BackupApplyResult.Invalid, store().apply(BackupContents(emptyMap(), "LIXO".toByteArray(), emptyMap(), null)))
        // .bkold orfão com trips corrompido: IoFailure (pendência herdada)
        File(trips.path + ".bkold").writeText("copia"); trips.writeText("lixo")
        assertTrue(store().apply(BackupContents(emptyMap(), null, emptyMap(), null)) === BackupApplyResult.IoFailure)
    }
}
