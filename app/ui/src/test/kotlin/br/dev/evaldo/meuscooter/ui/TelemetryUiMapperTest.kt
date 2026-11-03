package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.TripSummary
import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Esperado vindo de design/telemetry.py (estado "Telemetria-Andando": 18 km/h, 8,4 A, 54,9 V, 41/39 °C, viagem 16/27 km/h 12:40). */
class TelemetryUiMapperTest {
    private val mapper = TelemetryUiMapper()

    private val moving = Telemetry(
        receivedAtMs = 1, speedKmh = 18f, batteryPercent = 82, totalKm = 1248.4f,
        voltageV = 54.9f, currentA = 8.4f, motor1TempC = 41, motor2TempC = 39,
    )
    private val trip = TripSummary(distanceKm = 2.5f, durationMs = 760_000, avgKmh = 16.2f, maxKmh = 27f)

    @Test fun `andando mostra velocidade, bateria e fracao do arco 0 a 60`() {
        val ui = mapper.map(moving, trip)
        assertEquals("18", ui.speedText)
        assertEquals(0.3f, ui.gaugeFraction, 0.001f)
        assertEquals("82%", ui.batteryText)
    }

    @Test fun `velocidade acima de 60 limita o arco em 1`() {
        assertEquals(1f, mapper.map(moving.copy(speedKmh = 75f), null).gaugeFraction, 0.001f)
    }

    @Test fun `consumo, corrente, tensao e temperaturas`() {
        val ui = mapper.map(moving, trip)
        assertEquals("461 W", ui.powerText)
        assertEquals("25,6 Wh/km", ui.efficiencyText)
        assertEquals("8,4", ui.currentText)
        assertEquals("54,9", ui.voltageText)
        assertEquals("41", ui.motor1Text)
        assertEquals("39", ui.motor2Text)
        assertEquals(null, ui.motor2Note)
    }

    @Test fun `parado nao mostra consumo por km`() {
        val ui = mapper.map(moving.copy(speedKmh = 0f, currentA = 0f, voltageV = 55.4f), null)
        assertEquals("0 W", ui.powerText)
        assertEquals("Parado · sem consumo", ui.efficiencyText)
    }

    @Test fun `motor 2 sem leitura mostra traco e nota`() {
        val ui = mapper.map(moving.copy(motor2TempC = null), trip)
        assertEquals("--", ui.motor2Text)
        assertEquals("Sem leitura", ui.motor2Note)
    }

    @Test fun `odometros e resumo da viagem`() {
        val ui = mapper.map(moving, trip)
        assertEquals("2,50 km", ui.tripOdometerText)
        assertEquals("1.248 km", ui.totalOdometerText)
        assertEquals("16 km/h", ui.avgText)
        assertEquals("27 km/h", ui.maxText)
        assertEquals("12:40", ui.durationText)
        assertEquals("Sem leitura", ui.altitudeText)
    }

    @Test fun `sem telemetria nem viagem tudo vira traco ou zero`() {
        val ui = mapper.map(null, null)
        assertEquals("--", ui.speedText)
        assertEquals(0f, ui.gaugeFraction, 0.001f)
        assertEquals("--", ui.powerText)
        assertEquals("0,00 km", ui.tripOdometerText)
        assertEquals("0 km/h", ui.avgText)
        assertEquals("00:00", ui.durationText)
    }
}
