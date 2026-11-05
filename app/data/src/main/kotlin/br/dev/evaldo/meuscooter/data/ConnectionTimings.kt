package br.dev.evaldo.meuscooter.data

import java.util.UUID

/** Passos de `connect()` que têm timeout próprio. */
enum class ConnectStep { Connect, DiscoverServices, Mtu, Subscribe }

/** Por que `connect()` não chegou a Conectado. `null` em `failure` = sem falha (ou desconexão pedida pelo usuário). */
sealed interface ConnectFailure {
    data class Timeout(val step: ConnectStep) : ConnectFailure

    /** Serviço FF60 ausente, ou característica FF61/FF62 ausente nele. */
    data class ServiceMissing(val missing: List<UUID>) : ConnectFailure

    /** O patinete concedeu um MTU que não comporta o maior quadro RX conhecido (27 B + 3 B de cabeçalho ATT): quadros chegariam truncados. */
    data class MtuTooSmall(val granted: Int) : ConnectFailure

    /** Nenhum quadro de telemetria decodificável em `firstTelemetryTimeoutMs` após o CCCD. Nada foi enviado. */
    data object NoTelemetry : ConnectFailure
    data object LinkLost : ConnectFailure

    /** Modo TX (4.3a): o CONNECT 0x30 foi escrito uma vez e nenhum ACK (tipo 05) chegou em `ackTimeoutMs`. Não reenvia. */
    data object ConnectNotAcknowledged : ConnectFailure

    /** Modo TX (4.3a): `heartbeatMissLimit` heartbeats seguidos sem ACK (≥ 3 s) — tratado como enlace perdido. */
    data object HeartbeatLost : ConnectFailure
    data class Gatt(val error: GattError) : ConnectFailure
    data class Unexpected(val message: String) : ConnectFailure
}

/**
 * Parâmetros de tempo. Os 15 s e 60 s são os aprovados no plano da Fase 4 (§6); os timeouts por passo (10 s) são
 * escolha de engenharia, sem medição `[DESCONHECIDO]` (S2 não mediu t_scan/t_conn) — ajustar após a etapa 4.2.
 */
data class BleConfig(
    val connectTimeoutMs: Long = 10_000,
    val discoverTimeoutMs: Long = 10_000,
    val mtuTimeoutMs: Long = 10_000,
    /** MTU pedido (ATT_MTU máximo do Android = 517; 247 = 244 B de payload, cabe um quadro de 27 B e o que a S2 mostrou). `[HIPÓTESE]`: o que o patinete aceita. */
    val requestedMtu: Int = 247,
    val subscribeTimeoutMs: Long = 10_000,
    /** 1ª telemetria depois da inscrição (CCCD). */
    val firstTelemetryTimeoutMs: Long = 15_000,
    /** Janela de escuta da etapa 4.2 — constante SEPARADA do timeout acima. */
    val observationWindowMs: Long = 60_000,
    /**
     * Modo TX da etapa 4.3a (CONNECT 0x30 + heartbeat 0x10). DESLIGADO por padrão: sem ele a única escrita é o CCCD.
     * Só a chave de depuração `ble_tx` (build depurável + `ble_real`) liga isto.
     */
    val txEnabled: Boolean = false,
    /** Prazo do ACK do CONNECT (plano §7.4: ACK ausente > 2 s = abortar). Original: ACK em ~110 ms `[CONFIRMADO]`. */
    val ackTimeoutMs: Long = 2_000,
    /** Período do heartbeat; também é o prazo do ACK de cada heartbeat. Original: ~1,0 s `[CONFIRMADO]` (s4-3). */
    val heartbeatPeriodMs: Long = 1_000,
    /** Heartbeats SEGUIDOS sem ACK que encerram o enlace (plano §7.2). */
    val heartbeatMissLimit: Int = 3,
)

/**
 * Duração de cada passo em ms (`null` = ainda não concluído). [scanMs] vem da descoberta (4.1), se houve.
 * [firstTelemetryMs] conta desde o início da inscrição (chamada de `enableNotifications`), então nunca é negativo
 * mesmo que o quadro chegue antes de o CCCD terminar.
 */
data class ConnectionTimings(
    val scanMs: Long? = null,
    val connectMs: Long? = null,
    val servicesMs: Long? = null,
    val mtuMs: Long? = null,
    val subscribeMs: Long? = null,
    val firstTelemetryMs: Long? = null,
)

/** Contadores de quadros RX (diagnóstico da etapa 4.2). `valid` = estrutura ok (qualquer CMD); `telemetry` ⊂ `valid`. */
data class RxStats(val valid: Int = 0, val telemetry: Int = 0, val invalid: Int = 0) {
    operator fun minus(base: RxStats) = RxStats(valid - base.valid, telemetry - base.telemetry, invalid - base.invalid)
}
