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
        assertEquals(Telemetry(receivedAtMs = 1234, locked = false, batteryPercent = 100, speedLimitKmh = 50, speedKmh = 0f, wheelMoving = false, headlightOn = false, speedModeRaw = 0, speedUnitMph = false, dualMotor = false, cruiseOn = false, zeroStartOn = true), t)
    }

    @Test fun meter1LockedBit5() {
        assertEquals(true, TelemetryDecoder.decode(frame(meter1Locked), 0)!!.locked)
    }

    @Test fun s31Meter1FieldsUseAbsoluteFrameByteOffsets() {
        // Frame byte[12] = payload[7] limit; byte[15] = payload[10] mode/unit/drive status.
        val d = MutableList(18) { 0 }
        d[1] = 0x20
        d[3] = 88
        d[7] = 39
        d[10] = 0b1_1_010 // dual motor, mph, mode 2
        val t = TelemetryDecoder.decode(RxFrame(0x20, 0x03, d, false), 55)!!
        assertEquals(39, t.speedLimitKmh)
        assertEquals(2, t.speedModeRaw)
        assertEquals(true, t.speedUnitMph)
        assertEquals(true, t.dualMotor)
    }

    /** S32: Meter1 reais de s32-original-tap.log (14:49) e s2d; dado[11] = byte[16]: bit6 cruise, bit7 partida do zero DESABILITADA. */
    @Test fun s32Meter1Byte16DecodesCruiseAndInvertedZeroStart() {
        val neutral = TelemetryDecoder.decode(frame("1aa1200312000000600000003c0000030023232302030810e01ff1"), 0)!!
        assertEquals(false, neutral.cruiseOn); assertEquals(true, neutral.zeroStartOn)
        val cruise = TelemetryDecoder.decode(frame("1aa1200312000000600000003c0000034023232302030810e01ff1"), 0)!!
        assertEquals(true, cruise.cruiseOn); assertEquals(true, cruise.zeroStartOn)
        assertEquals(60, cruise.speedLimitKmh); assertEquals(3, cruise.speedModeRaw)
        val noZero = TelemetryDecoder.decode(frame("1aa1200312000000600000003c0000038023232302030810e01ff1"), 0)!!
        assertEquals(false, noZero.cruiseOn); assertEquals(false, noZero.zeroStartOn)
        // s2d: Meter1 com cruise ligado e limite 50
        assertEquals(true, TelemetryDecoder.decode(frame("1aa12003120000006400000032000000" + "40" + "2323230203085237" + "1ff1"), 0)!!.cruiseOn)
        // bits independentes: ambos, e bit6+bit7
        val both = frame("1aa1200312000000600000003c000003c023232302030810e01ff1")
        assertEquals(true, TelemetryDecoder.decode(both, 0)!!.cruiseOn); assertEquals(false, TelemetryDecoder.decode(both, 0)!!.zeroStartOn)
    }

    @Test fun meter2AvailableModesUsesFrameByte20() {
        // Meter2 byte[20] is payload[15]. Bits 0..3 enable modes 0..3.
        val d = MutableList(18) { 0 }
        d[15] = 0b0000_1101
        val t = TelemetryDecoder.decode(RxFrame(0x21, 0x03, d, false), 77)!!
        assertEquals(0b0000_1101, t.availableModes)
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
        assertEquals(Telemetry(receivedAtMs = 400, locked = true, batteryPercent = 100, speedLimitKmh = 50, speedKmh = 0f, wheelMoving = false, headlightOn = false, speedModeRaw = 0, speedUnitMph = false, dualMotor = false, cruiseOn = false, zeroStartOn = true), merged.copy(powerW = null, voltageV = null, currentA = null, totalKm = null, motor1TempC = null, availableModes = null))
        // Meter2 (dado[8] = 0x17, dado[14] = 0x16): o overlay carrega os campos dele junto com os do Meter1.
        assertEquals(23f, merged.totalKm!!, 0f)
        assertEquals(22, merged.motor1TempC)
        val m1b = TelemetryDecoder.decode(frame(meter1Unlocked), 700)!!
        assertEquals(false, merged.overlay(m1b).locked)
    }

    @Test fun overlayCoversEveryField() {
        val full = Telemetry(
            receivedAtMs = 5, locked = true, speedKmh = 1f, batteryPercent = 2, headlightOn = true, dualMotor = true,
            speedModeRaw = 3, cruiseOn = true, zeroStartOn = true, speedLimitKmh = 4, speedUnitMph = true, availableModes = 15, totalKm = 6f,
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

    /**
     * S31: Meter1 dado[0] (byte[5]) bit4 = farol grande `[CONFIRMADO]` (0x00 -> 0x18 ao ligar, 6/6 transições);
     * bit3 (traseira) sobe junto e é ignorado. Quadros calculados fora do código (CRC RX não é exigido).
     */
    @Test fun meter1HeadlightIsBit4OfDado0AndTheRearBitIsIgnored() {
        val off = "1aa1200312000000640000003c0000000023232302030808ac1ff1"
        val both = "1aa1200312180000640000003c000000002323230203088e0b1ff1"
        val onlyHead = "1aa1200312100000640000003c000000002323230203080c691ff1"
        val onlyRear = "1aa1200312080000640000003c000000002323230203088ace1ff1"
        assertEquals(false, TelemetryDecoder.decode(frame(off), 0)!!.headlightOn)
        assertEquals(true, TelemetryDecoder.decode(frame(both), 0)!!.headlightOn)
        assertEquals(true, TelemetryDecoder.decode(frame(onlyHead), 0)!!.headlightOn)
        assertEquals(false, TelemetryDecoder.decode(frame(onlyRear), 0)!!.headlightOn)
    }

    @Test fun meter2DoesNotCarryTheHeadlightAndOverlayKeepsTheMeter1Value() {
        val on = TelemetryDecoder.decode(frame("1aa1200312180000640000003c000000002323230203088e0b1ff1"), 1)!!
        val meter2 = TelemetryDecoder.decode(frame("1aa12103120000ae1500000000170000000000160f03085d8b1ff1"), 2)!!
        assertNull(meter2.headlightOn)
        assertEquals(true, on.overlay(meter2).headlightOn)
    }
}
