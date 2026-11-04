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

    /** S2 `[CONFIRMADO]`: Meter1 e Meter2 chegam sempre com LEN 18. Outro tamanho não é telemetria reconhecida. */
    const val TELEMETRY_PAYLOAD_LEN = 18

    /** Grupo de campos do quadro; `null` se não é telemetria reconhecida (tipo, CMD ou tamanho do payload). */
    fun groupOf(frame: RxFrame): FieldGroup? = when {
        frame.type != TYPE_TELEMETRY -> null
        frame.payload.size != TELEMETRY_PAYLOAD_LEN -> null
        frame.cmd == CMD_METER1 -> FieldGroup.Meter1
        frame.cmd == CMD_METER2 -> FieldGroup.Meter2
        else -> null
    }

    /** `true` se é Meter1/Meter2 (tipo 0x03) mas com tamanho de payload diferente do `[CONFIRMADO]`: quadro malformado. */
    fun isMalformedTelemetry(frame: RxFrame): Boolean =
        frame.type == TYPE_TELEMETRY && (frame.cmd == CMD_METER1 || frame.cmd == CMD_METER2) && groupOf(frame) == null

    fun decode(frame: RxFrame, receivedAtMs: Long): Telemetry? {
        if (groupOf(frame) == null) return null
        return when (frame.cmd) {
            // Meter2: nenhum campo confirmado; só prova que o patinete está falando.
            CMD_METER2 -> Telemetry(receivedAtMs = receivedAtMs)
            CMD_METER1 -> meter1(frame.payload, receivedAtMs)
            else -> null
        }
    }

    private fun meter1(d: List<Int>, receivedAtMs: Long): Telemetry {
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

/** Grupos de campos que chegam em quadros diferentes (e portanto envelhecem separados). */
enum class FieldGroup { Meter1, Meter2 }

/**
 * Telemetria acumulada + quando cada grupo foi visto pela última vez. [Telemetry.receivedAtMs] é só o do ÚLTIMO quadro
 * (de qualquer grupo) e NÃO diz se bateria/bloqueio (Meter1) são frescos: use [ageMs]/[isStale].
 * Granularidade por grupo: um Meter1 com bateria implausível renova o grupo, mas zera os campos do Meter1 que vieram `null` (nunca preserva valor velho como fresco).
 */
data class AgedTelemetry(val telemetry: Telemetry, val meter1AtMs: Long? = null, val meter2AtMs: Long? = null) {
    /** Idade do grupo em [nowMs]; `null` se nunca visto. */
    fun ageMs(group: FieldGroup, nowMs: Long): Long? = seenAt(group)?.let { nowMs - it }

    /** `true` se nunca visto ou mais velho que [maxAgeMs]. */
    fun isStale(group: FieldGroup, nowMs: Long, maxAgeMs: Long): Boolean = (ageMs(group, nowMs) ?: return true) > maxAgeMs

    private fun seenAt(group: FieldGroup): Long? = when (group) {
        FieldGroup.Meter1 -> meter1AtMs
        FieldGroup.Meter2 -> meter2AtMs
    }

    /** Acumula [newer] (campos não nulos vencem) e marca só o [group] do quadro como visto em `newer.receivedAtMs`. */
    fun receive(newer: Telemetry, group: FieldGroup): AgedTelemetry = AgedTelemetry(
        telemetry = telemetry.overlay(newer).let { merged ->
            // Meter1 é dono de bloqueio/bateria/limite: campo inválido (null) num Meter1 NOVO vira desconhecido, em vez
            // de manter o valor velho com a idade renovada.
            if (group == FieldGroup.Meter1) {
                merged.copy(locked = newer.locked, batteryPercent = newer.batteryPercent, speedLimitKmh = newer.speedLimitKmh)
            } else merged
        },
        meter1AtMs = if (group == FieldGroup.Meter1) newer.receivedAtMs else meter1AtMs,
        meter2AtMs = if (group == FieldGroup.Meter2) newer.receivedAtMs else meter2AtMs,
    )

    companion object { fun empty() = AgedTelemetry(Telemetry(receivedAtMs = 0)) }
}
