package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Telemetry
import kotlinx.coroutines.flow.Flow

/** Velocidade medida pelo GPS do celular. [receivedAtMs] usa o MESMO relógio monotônico de `Telemetry.receivedAtMs`. */
data class GpsSpeedSample(val speedKmh: Float, val receivedAtMs: Long)

/** Fonte de velocidade por GPS; `null` = sem GPS/permissão. Só para exibição e viagem (ADR 0005). */
interface GpsSpeedSource {
    val samples: Flow<GpsSpeedSample?>

    companion object {
        /** Sem GPS: emite `null` (padrão do ViewModel e do Fake). */
        val None: GpsSpeedSource = of(kotlinx.coroutines.flow.flowOf(null))
        fun of(flow: Flow<GpsSpeedSample?>): GpsSpeedSource = object : GpsSpeedSource { override val samples = flow }
    }
}

/** Idade máxima de uma amostra de GPS para valer como velocidade atual. */
const val GPS_MAX_AGE_MS = 3_000L

/**
 * Velocidade para EXIBIÇÃO e VIAGEM. NÃO usar em decisão de segurança (reducer, desbloqueio, ações restritas):
 * essas continuam em `wheelMoving` do Meter1. Regras:
 * - `wheelMoving == false`: 0 (a roda parada manda; o GPS oscila parado);
 * - `wheelMoving == true`: GPS fresco e válido, senão `null` (desconhecida, nunca inventada);
 * - `wheelMoving == null` (Fake/transportes com km/h reais): mantém `speedKmh`.
 */
fun Telemetry.withGpsSpeed(sample: GpsSpeedSample?, nowMs: Long, maxAgeMs: Long = GPS_MAX_AGE_MS): Telemetry = when (wheelMoving) {
    null -> this
    false -> copy(speedKmh = 0f)
    true -> {
        val fresh = sample?.takeIf { nowMs - it.receivedAtMs <= maxAgeMs && it.speedKmh.isFinite() && it.speedKmh >= 0f }
        copy(speedKmh = fresh?.speedKmh)
    }
}

/**
 * Velocidade só para a VIAGEM (distância/média/máx.); o velocímetro e `speedKmh` exibido seguem [withGpsSpeed].
 * GPS fresco tem prioridade, inclusive 0 km/h com roda girando e patinete reportando velocidade positiva.
 * Sem GPS fresco e com a roda em movimento, usa `scooterSpeedKmh` apenas com Meter1 fresco
 * ([meter1AtMs] conhecido, idade <= [HomeRules.STALE_AFTER_MS], mesma régua monotônica de [nowMs]).
 * Meter2 não renova essa idade. Sem Meter1 fresco ou velocidade válida (mph/dúvida, ADR 0005 S42/S44):
 * `null` (sem amostra). Roda parada: 0. NÃO usar em segurança.
 */
fun Telemetry.forTrip(sample: GpsSpeedSample?, nowMs: Long, meter1AtMs: Long?, maxAgeMs: Long = GPS_MAX_AGE_MS): Telemetry {
    val r = withGpsSpeed(sample, nowMs, maxAgeMs)
    if (wheelMoving != true || r.speedKmh != null) return r
    if (meter1AtMs == null || nowMs - meter1AtMs > HomeRules.STALE_AFTER_MS) return r
    val own = scooterSpeedKmh?.takeIf { it.isFinite() && it >= 0f }
    return r.copy(speedKmh = own)
}

/** Converte uma leitura de GPS (m/s) em amostra; sem velocidade reportada ou inválida → `null`. Lógica pura, fora da classe Android. */
fun gpsSampleOf(hasSpeed: Boolean, speedMetersPerSecond: Float, atMs: Long): GpsSpeedSample? =
    if (hasSpeed && speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0f) GpsSpeedSample(speedMetersPerSecond * 3.6f, atMs) else null
