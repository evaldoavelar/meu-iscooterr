package br.dev.evaldo.meuscooter.protocol

/**
 * Decodifica Meter1 (CMD 0x20) e Meter2 (CMD 0x21), tipo 0x03, em [Telemetry] SÓ com campos `[CONFIRMADO]`.
 * Fonte: `docs/research/parts/S2-captura-resultados.md`. Tudo o mais fica `null` (velocidade, tensão, corrente,
 * temperaturas, odômetros… seguem `[HIPÓTESE]`/`[DESCONHECIDO]`).
 */
object TelemetryDecoder {
    const val CMD_METER1 = 0x20
    const val CMD_METER2 = 0x21
    const val TYPE_TELEMETRY = 0x03

    /** Índice mínimo dos DADOS do Meter1 para ler o limite (dado[7]). */
    private const val METER1_MIN_PAYLOAD = 8

    fun decode(frame: RxFrame, receivedAtMs: Long): Telemetry? {
        if (frame.type != TYPE_TELEMETRY) return null
        return when (frame.cmd) {
            // Meter2: nenhum campo confirmado; só prova que o patinete está falando.
            CMD_METER2 -> Telemetry(receivedAtMs = receivedAtMs)
            CMD_METER1 -> meter1(frame.payload, receivedAtMs)
            else -> null
        }
    }

    private fun meter1(d: List<Int>, receivedAtMs: Long): Telemetry? {
        if (d.size < METER1_MIN_PAYLOAD) return null
        val battery = d[3] // S2: dado[3] = bateria, 0x64 = 100 %
        return Telemetry(
            receivedAtMs = receivedAtMs,
            // S2: dado[1] bit5 = lock_status (1 = bloqueado, 12/12 ciclos); bit4 = lock_has_pwd (não é bloqueio).
            locked = d[1] and 0x20 != 0,
            batteryPercent = if (battery in 0..100) battery else null,
            speedLimitKmh = d[7], // S2: dado[7] = limite de velocidade atual (0x32 = 50)
        )
    }
}

/** Combina dois instantâneos: campos não nulos de [newer] vencem; [newer].receivedAtMs vale. Meter1 e Meter2 chegam alternados. */
fun Telemetry.overlay(newer: Telemetry): Telemetry = Telemetry(
    receivedAtMs = newer.receivedAtMs,
    locked = newer.locked ?: locked,
    speedKmh = newer.speedKmh ?: speedKmh,
    batteryPercent = newer.batteryPercent ?: batteryPercent,
    headlightOn = newer.headlightOn ?: headlightOn,
    dualMotor = newer.dualMotor ?: dualMotor,
    speedModeRaw = newer.speedModeRaw ?: speedModeRaw,
    cruiseOn = newer.cruiseOn ?: cruiseOn,
    zeroStartOn = newer.zeroStartOn ?: zeroStartOn,
    speedLimitKmh = newer.speedLimitKmh ?: speedLimitKmh,
    totalKm = newer.totalKm ?: totalKm,
    voltageV = newer.voltageV ?: voltageV,
    currentA = newer.currentA ?: currentA,
    motor1TempC = newer.motor1TempC ?: motor1TempC,
    motor2TempC = newer.motor2TempC ?: motor2TempC,
)
