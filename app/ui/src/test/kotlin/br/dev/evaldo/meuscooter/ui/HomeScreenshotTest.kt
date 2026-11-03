package br.dev.evaldo.meuscooter.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import br.dev.evaldo.meuscooter.domain.HomeState
import br.dev.evaldo.meuscooter.domain.Outcome
import br.dev.evaldo.meuscooter.domain.PendingOp
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.Control
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.SpeedModeOption
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.ui.home.HomeActions
import br.dev.evaldo.meuscooter.ui.home.HomeScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Seam S5: screenshots reais do Compose (Robolectric, gráficos nativos) contra `design/out/{claro,escuro}`.
 * Referência independente: os PNGs do FigCraft (720×1560 = 360×780 dp @ xhdpi). Saída: `build/screenshots/`.
 * Fidelidade pixel a pixel não é o critério (fontes/antialias diferem): comparam-se cor de fundo, cor do botão
 * principal e uma métrica de diferença média (luma, reduzida) registrada em `report.csv`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class HomeScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val modes = listOf(SpeedModeOption(0, "Pedestre"), SpeedModeOption(1, "Eco"), SpeedModeOption(2, "Race"), SpeedModeOption(3, "Sport"))
    private val mapper = HomeUiMapper(modes, "iX7 Pro") { "14:32" }
    private val out = File("build/screenshots").also { it.mkdirs() }
    private val design = File("../../design/out")
    private val report = StringBuilder("tema,estado,variante,mad_luma\n")

    private fun tel(locked: Boolean = false, speed: Float = 0f) = Telemetry(
        receivedAtMs = 1_000, locked = locked, speedKmh = speed, batteryPercent = 82, headlightOn = true, dualMotor = false,
        speedModeRaw = 1, cruiseOn = false, zeroStartOn = true, speedLimitKmh = 25, totalKm = 1248.4f,
    )

    private fun conn(t: Telemetry, pending: Map<Control, PendingOp> = emptyMap(), outcomes: Map<Control, Outcome> = emptyMap()) =
        mapper.map(HomeState(ConnectionStatus.Connected, t, 1_000, pending, outcomes))

    /** nome do FigCraft → estado de UI. */
    private val states: List<Pair<String, HomeUiState>> = listOf(
        "Home-Bloqueado" to conn(tel(locked = true)),
        "Home-Desbloqueado" to conn(tel()),
        "Home-Enviando-Lock" to conn(tel(), pending = mapOf(Control.Lock to PendingOp(ScooterCommand.SetLocked(true), 900))),
        "Home-Confirmado" to conn(tel(locked = true), outcomes = mapOf(Control.Lock to Outcome.Confirmed(1_000))),
        "Home-Falha" to conn(tel(), outcomes = mapOf(Control.Lock to Outcome.TimedOut(1_000))),
        "Home-Em-Movimento" to conn(tel(speed = 15f)),
        "Home-Conectando" to mapper.map(HomeState(connection = ConnectionStatus.Connecting)),
        "Home-Desconectado" to mapper.map(HomeState(ConnectionStatus.Disconnected, tel(), 1_000)),
    )

    private var current by mutableStateOf(states[0].second)
    private var dark by mutableStateOf(false)
    private var fontScale by mutableStateOf(1f)

    private fun readPng(f: File): Bitmap = requireNotNull(BitmapFactory.decodeFile(f.path)) { "PNG ausente: ${f.path}" }

    private fun luma(c: Int): Double = 0.299 * (c shr 16 and 255) + 0.587 * (c shr 8 and 255) + 0.114 * (c and 255)

    /** Diferença média de luma (0..1) em blocos de 8×8 px. */
    private fun mad(a: Bitmap, b: Bitmap): Double {
        var sum = 0.0; var n = 0
        for (by in 0 until a.height / 8) for (bx in 0 until a.width / 8) {
            var la = 0.0; var lb = 0.0
            for (y in 0 until 8) for (x in 0 until 8) { la += luma(a.getPixel(bx * 8 + x, by * 8 + y)); lb += luma(b.getPixel(bx * 8 + x, by * 8 + y)) }
            sum += abs(la - lb) / 64.0 / 255.0; n++
        }
        return sum / n
    }

    private fun near(expected: Int, actual: Int, tol: Int = 3) = listOf(16, 8, 0).all { abs((expected shr it and 255) - (actual shr it and 255)) <= tol }
    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    private fun capture(): Bitmap = rule.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)

    private fun save(bmp: Bitmap, theme: String, name: String) {
        File(out, theme).mkdirs()
        File(out, "$theme/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `Home em todos os estados claro e escuro bate com cores do FigCraft`() {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                MeuPatineteTheme(darkTheme = dark) { HomeScreen(current, HomeActions()) }
            }
        }
        for (isDark in listOf(false, true)) {
            val theme = if (isDark) "escuro" else "claro"
            for ((name, ui) in states) {
                rule.runOnIdle { current = ui; dark = isDark }
                rule.waitForIdle()
                val shot = capture().also { save(it, theme, name) }
                val ref = readPng(File(design, "$theme/$name.png"))
                assertEquals("$theme/$name largura", ref.width, shot.width)
                assertEquals("$theme/$name altura", ref.height, shot.height)
                assertTrue("$theme/$name fundo ${hex(shot.getPixel(5, 5))} ≠ ${hex(ref.getPixel(5, 5))}", near(ref.getPixel(5, 5), shot.getPixel(5, 5)))
                assertTrue("$theme/$name botão ${hex(shot.getPixel(60, 1440))} ≠ ${hex(ref.getPixel(60, 1440))}", near(ref.getPixel(60, 1440), shot.getPixel(60, 1440)))
                val m = mad(shot, ref)
                report.append("$theme,$name,fonte1.0,${"%.4f".format(m)}\n")
                assertTrue("$theme/$name diferença média $m acima do teto de regressão", m < MAD_CEILING)
            }
        }
        File(out, "report.csv").writeText(report.toString())
    }

    @Test fun `variantes de fonte 1_5 e 2_0 renderizam sem perder o botao`() {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                MeuPatineteTheme(darkTheme = dark) { HomeScreen(current, HomeActions()) }
            }
        }
        for (isDark in listOf(false, true)) for (scale in listOf(1.5f, 2.0f)) {
            rule.runOnIdle { current = states[1].second; dark = isDark; fontScale = scale }
            rule.waitForIdle()
            val theme = if (isDark) "escuro" else "claro"
            val shot = capture().also { save(it, theme, "Home-Desbloqueado-fonte${scale}") }
            // botão fixo: cor do botão presente no rodapé mesmo com fonte grande
            val ref = readPng(File(design, "$theme/Home-Desbloqueado.png"))
            assertTrue("$theme fonte $scale: botão ausente no rodapé", near(ref.getPixel(60, 1440), shot.getPixel(60, 1440)))
        }
    }

    private companion object { const val MAD_CEILING = 0.12 }
}
