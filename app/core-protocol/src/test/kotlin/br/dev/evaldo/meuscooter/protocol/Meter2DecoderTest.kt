package br.dev.evaldo.meuscooter.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Meter2 (CMD 0x21). Esperados calculados à mão a partir de `docs/research/parts/S29-meter2-decodificacao.md` §1/§2/§5
 * (não pela função testada). Potência em W é `[HIPÓTESE forte]`; os demais campos `[ESTÁTICO]` + plausibilidade em captura real.
 */
class Meter2DecoderTest {
    private fun frame(hex: String): RxFrame =
        (RxFrameParser.parse(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) as RxParseResult.Frame).frame

    private fun decode(hex: String) = TelemetryDecoder.decode(frame(hex), 10)!!

    /** Quadro A com bytes de dado[] sobrescritos (CRC irrelevante no decoder). */
    private fun synthetic(vararg edits: Pair<Int, Int>): Telemetry {
        val base = frame("1aa12103120000401500000000180000000000210f0308e5151ff1")
        val payload = base.payload.toMutableList().also { p -> edits.forEach { (i, v) -> p[i] = v } }
        return TelemetryDecoder.decode(base.copy(payload = payload), 10)!!
    }

    @Test fun frameA_parked33C() {
        // dado[2..3] = 40 15 -> 0x1540 = 5440 -> 54,40 V; dado[8] = 0x18 = 24 km; dado[14] = 0x21 = 33 C.
        val t = decode("1aa12103120000401500000000180000000000210f0308e5151ff1")
        assertEquals(54.40f, t.voltageV!!, 0.001f)
        assertEquals(0f, t.currentA!!, 0.001f)
        assertEquals(0f, t.powerW!!, 0.001f)
        assertEquals(24f, t.totalKm!!, 0f)
        assertEquals(33, t.motor1TempC)
        assertNull(t.motor2TempC)
    }

    @Test fun frameB_36C() {
        // 0x154a = 5450 -> 54,50 V; dado[14] = 0x24 = 36.
        val t = decode("1aa121031200004a1500000000180000000000240f0308ec4f1ff1")
        assertEquals(54.50f, t.voltageV!!, 0.001f)
        assertEquals(24f, t.totalKm!!, 0f)
        assertEquals(36, t.motor1TempC)
    }

    @Test fun frameC_throttleOnStand() {
        // dado[0..1] = 68 03 -> 0x0368 = 872 -> 87,2 W; dado[4..5] = a0 00 -> 160 -> 1,60 A; dado[14] = 0x19 = 25.
        val t = decode("1aa121031268034a15a0000000180000000000190f030813f11ff1")
        assertEquals(87.2f, t.powerW!!, 0.001f)
        assertEquals(54.50f, t.voltageV!!, 0.001f)
        assertEquals(1.60f, t.currentA!!, 0.001f)
        assertEquals(24f, t.totalKm!!, 0f)
        assertEquals(25, t.motor1TempC)
        // Invariante do doc: |P - V*I| <= 0,1.
        assertEquals(0.0, Math.abs((t.powerW!! - t.voltageV!! * t.currentA!!).toDouble()), 0.1)
    }

    @Test fun frameD_odo23_motor2Ignored() {
        // 0x15a4 = 5540 -> 55,40 V; dado[8] = 0x17 = 23; dado[14] = 0x16 = 22; dado[16] = 0xF1 NAO vira motor 2.
        val t = decode("1aa12103120000a41500000000170000000000160ff10857811ff1")
        assertEquals(55.40f, t.voltageV!!, 0.001f)
        assertEquals(23f, t.totalKm!!, 0f)
        assertEquals(22, t.motor1TempC)
        assertNull(t.motor2TempC)
    }

    @Test fun motor2IsNeverDecoded() {
        assertNull(synthetic(16 to 3).motor2TempC)
        assertNull(synthetic(16 to 0xF1).motor2TempC)
    }

    @Test fun signAndScale() {
        // 0xff9c = -100 como s16 -> -1,00 A; 0x1388 = 5000 -> 50,00 V; 0xfc18 = -1000 -> -100,0 W.
        val t = synthetic(4 to 0x9c, 5 to 0xff, 2 to 0x88, 3 to 0x13, 0 to 0x18, 1 to 0xfc)
        assertEquals(-1.00f, t.currentA!!, 0.001f)
        assertEquals(50.00f, t.voltageV!!, 0.001f)
        assertEquals(-100.0f, t.powerW!!, 0.001f)
    }

