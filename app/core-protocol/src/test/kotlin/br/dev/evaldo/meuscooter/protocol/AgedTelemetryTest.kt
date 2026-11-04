package br.dev.evaldo.meuscooter.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** Números escritos à mão: Meter1 visto em t=1000, Meter2 em t=6000, agora = 6000 → Meter1 tem 5000 ms. */
class AgedTelemetryTest {
    private val meter1 = Telemetry(receivedAtMs = 1000, locked = true, batteryPercent = 80, speedLimitKmh = 50)
    private val meter2 = Telemetry(receivedAtMs = 6000)

    @Test fun staleMeter1StaysOldWhileMeter2IsFresh() {
        val aged = AgedTelemetry.empty().receive(meter1, FieldGroup.Meter1).receive(meter2, FieldGroup.Meter2)
        assertEquals(5000L, aged.ageMs(FieldGroup.Meter1, nowMs = 6000))
        assertEquals(0L, aged.ageMs(FieldGroup.Meter2, nowMs = 6000))
        assertEquals(6000L, aged.telemetry.receivedAtMs) // o geral parece fresco, mas bateria/lock têm 5 s
    }

    @Test fun staleMeansOlderThanTheLimitAndNeverSeenIsStale() {
        val aged = AgedTelemetry.empty().receive(meter1, FieldGroup.Meter1)
        assertEquals(false, aged.isStale(FieldGroup.Meter1, nowMs = 6000, maxAgeMs = 5000)) // 5000 não é > 5000
        assertEquals(true, aged.isStale(FieldGroup.Meter1, nowMs = 6001, maxAgeMs = 5000))
        assertEquals(null, aged.ageMs(FieldGroup.Meter2, nowMs = 6000)) // Meter2 nunca visto
        assertEquals(true, aged.isStale(FieldGroup.Meter2, nowMs = 6000, maxAgeMs = 5000))
        assertEquals(true, AgedTelemetry.empty().isStale(FieldGroup.Meter1, 0, 5000))
    }

    @Test fun receivingOneGroupKeepsTheOtherGroupsFieldsAndTime() {
        val aged = AgedTelemetry.empty().receive(meter1, FieldGroup.Meter1).receive(meter2, FieldGroup.Meter2)
        assertEquals(Telemetry(receivedAtMs = 6000, locked = true, batteryPercent = 80, speedLimitKmh = 50), aged.telemetry)
        val refreshed = aged.receive(meter1.copy(receivedAtMs = 7000, locked = false), FieldGroup.Meter1)
        assertEquals(false, refreshed.telemetry.locked)
        assertEquals(0L, refreshed.ageMs(FieldGroup.Meter1, 7000))
        assertEquals(1000L, refreshed.ageMs(FieldGroup.Meter2, 7000))
    }

    @Test fun invalidBatteryInANewMeter1DoesNotKeepTheOldValueAsFresh() {
        // Revisão Codex P3: 100% em t=1000; em t=9000 chega Meter1 com bateria 0xFF (campo null). O 100% antigo não pode sobreviver.
        val old = Telemetry(receivedAtMs = 1000, locked = true, batteryPercent = 100, speedLimitKmh = 50)
        val invalidBattery = Telemetry(receivedAtMs = 9000, locked = false, batteryPercent = null, speedLimitKmh = 50)
        val aged = AgedTelemetry.empty().receive(old, FieldGroup.Meter1).receive(invalidBattery, FieldGroup.Meter1)
        assertEquals(null, aged.telemetry.batteryPercent)
        assertEquals(false, aged.telemetry.locked)
        assertEquals(9000L, aged.meter1AtMs)
    }

    @Test fun meter2DoesNotClearMeter1Fields() {
        val m1 = Telemetry(receivedAtMs = 1000, locked = true, batteryPercent = 100, speedLimitKmh = 50)
        val aged = AgedTelemetry.empty().receive(m1, FieldGroup.Meter1).receive(Telemetry(receivedAtMs = 2000), FieldGroup.Meter2)
        assertEquals(100, aged.telemetry.batteryPercent)
        assertEquals(1000L, aged.meter1AtMs)
    }
}
