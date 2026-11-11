package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripModeTest {

    private fun telemetry(
        atMs: Long,
        speedModeRaw: Int? = 1,
        dualMotor: Boolean? = false,
        speedKmh: Float? = 20f,
    ) = Telemetry(
        receivedAtMs = atMs,
        speedKmh = speedKmh,
        speedModeRaw = speedModeRaw,
        dualMotor = dualMotor,
    )

    @Test
    fun `troca Walking para Eco para Sport para Eco calcula segmentos e tempos com valores a mao`() {
        val events = listOf(
            TripModeEvent(elapsedMs = 0L, mode = 0, dualMotor = false),       // Walking
            TripModeEvent(elapsedMs = 4_000L, mode = 1, dualMotor = false),   // Eco
            TripModeEvent(elapsedMs = 10_000L, mode = 3, dualMotor = true),   // Sport
            TripModeEvent(elapsedMs = 15_000L, mode = 1, dualMotor = true),   // Eco
        )
        val totalDurationMs = 20_000L
        val route = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.001),
            GeoPoint(0.0, 0.002),
            GeoPoint(0.0, 0.003),
            GeoPoint(0.0, 0.004),
        )

        val analysis = analyzeTripModes(route, events, totalDurationMs)

        // 1. Tempos em cada modo
        assertEquals(3, analysis.modeTimes.size)
        val walking = analysis.modeTimes.first { it.mode == 0 }
        val eco = analysis.modeTimes.first { it.mode == 1 }
        val sport = analysis.modeTimes.first { it.mode == 3 }

        assertEquals(4_000L, walking.durationMs)
        assertEquals(20, walking.percent)

        assertEquals(11_000L, eco.durationMs) // 6_000L + 5_000L
        assertEquals(55, eco.percent)

        assertEquals(5_000L, sport.durationMs)
        assertEquals(25, sport.percent)

        // 2. Segmentos de modo
        assertEquals(4, analysis.modeSegments.size)
        assertEquals(0, analysis.modeSegments[0].mode)
        assertEquals(4_000L, analysis.modeSegments[0].durationMs)
        assertEquals(0L, analysis.modeSegments[0].startElapsedMs)
        assertEquals(4_000L, analysis.modeSegments[0].endElapsedMs)

        assertEquals(1, analysis.modeSegments[1].mode)
        assertEquals(6_000L, analysis.modeSegments[1].durationMs)
        assertEquals(4_000L, analysis.modeSegments[1].startElapsedMs)
        assertEquals(10_000L, analysis.modeSegments[1].endElapsedMs)

        assertEquals(3, analysis.modeSegments[2].mode)
        assertEquals(5_000L, analysis.modeSegments[2].durationMs)
        assertEquals(10_000L, analysis.modeSegments[2].startElapsedMs)
        assertEquals(15_000L, analysis.modeSegments[2].endElapsedMs)

        assertEquals(1, analysis.modeSegments[3].mode)
        assertEquals(5_000L, analysis.modeSegments[3].durationMs)
        assertEquals(15_000L, analysis.modeSegments[3].startElapsedMs)
        assertEquals(20_000L, analysis.modeSegments[3].endElapsedMs)

        // 3. Segmentos de motor (1 motor 0..10_000ms, 2 motores 10_000..20_000ms)
        assertEquals(2, analysis.motorSegments.size)
        assertEquals(false, analysis.motorSegments[0].dualMotor)
        assertEquals(10_000L, analysis.motorSegments[0].durationMs)
        assertEquals(0L, analysis.motorSegments[0].startElapsedMs)
        assertEquals(10_000L, analysis.motorSegments[0].endElapsedMs)

        assertEquals(true, analysis.motorSegments[1].dualMotor)
        assertEquals(10_000L, analysis.motorSegments[1].durationMs)
        assertEquals(10_000L, analysis.motorSegments[1].startElapsedMs)
        assertEquals(20_000L, analysis.motorSegments[1].endElapsedMs)
    }

    @Test
    fun `viagem com modo unico gera segmento unico e 100 porcento do tempo`() {
        val events = listOf(
            TripModeEvent(elapsedMs = 0L, mode = 1, dualMotor = false), // Eco
        )
        val totalDurationMs = 15_000L
        val route = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.002))

        val analysis = analyzeTripModes(route, events, totalDurationMs)

        assertEquals(1, analysis.modeTimes.size)
        assertEquals(1, analysis.modeTimes[0].mode)
        assertEquals(15_000L, analysis.modeTimes[0].durationMs)
        assertEquals(100, analysis.modeTimes[0].percent)

        assertEquals(1, analysis.modeSegments.size)
        assertEquals(1, analysis.modeSegments[0].mode)
        assertEquals(15_000L, analysis.modeSegments[0].durationMs)
        assertEquals(2, analysis.modeSegments[0].points.size)

        assertEquals(1, analysis.motorSegments.size)
        assertEquals(false, analysis.motorSegments[0].dualMotor)
        assertEquals(15_000L, analysis.motorSegments[0].durationMs)
    }

    @Test
    fun `sem eventos devolve analise vazia`() {
        val analysis = analyzeTripModes(emptyList(), emptyList(), 10_000L)
        assertTrue(analysis.modeSegments.isEmpty())
        assertTrue(analysis.motorSegments.isEmpty())
        assertTrue(analysis.modeTimes.isEmpty())
    }

    @Test
    fun `TripSession grava eventos durante Running e anexa na Trip ao parar`() {
        val session = TripSession()
        session.onTelemetry(telemetry(atMs = 1_000L, speedModeRaw = 0, dualMotor = false)) // inicial: Walking
        session.onTelemetry(telemetry(atMs = 5_000L, speedModeRaw = 1, dualMotor = false)) // Eco aos 4s
        session.onTelemetry(telemetry(atMs = 11_000L, speedModeRaw = 3, dualMotor = true)) // Sport aos 10s
        session.onTelemetry(telemetry(atMs = 16_000L, speedModeRaw = 1, dualMotor = true)) // Eco aos 15s
        session.onTelemetry(telemetry(atMs = 21_000L, speedModeRaw = 1, dualMotor = true)) // final aos 20s

        val trip = session.stop()!!
        assertEquals(20_000L, trip.durationMs)
        assertEquals(4, trip.modeEvents.size)

        assertEquals(TripModeEvent(0L, 0, false), trip.modeEvents[0])
        assertEquals(TripModeEvent(4_000L, 1, false), trip.modeEvents[1])
        assertEquals(TripModeEvent(10_000L, 3, true), trip.modeEvents[2])
        assertEquals(TripModeEvent(15_000L, 1, true), trip.modeEvents[3])
    }

    @Test
    fun `pausa nao grava eventos nem dilui o tempo ativo`() {
        val session = TripSession()
        session.onTelemetry(telemetry(atMs = 1_000L, speedModeRaw = 0, dualMotor = false)) // Walking
        session.onTelemetry(telemetry(atMs = 6_000L, speedModeRaw = 0, dualMotor = false)) // 5s ativos

        session.pause()
        // Amostras durante a pausa chegam mas são ignoradas
        session.onTelemetry(telemetry(atMs = 8_000L, speedModeRaw = 2, dualMotor = true))
        session.onTelemetry(telemetry(atMs = 10_000L, speedModeRaw = 2, dualMotor = true))

        session.play()
        // Ao retomar, primeiro quadro reancora com novo modo (Eco)
        session.onTelemetry(telemetry(atMs = 12_000L, speedModeRaw = 1, dualMotor = false))
        session.onTelemetry(telemetry(atMs = 17_000L, speedModeRaw = 1, dualMotor = false)) // +5s ativos

        val trip = session.stop()!!
        assertEquals(10_000L, trip.durationMs) // 5s Walking + 5s Eco (pausa descontada)
        assertEquals(2, trip.modeEvents.size)
        assertEquals(TripModeEvent(0L, 0, false), trip.modeEvents[0])
        assertEquals(TripModeEvent(5_000L, 1, false), trip.modeEvents[1])

        val analysis = analyzeTripModes(emptyList(), trip.modeEvents, trip.durationMs)
        val walking = analysis.modeTimes.first { it.mode == 0 }
        val eco = analysis.modeTimes.first { it.mode == 1 }
        assertEquals(5_000L, walking.durationMs)
        assertEquals(50, walking.percent)
        assertEquals(5_000L, eco.durationMs)
        assertEquals(50, eco.percent)
    }

    @Test
    fun `Meter1 velho e ignorado e nao gera transicao de modo`() {
        val session = TripSession()
        session.onTelemetry(telemetry(atMs = 1_000L, speedModeRaw = 1, dualMotor = false), meter1Fresh = true) // Eco
        session.onTelemetry(telemetry(atMs = 5_000L, speedModeRaw = 1, dualMotor = false), meter1Fresh = true)

        // Quadro com Meter1 velho / obsoleto tentando mudar para Sport: deve ser ignorado
        session.onTelemetry(telemetry(atMs = 8_000L, speedModeRaw = 3, dualMotor = true), meter1Fresh = false)

        session.onTelemetry(telemetry(atMs = 11_000L, speedModeRaw = 1, dualMotor = false), meter1Fresh = true)

        val trip = session.stop()!!
        assertEquals(1, trip.modeEvents.size)
        assertEquals(TripModeEvent(0L, 1, false), trip.modeEvents[0])
    }
}