    @Test fun odometerIsSixteenBitLittleEndian() {
        // 0xe8 0x03 = 1000 km.
        assertEquals(1000f, synthetic(8 to 0xe8, 9 to 0x03).totalKm!!, 0f)
    }

    @Test fun odometerNegativeAsSignedIsInvalid() {
        // 0x00 0x80 = -32768 como s16: não é odômetro -> null.
        assertNull(synthetic(8 to 0x00, 9 to 0x80).totalKm)
    }

    @Test fun tripIsNotDecodedAndDoesNotLeakIntoOdometer() {
        // dado[6..7] = e8 03 (viagem 100,0 km) não altera o odômetro (continua 24).
        assertEquals(24f, synthetic(6 to 0xe8, 7 to 0x03).totalKm!!, 0f)
    }

    @Test fun implausibleValuesBecomeNull() {
        assertNull(synthetic(14 to 121).motor1TempC)
        assertNull(synthetic(14 to 0xF1).motor1TempC)
        assertNull(synthetic(2 to 0x00, 3 to 0x00).voltageV) // 0,00 V
        assertNull(synthetic(2 to 0x10, 3 to 0x27).voltageV) // 0x2710 = 10000 -> 100,00 V
        assertNull(synthetic(2 to 0xc7, 3 to 0x00).voltageV) // 199 -> 1,99 V
    }

    @Test fun currentAndPowerBeyondGenerousLimitsBecomeNull() {
        // 0x4e20 = 20000 -> 200,00 A (limite, vale); 0x4e21 = 20001 -> 200,01 A (null). Potência: 0x7fff = 32767 -> 3276,7 W (vale);
        // limite 20000 W não é atingível por s16 x 0,1 (máx. 3276,7), então só a corrente exercita o corte.
        assertEquals(200.0f, synthetic(4 to 0x20, 5 to 0x4e).currentA!!, 0.001f)
        assertNull(synthetic(4 to 0x21, 5 to 0x4e).currentA)
        assertNull(synthetic(4 to 0x00, 5 to 0x7f).currentA) // 0x7f00 = 32512 -> 325,12 A
        assertEquals(-200.0f, synthetic(4 to 0xe0, 5 to 0xb1).currentA!!, 0.001f) // 0xb1e0 = -20000
        assertNull(synthetic(4 to 0x00, 5 to 0x80).currentA) // -32768 -> -327,68 A
        assertEquals(3276.7f, synthetic(0 to 0xff, 1 to 0x7f).powerW!!, 0.01f)
    }

    @Test fun wrongLengthIsNotTelemetry() {
        assertNull(TelemetryDecoder.decode(frame("1aa121030000001ff1"), 0))
    }

    @Test fun meter2LeavesMeter1FieldsUnknown() {
        val t = decode("1aa12103120000401500000000180000000000210f0308e5151ff1")
        assertNull(t.locked)
        assertNull(t.batteryPercent)
        assertNull(t.speedLimitKmh)
        assertNull(t.wheelMoving)
    }

    @Test fun newMeter2WithInvalidFieldDoesNotKeepOldValueAsFresh() {
        val ok = decode("1aa12103120000401500000000180000000000210f0308e5151ff1")
        val bad = TelemetryDecoder.decode(
            frame("1aa12103120000401500000000180000000000210f0308e5151ff1").let { it.copy(payload = it.payload.toMutableList().also { p -> p[14] = 200 }) }, 20,
        )!!
        val aged = AgedTelemetry.empty().receive(ok, FieldGroup.Meter2).receive(bad, FieldGroup.Meter2)
        assertNull(aged.telemetry.motor1TempC)
        assertEquals(54.40f, aged.telemetry.voltageV!!, 0.001f)
        assertEquals(20L, aged.meter2AtMs)
    }

    @Test fun meter2DoesNotClearMeter1Fields() {
        val m1 = Telemetry(receivedAtMs = 1, locked = true, batteryPercent = 80, speedLimitKmh = 50)
        val aged = AgedTelemetry.empty().receive(m1, FieldGroup.Meter1)
            .receive(decode("1aa12103120000401500000000180000000000210f0308e5151ff1"), FieldGroup.Meter2)
        assertEquals(true, aged.telemetry.locked)
        assertEquals(80, aged.telemetry.batteryPercent)
        assertEquals(50, aged.telemetry.speedLimitKmh)
    }
}
