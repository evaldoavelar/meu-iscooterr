package br.dev.evaldo.meuscooter.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
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
import br.dev.evaldo.meuscooter.data.SampleTrips
import br.dev.evaldo.meuscooter.domain.OnboardingFlow
import br.dev.evaldo.meuscooter.domain.Preferences
import br.dev.evaldo.meuscooter.domain.RecoveryReason
import br.dev.evaldo.meuscooter.domain.Trip
import br.dev.evaldo.meuscooter.domain.TripSummary
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.ui.history.HistoryScreen
import br.dev.evaldo.meuscooter.ui.history.TripDetailScreen
import br.dev.evaldo.meuscooter.ui.onboarding.OnboardingActions
import br.dev.evaldo.meuscooter.ui.onboarding.OnboardingScreen
import br.dev.evaldo.meuscooter.ui.recovery.RecoveryScreen
import br.dev.evaldo.meuscooter.ui.settings.SettingsActions
import br.dev.evaldo.meuscooter.ui.settings.SettingsScreen
import br.dev.evaldo.meuscooter.ui.telemetry.TelemetryScreen
import br.dev.evaldo.meuscooter.ui.theme.MeuPatineteTheme
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Seam S5 (telas novas da Sprint 5): screenshots reais do Compose contra `design/out/{claro,escuro}`.
 * Mesma métrica do [HomeScreenshotTest] (diferença média de luma em blocos 8×8, 0..1); teto POR FRAME = observado + margem e altura da captura conferida.
 * Frames rolantes do FigCraft (Telemetria, 720×1800) são cortados em 720×1560 (trecho visível no 360×780 dp).
 * Saída: `build/screenshots/{claro,escuro}/<Frame>.png` e `build/screenshots/report-telas.csv`. As diferenças medidas
 * (inclusive as grandes) estão descritas em `docs/sprints/sprint5-entrega.md`; nada é escondido por limiar.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class ScreensScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val out = File("build/screenshots").also { it.mkdirs() }
    private val design = File("../../design/out")

    private class Frame(val name: String, val content: @Composable () -> Unit)

    private val zone = ZoneOffset.UTC
    private fun at(d: Int, m: Int, h: Int, min: Int) = ZonedDateTime.of(2026, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(3, 11, 20, 0)

    // Mesmas 5 viagens de design/history.py (TRIPS); trajetos de SampleTrips (sementes 11, 23, 37, 41, 59).
    private val routes = SampleTrips.build(now).map { it.route }
    private val trips = listOf(
        Trip(1, at(3, 11, 18, 42), 4.8f, 21 * 60_000L, 14f, 27f, 24f, 82, 76, routes[0]),
        Trip(2, at(3, 11, 8, 15), 3.1f, 14 * 60_000L, 13f, 25f, 22f, 90, 86, routes[1]),
        Trip(3, at(2, 11, 19, 3), 7.9f, 32 * 60_000L, 15f, 31f, 27f, 100, 90, routes[2]),
        Trip(4, at(31, 10, 18, 20), 5.2f, 24 * 60_000L, 13f, 26f, 23f, 78, 72, routes[3]),
        Trip(5, at(29, 10, 10, 5), 12.6f, 48 * 60_000L, 16f, 34f, 28f, 95, 79, routes[4]),
    )
    private val history = HistoryUiMapper(zone)

    private val stopped = Telemetry(receivedAtMs = 1, speedKmh = 0f, batteryPercent = 82, totalKm = 1248.4f, voltageV = 55.4f, currentA = 0f, motor1TempC = 22, motor2TempC = 22)
    private val riding = Telemetry(receivedAtMs = 1, speedKmh = 18f, batteryPercent = 82, totalKm = 1248.4f, voltageV = 54.9f, currentA = 8.4f, motor1TempC = 41, motor2TempC = 39)
    private val tripNow = TripSummary(0f, 760_000, 16.2f, 27f)
    private val tele = TelemetryUiMapper()

    private val searching = OnboardingFlow()
    private val found = searching.scooterFound()
    private val connected = found.confirm().connectionEstablished()

    private fun telemetry(t: Telemetry, trip: TripSummary?) = @Composable { TelemetryScreen(tele.map(t, trip), {}, {}, {}) }
    private fun onboarding(f: OnboardingFlow) = @Composable { OnboardingScreen(f, "iX7 Pro", OnboardingActions()) }
    private fun recovery(r: RecoveryReason) = @Composable { RecoveryScreen(RecoveryUiMapper.map(r), onPrimary = {}) }

    private val frames: List<Frame> = listOf(
        Frame("Telemetria-Parado", telemetry(stopped, null)),
        Frame("Telemetria-Andando", telemetry(riding, tripNow)),
        Frame("Telemetria-1-Motor", telemetry(riding.copy(motor2TempC = null), tripNow)),
        Frame("Historico", { HistoryScreen(history.map(trips, now), {}, {}, routeOf = { id -> trips.first { it.id == id }.route }) }),
        Frame("Historico-Vazio", { HistoryScreen(history.map(emptyList(), now), {}, {}) }),
    ) + trips.mapIndexed { i, t -> Frame("Viagem-${i + 1}", { TripDetailScreen(history.detail(t, now), t.route, {}) }) } + listOf(
        Frame("Configuracoes", { SettingsScreen(Preferences(), "iX7 Pro", SettingsActions()) }),
        Frame("PrimeiroUso-1", onboarding(searching)),
        Frame("PrimeiroUso-2", onboarding(found)),
        Frame("PrimeiroUso-3", onboarding(connected)),
        Frame("Recuperacao-Desligado", recovery(RecoveryReason.OutOfRange)),
        Frame("Recuperacao-Bluetooth", recovery(RecoveryReason.BluetoothOff)),
        Frame("Recuperacao-Permissao", recovery(RecoveryReason.PermissionDenied)),
        Frame("Recuperacao-Reconectando", recovery(RecoveryReason.Reconnecting(2))),
    )

    private var current by mutableStateOf(frames[0])
    private var dark by mutableStateOf(false)

    private fun luma(c: Int): Double = 0.299 * (c shr 16 and 255) + 0.587 * (c shr 8 and 255) + 0.114 * (c and 255)

    /** Diferença média de luma (0..1) em blocos 8×8, só no trecho comum (largura e altura mínimas). */
    private fun mad(a: Bitmap, b: Bitmap): Double {
        val w = minOf(a.width, b.width); val h = minOf(a.height, b.height)
        var sum = 0.0; var n = 0
        for (by in 0 until h / 8) for (bx in 0 until w / 8) {
            var la = 0.0; var lb = 0.0
            for (y in 0 until 8) for (x in 0 until 8) { la += luma(a.getPixel(bx * 8 + x, by * 8 + y)); lb += luma(b.getPixel(bx * 8 + x, by * 8 + y)) }
            sum += abs(la - lb) / 64.0 / 255.0; n++
        }
        return sum / n
    }

    private fun near(expected: Int, actual: Int, tol: Int = 3) = listOf(16, 8, 0).all { abs((expected shr it and 255) - (actual shr it and 255)) <= tol }
    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    @Test fun `telas novas claro e escuro batem com o FigCraft dentro do teto de regressao`() {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1f)) {
                MeuPatineteTheme(darkTheme = dark) { current.content() }
            }
        }
        val report = StringBuilder("tema,frame,fundo_figcraft,fundo_app,mad_luma\n")
        val failures = mutableListOf<String>()
        for (isDark in listOf(false, true)) {
            val theme = if (isDark) "escuro" else "claro"
            File(out, theme).mkdirs()
            for (frame in frames) {
                rule.runOnIdle { current = frame; dark = isDark }
                rule.waitForIdle()
                val shot = rule.onRoot().captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
                File(out, "$theme/${frame.name}.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val ref = requireNotNull(BitmapFactory.decodeFile(File(design, "$theme/${frame.name}.png").path)) { "PNG ausente: $theme/${frame.name}" }
                val m = mad(shot, ref)
                report.append("$theme,${frame.name},${hex(ref.getPixel(5, 5))},${hex(shot.getPixel(5, 5))},${"%.4f".format(m)}\n")
                if (shot.width != ref.width) failures += "$theme/${frame.name} largura ${shot.width} ≠ ${ref.width}"
                if (!near(ref.getPixel(5, 5), shot.getPixel(5, 5))) failures += "$theme/${frame.name} fundo ${hex(shot.getPixel(5, 5))} ≠ ${hex(ref.getPixel(5, 5))}"
                val ceiling = requireNotNull(BASELINE[frame.name]) { "sem linha de base para ${frame.name}" } + MARGIN
                if (m >= ceiling) failures += "$theme/${frame.name} diferença média $m acima do teto $ceiling (observado + $MARGIN)"
                val expectedH = minOf(ref.height, VISIBLE_HEIGHT_PX)   // frames rolantes do FigCraft são cortados na altura visível
                if (shot.height != expectedH) failures += "$theme/${frame.name} altura ${shot.height} ≠ $expectedH"
            }
        }
        File(out, "report-telas.csv").writeText(report.toString())
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private companion object {
        /** Maior MAD (claro/escuro) observado por frame em 2026-11-03 (`build/screenshots/report-telas.csv`, sessão 11). */
        val BASELINE = mapOf(
        "Telemetria-Parado" to 0.0468,
        "Telemetria-Andando" to 0.0621,
        "Telemetria-1-Motor" to 0.0533,
        "Historico" to 0.0381,
        "Historico-Vazio" to 0.0171,
        "Viagem-1" to 0.0485,
        "Viagem-2" to 0.0485,
        "Viagem-3" to 0.0493,
        "Viagem-4" to 0.0495,
        "Viagem-5" to 0.0497,
        "Configuracoes" to 0.0622, // S34 (ADR 0011): linha "Bloquear ao desconectar" com texto novo + selo; o FigCraft ainda mostra a linha antiga (observado 0.0622 claro, 0.0577 escuro)
        "PrimeiroUso-1" to 0.0494,
        "PrimeiroUso-2" to 0.0528,
        "PrimeiroUso-3" to 0.0495,
        "Recuperacao-Desligado" to 0.0445,
        "Recuperacao-Bluetooth" to 0.0419,
        "Recuperacao-Permissao" to 0.0497,
        "Recuperacao-Reconectando" to 0.0247,
        )
        const val MARGIN = 0.015          // folga sobre o observado: regressão visual maior que isso falha o build
        const val VISIBLE_HEIGHT_PX = 1560   // 780 dp × 2 (xhdpi)
    }
}
