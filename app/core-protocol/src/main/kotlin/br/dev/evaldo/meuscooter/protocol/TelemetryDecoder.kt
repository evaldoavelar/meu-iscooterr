package br.dev.evaldo.meuscooter.protocol

import kotlin.math.abs

/**
 * Decodifica Meter1 (CMD 0x20) e Meter2 (CMD 0x21), tipo 0x03, em [Telemetry].
 * Meter1: SÓ campos `[CONFIRMADO]` (S2, S19 movimento da roda). Meter2: S29-meter2-decodificacao.md (`[ESTÁTICO]`;
 * potência `[HIPÓTESE forte]`). Velocidade em km/h não zero segue `[HIPÓTESE]`.
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
            CMD_METER2 -> meter2(frame.payload, receivedAtMs)
            CMD_METER1 -> meter1(frame.payload, receivedAtMs)
            else -> null
        }
    }

    /** Inteiro de 16 bits COM sinal, little-endian (byte baixo primeiro), em `d[i..i+1]`. */
    private fun s16(d: List<Int>, i: Int): Int = ((d[i + 1] shl 8) or d[i]).toShort().toInt()

    /**
     * Meter2 (S29-meter2-decodificacao.md §1). Escalas `[ESTÁTICO]`, plausibilidade em 11 349 quadros reais:
     * - dado[0..1] potência W, s16 x 0,1: `[HIPÓTESE forte]` (unidade W só por V x I; a UI a exibe sem rótulo de estimada); |P| <= 20000 W;
     * - dado[2..3] tensão V, s16 x 0,01; dado[4..5] corrente A COM sinal, s16 x 0,01 (|I| <= 200 A): `[ESTÁTICO]`;
     * - dado[8..9] odômetro km, s16 x 1 (corrige a hipótese antiga de S17, que lia temperatura em dado[8]): `[ESTÁTICO]`;
     * - dado[14] temperatura do motor °C, u8 sem offset: `[ESTÁTICO]`.
     * NÃO decodificados: viagem, autonomia, corrente média, brilho, temp. da bateria (sempre 0) e motor 2 (dado[16], só
     * protocolo >= 1.05; este patinete é 1.03). Valor implausível vira `null` (nunca lixo na tela).
     */
    private fun meter2(d: List<Int>, receivedAtMs: Long): Telemetry {
        val voltage = s16(d, 2) * 0.01f
        val odometer = s16(d, 8)
        val motorTemp = d[14]
        val current = s16(d, 4) * 0.01f
        val power = s16(d, 0) * 0.1f
        return Telemetry(
            receivedAtMs = receivedAtMs,
            availableModes = d[15], // S31: byte[20] (dado[15]) = máscara de modos disponíveis
            powerW = power.takeIf { abs(it) <= MAX_ABS_POWER_W },
            voltageV = voltage.takeIf { it in MIN_VOLTAGE_V..MAX_VOLTAGE_V },
            currentA = current.takeIf { abs(it) <= MAX_ABS_CURRENT_A },
            totalKm = odometer.takeIf { it >= 0 }?.toFloat(), // s16 negativo (ex.: 0x8000) não é odômetro
            motor1TempC = motorTemp.takeIf { it in 0..MAX_MOTOR_TEMP_C },
        )
    }

    private const val MIN_VOLTAGE_V = 20f
    private const val MAX_VOLTAGE_V = 90f
    private const val MAX_MOTOR_TEMP_C = 120
    private const val MAX_ABS_CURRENT_A = 200f   // limite generoso: fora disso é lixo, não corrente
    private const val MAX_ABS_POWER_W = 20_000f

    private fun meter1(d: List<Int>, receivedAtMs: Long): Telemetry {
        val battery = d[3] // S2: dado[3] = bateria, 0x64 = 100 %
        val wheelMoving = d.slice(4..6).any { it != 0 }
        return Telemetry(
            receivedAtMs = receivedAtMs,
            // S2: dado[1] bit5 = lock_status (1 = bloqueado, 12/12 ciclos); bit4 = lock_has_pwd (não é bloqueio).
            locked = d[1] and 0x20 != 0,
            // S31 [CONFIRMADO]: dado[0] bit4 = farol grande (6/6 transições); bit3 (traseira) sobe junto e é ignorado.
            headlightOn = d[0] and 0x10 != 0,
            // S19 [CONFIRMADO]: zero nos três bytes = parado; escala não confirmada.
            speedKmh = if (wheelMoving) null else 0f,
            wheelMoving = wheelMoving,
            batteryPercent = if (battery in 0..100) battery else null,
            speedLimitKmh = d[7], // S2: dado[7] = limite de velocidade atual (0x32 = 50)
            speedModeRaw = d[10] and 0x07, // S31: byte[15] bits 0..2
            speedUnitMph = d[10] and 0x08 != 0, // S31: byte[15] bit 3
            dualMotor = d[10] and 0x10 != 0, // S31: byte[15] bit 4
        )
    }
}

/** Campos não nulos de [newer] vencem; movimento conhecido substitui também km/h por desconhecido. */
fun Telemetry.overlay(newer: Telemetry): Telemetry = Telemetry(
    receivedAtMs = newer.receivedAtMs,
    locked = newer.locked ?: locked,
    speedKmh = if (newer.wheelMoving != null) newer.speedKmh else newer.speedKmh ?: speedKmh,
    wheelMoving = newer.wheelMoving ?: wheelMoving,
    batteryPercent = newer.batteryPercent ?: batteryPercent,
    headlightOn = newer.headlightOn ?: headlightOn,
    dualMotor = newer.dualMotor ?: dualMotor,
    speedModeRaw = newer.speedModeRaw ?: speedModeRaw,
    cruiseOn = newer.cruiseOn ?: cruiseOn,
    zeroStartOn = newer.zeroStartOn ?: zeroStartOn,
    speedLimitKmh = newer.speedLimitKmh ?: speedLimitKmh,
    speedUnitMph = newer.speedUnitMph ?: speedUnitMph,
    availableModes = newer.availableModes ?: availableModes,
    totalKm = newer.totalKm ?: totalKm,
    powerW = newer.powerW ?: powerW,
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
                merged.copy(
                    locked = newer.locked, batteryPercent = newer.batteryPercent, speedLimitKmh = newer.speedLimitKmh,
                    speedKmh = newer.speedKmh, wheelMoving = newer.wheelMoving,
                    speedUnitMph = newer.speedUnitMph, speedModeRaw = newer.speedModeRaw, dualMotor = newer.dualMotor,
                )
            } else {
                // Idem Meter2 (potência/tensão/corrente/odômetro/temp. do motor): campo inválido num Meter2 NOVO vira
                // desconhecido ("--"), nunca o valor velho com idade renovada.
                merged.copy(
                    powerW = newer.powerW, voltageV = newer.voltageV, currentA = newer.currentA,
                    totalKm = newer.totalKm, motor1TempC = newer.motor1TempC,
                    availableModes = newer.availableModes,
                )
            }
        },
        meter1AtMs = if (group == FieldGroup.Meter1) newer.receivedAtMs else meter1AtMs,
        meter2AtMs = if (group == FieldGroup.Meter2) newer.receivedAtMs else meter2AtMs,
    )

    companion object { fun empty() = AgedTelemetry(Telemetry(receivedAtMs = 0)) }
}
