package br.dev.evaldo.meuscooter

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lê o manifest-fonte (o diretório de trabalho do teste é o do módulo). */
class ManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test fun `declara INTERNET para baixar tiles do mapa`() {
        assertTrue(manifest.contains("android.permission.INTERNET"))
    }

    /** S52: backup/restauração usa o seletor do Android (SAF) e o cofre existente: nenhuma permissão nova (nem Drive/armazenamento). */
    @Test fun `conjunto de permissoes e o esperado - backup nao adicionou nenhuma`() {
        val declared = Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(manifest).map { it.groupValues[1].removePrefix("android.permission.") }.toSet()
        val expected = setOf(
            "BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "BLUETOOTH", "BLUETOOTH_ADMIN", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "INTERNET",
            "USE_BIOMETRIC", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_CONNECTED_DEVICE", "FOREGROUND_SERVICE_LOCATION", "POST_NOTIFICATIONS",
        )
        org.junit.Assert.assertEquals(expected, declared)
    }
}
