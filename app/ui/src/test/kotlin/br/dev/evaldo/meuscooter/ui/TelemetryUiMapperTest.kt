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

    /** TASK-S15-A item 2: only the battery belongs to the stale Meter1 fixture. */
    @Test fun staleBatteryIsUnknownWhileOtherReadingsKeepTheirFormatting() {
        val ui = mapper.map(moving, trip, batteryStale = true)
        assertEquals("--", ui.batteryText)
        assertEquals("18", ui.speedText)
        assertEquals("km/h", ui.speedUnit)
        assertEquals("461 W", ui.powerText)
        assertEquals("25,6 Wh/km", ui.efficiencyText)
        assertEquals("8,4", ui.currentText)
        assertEquals("54,9", ui.voltageText)
        assertEquals("41", ui.motor1Text)
        assertEquals("39", ui.motor2Text)
        assertEquals("1.248 km", ui.totalOdometerText)
        assertEquals("2,50 km", ui.tripOdometerText)
        assertEquals("12:40", ui.durationText)
        assertEquals(null, ui.staleNote)
        assertEquals("82%", mapper.map(moving, trip, batteryStale = false).batteryText)
    }

    @Test fun missingTelemetryFieldsStayUnknownRegardlessOfBatteryAge() {
        for (reading in listOf(null, Telemetry(receivedAtMs = 6_000))) {
            for (batteryStale in listOf(false, true)) {
                val ui = mapper.map(reading, null, batteryStale = batteryStale)
                assertEquals("--", ui.batteryText)
                assertEquals("--", ui.speedText)
                assertEquals("--", ui.powerText)
                assertEquals("--", ui.efficiencyText)
                assertEquals("--", ui.currentText)
                assertEquals("--", ui.voltageText)
                assertEquals("--", ui.motor1Text)
                assertEquals("--", ui.motor2Text)
                assertEquals("--", ui.totalOdometerText)
                assertEquals(0f, ui.gaugeFraction, 0f)
                assertEquals("0,00 km", ui.tripOdometerText)
                assertEquals("00:00", ui.durationText)
            }
        }
    }

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
        assertEquals("", ui.efficiencyText)
    }

    /** Codex r1 #4: andando a 18 km/h, tensão ou corrente ausente é leitura indisponível, não "parado". */
    @Test fun `sem tensao ou corrente o consumo e indisponivel e nao parado`() {
        assertEquals("--", mapper.map(moving.copy(currentA = null), trip).efficiencyText)
        assertEquals("--", mapper.map(moving.copy(voltageV = null), trip).efficiencyText)
        assertEquals("--", mapper.map(moving.copy(speedKmh = null), trip).efficiencyText)
    }

    /** A2. Perfil [10, 12, 11, 20] m: subida acumulada = (12−10) + (20−11) = 11 m; barras normalizadas (v−mín)/(máx−mín) = [0, 0,2, 0,1, 1]. */
    @Test fun `altimetria com dados vira subida acumulada e barras normalizadas`() {
        val ui = mapper.map(moving, trip, altitudesM = listOf(10f, 12f, 11f, 20f))
        assertEquals("+11 m", ui.altitudeText)
        assertEquals(listOf(0f, 0.2f, 0.1f, 1f), ui.altitudeBars)
    }

    @Test fun `altimetria sem dados nao inventa leitura`() {
        val ui = mapper.map(moving, trip)
        assertEquals("Sem leitura", ui.altitudeText)
        assertEquals(emptyList<Float>(), ui.altitudeBars)
    }

    @Test fun `altimetria plana vira barras no piso`() {
        assertEquals(listOf(0f, 0f), mapper.map(moving, trip, altitudesM = listOf(5f, 5f)).altitudeBars)
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

    /** 18 km/h = 11,2 mph; 2,5 km = 1,55 mi; 16,2 km/h = 10,07 mph; 27 km/h = 16,78 mph; 25,62 Wh/km = 41,2 Wh/mi; fundo de escala 60 km/h = 37 mph. */
    @Test fun `unidades imperiais`() {
        val ui = mapper.map(moving, trip, br.dev.evaldo.meuscooter.domain.Units.Imperial)
        assertEquals("11", ui.speedText); assertEquals("mph", ui.speedUnit); assertEquals("37", ui.gaugeMaxText)
        assertEquals(0.3f, ui.gaugeFraction, 0.001f)
        assertEquals("1,55 mi", ui.tripOdometerText)
        assertEquals("776 mi", ui.totalOdometerText)
        assertEquals("10 mph", ui.avgText); assertEquals("17 mph", ui.maxText)
        assertEquals("41,2 Wh/mi", ui.efficiencyText)
    }

    /** Ganho de altitude 11 m = 36 ft. */
    @Test fun `altimetria em pes no imperial`() {
        assertEquals("+36 ft", mapper.map(moving, trip, br.dev.evaldo.meuscooter.domain.Units.Imperial, altitudesM = listOf(10f, 12f, 11f, 20f)).altitudeText)
    }

    @Test fun `metrico expoe rotulo e fundo de escala`() {
        val ui = mapper.map(moving, trip)
        assertEquals("km/h", ui.speedUnit); assertEquals("60", ui.gaugeMaxText)
    }

    @Test fun `telemetria velha vira traco e avisa desatualizado, mantendo a viagem`() {
        val ui = mapper.map(moving, trip, stale = true)
        assertEquals("--", ui.speedText); assertEquals("--", ui.batteryText); assertEquals("--", ui.powerText)
        assertEquals("--", ui.currentText); assertEquals("--", ui.voltageText); assertEquals("--", ui.motor1Text)
        assertEquals("--", ui.totalOdometerText)
        assertEquals(0f, ui.gaugeFraction, 0.001f)
        assertEquals("Dados desatualizados", ui.staleNote)
        assertEquals("12:40", ui.durationText)
    }

    @Test fun `telemetria atual nao tem aviso de desatualizado`() {
        assertEquals(null, mapper.map(moving, trip).staleNote)
        assertEquals(null, mapper.map(null, null).staleNote)
    }

    @Test fun `duracao de 60 min ou mais usa h mm ss e abaixo disso mm ss`() {
        assertEquals("59:59", mapper.map(moving, trip.copy(durationMs = 3_599_000)).durationText)
        assertEquals("1:00:00", mapper.map(moving, trip.copy(durationMs = 3_600_000)).durationText)
        assertEquals("1:05:30", mapper.map(moving, trip.copy(durationMs = 3_930_000)).durationText)
        assertEquals("12:03:09", mapper.map(moving, trip.copy(durationMs = 43_389_000)).durationText)
    }

    /** Meter2 velho: potência/corrente/tensão/temp. do motor/odômetro viram "--"; bateria (Meter1) e viagem ficam. */
    @Test fun staleMeter2ShowsUnknownForItsFieldsOnly() {
        val ui = mapper.map(moving, trip, meter2Stale = true)
        assertEquals("--", ui.powerText)
        assertEquals("--", ui.currentText)
        assertEquals("--", ui.voltageText)
        assertEquals("--", ui.motor1Text)
        assertEquals("--", ui.totalOdometerText)
        assertEquals("82%", ui.batteryText)
        assertEquals("2,50 km", ui.tripOdometerText)
    }

    /** Potência real do Meter2 (dado[0..1]) tem prioridade sobre V x I; temperatura da bateria não existe: motor 2 segue "Sem leitura". */
    @Test fun meter2PowerIsShownAsDecoded() {
        val ui = mapper.map(Telemetry(receivedAtMs = 1, powerW = 87.2f, voltageV = 54.5f, currentA = 1.6f, totalKm = 24f, motor1TempC = 25), null)
        assertEquals("87 W", ui.powerText)
        assertEquals("1,6", ui.currentText)
        assertEquals("54,5", ui.voltageText)
        assertEquals("25", ui.motor1Text)
        assertEquals("24 km", ui.totalOdometerText)
        assertEquals("--", ui.motor2Text)
    }

    /** S35: rótulo do estado da gravação ao lado da Duração (mockup: "Contando" verde / "Pausada" laranja). */
    @Test fun recordingStateLabels() {
        assertEquals("Contando", mapper.map(moving, trip).recStateText)
        assertEquals("Contando", mapper.map(moving, trip, recState = br.dev.evaldo.meuscooter.domain.TripRecState.Running).recStateText)
        assertEquals("Pausada", mapper.map(moving, trip, recState = br.dev.evaldo.meuscooter.domain.TripRecState.Paused).recStateText)
        val stopped = mapper.map(moving, null, recState = br.dev.evaldo.meuscooter.domain.TripRecState.Stopped)
        assertEquals("Parada", stopped.recStateText)
        assertEquals("00:00", stopped.durationText)
    }
}
