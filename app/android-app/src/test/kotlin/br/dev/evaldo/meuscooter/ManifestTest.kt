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
}
