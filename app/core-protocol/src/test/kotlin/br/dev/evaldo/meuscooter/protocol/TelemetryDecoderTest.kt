package br.dev.evaldo.meuscooter.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Esperados à mão a partir de `S2-captura-resultados.md`: Meter1 dado[1] bit5 = lock_status, dado[3] = bateria, dado[7] = limite. */
class TelemetryDecoderTest {
    private fun frame(hex: String): RxFrame =
        (RxFrameParser.parse(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) as RxParseResult.Frame).frame

    private val meter1Unlocked = "1aa120031200000064000000320000000023232302030853f81ff1"
    private val meter1Locked = "1aa120031200200064000000320000000023232302030873e01ff1" // dado[1] = 0x20

    @Test fun meter1UnlockedYieldsOnlyConfirmedFields() {
        val t = TelemetryDecoder.decode(frame(meter1Unlocked), receivedAtMs = 1234)
        assertEquals(Telemetry(receivedAtMs = 1234, locked = false, batteryPercent = 100, speedLimitKmh = 50), t)
    }

    @Test fun meter1LockedBit5() {
        assertEquals(true, TelemetryDecoder.decode(frame(meter1Locked), 0)!!.locked)
    }

    @Test fun lockHasPasswordBitDoesNotMeanLocked() {
        // dado[1]=0x10 = bit4 lock_has_pwd (S2), bit5 limpo → não bloqueado. Quadro montado à mão (CRC irrelevante em RX).
        val f = RxFrame(0x20, 0x03, listOf(0, 0x10, 0, 0x64, 0, 0, 0, 0x32), crcMatches = false)
        assertEquals(false, TelemetryDecoder.decode(f, 0)!!.locked)
    }

    @Test fun meter2ConfirmsNoFieldsButIsRecognised() {
        val t = TelemetryDecoder.decode(frame("1aa12103120000ae1500000000170000000000160f03085d8b1ff1"), 77)
        assertEquals(Telemetry(receivedAtMs = 77), t)
    }

    @Test fun nonTelemetryFramesAreNotTelemetry() {
        assertNull(TelemetryDecoder.decode(frame("1aa130050101d5b31ff1"), 0)) // ACK do CONNECT
        assertNull(TelemetryDecoder.decode(frame("1aa16204021432531f1ff1"), 0)) // resposta tipo 04
        assertNull(TelemetryDecoder.decode(RxFrame(0x20, 0x05, List(18) { 0 }, false), 0)) // Meter1 com tipo errado
    }

    @Test fun shortMeter1PayloadIsIgnored() {
        assertNull(TelemetryDecoder.decode(RxFrame(0x20, 0x03, listOf(0, 0, 0, 0x64), false), 0))
    }

    @Test fun implausibleBatteryBecomesUnknown() {
        val f = RxFrame(0x20, 0x03, listOf(0, 0, 0, 101, 0, 0, 0, 0x32), false)
        val t = TelemetryDecoder.decode(f, 0)!!
        assertNull(t.batteryPercent)
        assertEquals(50, t.speedLimitKmh)
    }

    @Test fun overlayKeepsOlderFieldsAndTakesNewerOnes() {
        val m1 = TelemetryDecoder.decode(frame(meter1Locked), 100)!!
        val m2 = TelemetryDecoder.decode(frame("1aa12103120000ae1500000000170000000000160f03085d8b1ff1"), 400)!!
        val merged = m1.overlay(m2)
        assertEquals(Telemetry(receivedAtMs = 400, locked = true, batteryPercent = 100, speedLimitKmh = 50), merged)
        val m1b = TelemetryDecoder.decode(frame(meter1Unlocked), 700)!!
        assertEquals(false, merged.overlay(m1b).locked)
    }

    @Test fun overlayCoversEveryField() {
        val full = Telemetry(
            receivedAtMs = 5, locked = true, speedKmh = 1f, batteryPercent = 2, headlightOn = true, dualMotor = true,
            speedModeRaw = 3, cruiseOn = true, zeroStartOn = true, speedLimitKmh = 4, totalKm = 6f,
            voltageV = 7f, currentA = 8f, motor1TempC = 9, motor2TempC = 10,
        )
        assertEquals(full, Telemetry(receivedAtMs = 0).overlay(full))
        assertEquals(full.copy(receivedAtMs = 9), full.overlay(Telemetry(receivedAtMs = 9)))
    }
}
