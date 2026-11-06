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

    @Test fun s19StationaryFrameHasKnownZeroSpeed() {
        // S19 ix7tap-speed.txt:6, quadro completo preservado, inclusive CRC RX.
        val t = TelemetryDecoder.decode(frame("1aa120031200000064000000320000000023232302030853f81ff1"), 0)!!
        assertEquals(0f, t.speedKmh)
    }

    @Test fun s19WheelMotionDoesNotClaimAKmhScale() {
        // ix7tap-speed.txt:6 (parado), :561/:544 (acelerador), :950/:1063 (à mão).
        val frames = listOf(
            "1aa120031200000064000000320000000023232302030853f81ff1" to false,
            "1aa120031200000064c60000320000000023232302030858b11ff1" to true,
            "1aa1200312000000642d000032000000002323230203083f941ff1" to true,
            "1aa1200312000000642500003200000000232323020308de4b1ff1" to true,
            "1aa1200312000000640100003200000000232323020308ae3b1ff1" to true,
        )
        for ((hex, moving) in frames) {
            val t = TelemetryDecoder.decode(frame(hex), 0)!!
            assertEquals(hex, moving, t.wheelMoving)
            assertEquals(hex, if (moving) null else 0f, t.speedKmh)
        }
        // Guarda conservadora: bytes 10/11 não variaram na captura; não atribuir escala/significado.
        for (index in 5..6) {
            val stopped = frame(frames.first().first)
            val synthetic = stopped.copy(payload = stopped.payload.toMutableList().also { it[index] = 1 })
            val t = TelemetryDecoder.decode(synthetic, 0)!!
            assertEquals(true, t.wheelMoving)
            assertNull(t.speedKmh)
        }
    }

    @Test fun s19MotionReplacesStoppedSpeedAndMeter2PreservesIt() {
        val stopped = TelemetryDecoder.decode(frame(meter1Unlocked), 100)!! // S19:6
        val moving = TelemetryDecoder.decode(frame("1aa1200312000000640100003200000000232323020308ae3b1ff1"), 200)!! // S19:1063
        val meter2 = TelemetryDecoder.decode(frame("1aa12103120000ae1500000000170000000000160f03085d8b1ff1"), 300)!!
        val merged = stopped.overlay(moving).overlay(meter2)
        assertNull(merged.speedKmh)
        assertEquals(true, merged.wheelMoving)
        val aged = AgedTelemetry.empty().receive(stopped, FieldGroup.Meter1)
            .receive(moving, FieldGroup.Meter1).receive(meter2, FieldGroup.Meter2)
        assertNull(aged.telemetry.speedKmh)
        assertEquals(true, aged.telemetry.wheelMoving)
        assertEquals(200L, aged.meter1AtMs)
        val stoppedAgain = aged.receive(stopped.copy(receivedAtMs = 400), FieldGroup.Meter1)
        assertEquals(0f, stoppedAgain.telemetry.speedKmh)
        assertEquals(false, stoppedAgain.telemetry.wheelMoving)
    }

    @Test fun meter1UnlockedYieldsOnlyConfirmedFields() {
        val t = TelemetryDecoder.decode(frame(meter1Unlocked), receivedAtMs = 1234)
        assertEquals(Telemetry(receivedAtMs = 1234, locked = false, batteryPercent = 100, speedLimitKmh = 50, speedKmh = 0f, wheelMoving = false), t)
    }

    @Test fun meter1LockedBit5() {
        assertEquals(true, TelemetryDecoder.decode(frame(meter1Locked), 0)!!.locked)
    }

    @Test fun lockHasPasswordBitDoesNotMeanLocked() {
        // dado[1]=0x10 = bit4 lock_has_pwd (S2), bit5 limpo → não bloqueado. Quadro montado à mão (CRC irrelevante em RX).
        val f = RxFrame(0x20, 0x03, listOf(0, 0x10, 0, 0x64, 0, 0, 0, 0x32) + List(10) { 0 }, crcMatches = false)
        assertEquals(false, TelemetryDecoder.decode(f, 0)!!.locked)
    }

    @Test fun meter2IsRecognisedAndDecoded() {
        // payload 00 00 | ae 15 | 00 00 | 00 00 | 17 00 | ... | 16 ...: P 0 W, V 0x15ae = 5550 -> 55,50, I 0, odo 0x17 = 23, motor 0x16 = 22.
        val t = TelemetryDecoder.decode(frame("1aa12103120000ae1500000000170000000000160f03085d8b1ff1"), 77)!!
        assertEquals(77L, t.receivedAtMs)
        assertEquals(55.5f, t.voltageV!!, 0.001f)
        assertEquals(23f, t.totalKm!!, 0f)
        assertEquals(22, t.motor1TempC)
    }

    @Test fun nonTelemetryFramesAreNotTelemetry() {
        assertNull(TelemetryDecoder.decode(frame("1aa130050101d5b31ff1"), 0)) // ACK do CONNECT
        assertNull(TelemetryDecoder.decode(frame("1aa16204021432531f1ff1"), 0)) // resposta tipo 04
        assertNull(TelemetryDecoder.decode(RxFrame(0x20, 0x05, List(18) { 0 }, false), 0)) // Meter1 com tipo errado
    }

    @Test fun meter2WithEmptyPayloadIsNotTelemetry() {
        // Revisão Codex P4: cabeçalho/rodapé corretos mas LEN 0; S2 registra LEN 18 em Meter1/Meter2.
        assertNull(TelemetryDecoder.decode(frame("1aa121030000001ff1"), 0))
        assertNull(TelemetryDecoder.groupOf(frame("1aa121030000001ff1")))
    }

    @Test fun shortMeter1PayloadIsIgnored() {
        assertNull(TelemetryDecoder.decode(RxFrame(0x20, 0x03, listOf(0, 0, 0, 0x64), false), 0))
    }

    @Test fun implausibleBatteryBecomesUnknown() {
        val f = RxFrame(0x20, 0x03, listOf(0, 0, 0, 101, 0, 0, 0, 0x32) + List(10) { 0 }, false)
        val t = TelemetryDecoder.decode(f, 0)!!
        assertNull(t.batteryPercent)
        assertEquals(50, t.speedLimitKmh)
    }

    @Test fun overlayKeepsOlderFieldsAndTakesNewerOnes() {
        val m1 = TelemetryDecoder.decode(frame(meter1Locked), 100)!!
        val m2 = TelemetryDecoder.decode(frame("1aa12103120000ae1500000000170000000000160f03085d8b1ff1"), 400)!!
        val merged = m1.overlay(m2)
        assertEquals(Telemetry(receivedAtMs = 400, locked = true, batteryPercent = 100, speedLimitKmh = 50, speedKmh = 0f, wheelMoving = false), merged.copy(powerW = null, voltageV = null, currentA = null, totalKm = null, motor1TempC = null))
        // Meter2 (dado[8] = 0x17, dado[14] = 0x16): o overlay carrega os campos dele junto com os do Meter1.
        assertEquals(23f, merged.totalKm!!, 0f)
        assertEquals(22, merged.motor1TempC)
        val m1b = TelemetryDecoder.decode(frame(meter1Unlocked), 700)!!
        assertEquals(false, merged.overlay(m1b).locked)
    }

    @Test fun overlayCoversEveryField() {
        val full = Telemetry(
            receivedAtMs = 5, locked = true, speedKmh = 1f, batteryPercent = 2, headlightOn = true, dualMotor = true,
            speedModeRaw = 3, cruiseOn = true, zeroStartOn = true, speedLimitKmh = 4, totalKm = 6f,
            voltageV = 7f, currentA = 8f, motor1TempC = 9, motor2TempC = 10, powerW = 11f, wheelMoving = true,
        )
        assertEquals(full, Telemetry(receivedAtMs = 0).overlay(full))
        assertEquals(full.copy(receivedAtMs = 9), full.overlay(Telemetry(receivedAtMs = 9)))
    }

    @Test fun frameGroupFollowsTheCmd() {
        assertEquals(FieldGroup.Meter1, TelemetryDecoder.groupOf(frame(meter1Unlocked)))
        assertEquals(FieldGroup.Meter2, TelemetryDecoder.groupOf(frame("1aa12103120000ae1500000000170000000000160f03085d8b1ff1")))
        assertNull(TelemetryDecoder.groupOf(frame("1aa130050101d5b31ff1"))) // ACK
        assertNull(TelemetryDecoder.groupOf(RxFrame(0x20, 0x05, List(18) { 0 }, false))) // tipo errado
    }
}
