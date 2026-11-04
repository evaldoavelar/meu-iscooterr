package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.AgedTelemetry
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.RxFrameParser
import br.dev.evaldo.meuscooter.protocol.RxParseResult
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import br.dev.evaldo.meuscooter.protocol.SpeedModeCatalog
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.TelemetryDecoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Transporte BLE real sobre [GattClient] (Fase 4.0–4.2). SOMENTE ESCUTA: a única escrita é o CCCD feito por
 * [GattClient.enableNotifications]; [send] nunca toca o GATT. Não codifica CONNECT 0x30, heartbeat, PIN nem WR.
 *
 * `connect()` retorna ao ficar Conectado (1ª telemetria decodificada) ou ao falhar ([failure] explica); cancelar
 * a coroutine, cancelar o escopo do transporte (mesmo depois de Conectado), [disconnect] ou perda do enlace fecham o GATT. Use sempre o mesmo dispatcher (o de [scope]) — não é thread-safe.
 */
class BleScooterTransport(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val gatt: GattClient,
    private val address: String,
    private val config: BleConfig = BleConfig(),
    private val discoveryMs: Long? = null,
) : ScooterTransport {

    private val _connection = MutableStateFlow(ConnectionStatus.Disconnected)
    override val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    private val _telemetry = MutableSharedFlow<Telemetry>(replay = 1, extraBufferCapacity = 64)
    override val telemetry: SharedFlow<Telemetry> = _telemetry.asSharedFlow()

    /** Os nomes dos modos do CMD 0x35 são `[HIPÓTESE]` (só o Fake os inventa); o real não oferece nenhum. */
    override val speedModes = SpeedModeCatalog { emptyList() }

    /** Telemetria acumulada com a idade de cada grupo de campos (bateria/bloqueio = Meter1). `null` até o 1º quadro. */
    private val _aged = MutableStateFlow<AgedTelemetry?>(null)
    val aged: StateFlow<AgedTelemetry?> = _aged.asStateFlow()

    /** Idade por grupo pela interface (M1): quem consome [ScooterTransport] vê bateria/bloqueio velhos. */
    override val agedTelemetry: Flow<AgedTelemetry> = _aged.filterNotNull()

    private val _failure = MutableStateFlow<ConnectFailure?>(null)
    val failure: StateFlow<ConnectFailure?> = _failure.asStateFlow()

    private val _timings = MutableStateFlow(ConnectionTimings())
    val timings: StateFlow<ConnectionTimings> = _timings.asStateFlow()

    private val _stats = MutableStateFlow(RxStats())
    val stats: StateFlow<RxStats> = _stats.asStateFlow()

    private var epoch = 0
    private var session: Job? = null

    private class StepTimeout(val step: ConnectStep) : Exception()

    override suspend fun connect() {
        if (_connection.value != ConnectionStatus.Disconnected) return
        val ep = ++epoch
        _connection.value = ConnectionStatus.Connecting
        _failure.value = null
        _timings.value = ConnectionTimings(scanMs = discoveryMs)
        _stats.value = RxStats()
        _telemetry.resetReplayCache()
        _aged.value = null
        val sess = Job(scope.coroutineContext[Job])
        session = sess
        // Escopo cancelado (ex.: ViewModel destruído) mesmo depois de connect() ter retornado: fecha o GATT.
        // terminate() ignora tentativa obsoleta ou já encerrada, então disparos repetidos são inofensivos.
        sess.invokeOnCompletion { terminate(ep, failure = null) }
        val attempt = scope.launch(sess) { runAttempt(ep, sess) }
        try {
            attempt.join()
        } catch (e: CancellationException) {
            terminate(ep, failure = null)
            throw e
        }
    }

    override suspend fun disconnect() {
        epoch++
        session?.cancel()
        session = null
        gatt.close()
        _connection.value = ConnectionStatus.Disconnected
    }

    /** Fase 4.x: comandos desabilitados (4.4 bloqueada). Não toca o GATT. */
    override suspend fun send(command: ScooterCommand): Ack = Ack.Rejected(COMMANDS_DISABLED)

    /**
     * Janela de observação da etapa 4.2: só escuta por [BleConfig.observationWindowMs] (ou até o enlace cair) e
     * devolve o que chegou nesse período. Não envia nada.
     */
    suspend fun observe(): RxStats {
        val base = _stats.value
        withTimeoutOrNull(config.observationWindowMs) { _connection.first { it == ConnectionStatus.Disconnected } }
        return _stats.value - base
    }

    private suspend fun runAttempt(ep: Int, sess: Job) {
        try {
            val t0 = clock.nowMs()
            step(ConnectStep.Connect, config.connectTimeoutMs) { gatt.connect(address) }
            val t1 = clock.nowMs()
            patchTimings { it.copy(connectMs = t1 - t0) }
            scope.launch(sess) {
                gatt.state.first { it == GattState.Disconnected }
                terminate(ep, ConnectFailure.LinkLost)
            }

            val services = step(ConnectStep.DiscoverServices, config.discoverTimeoutMs) { gatt.discoverServices() }
            val t2 = clock.nowMs()
            patchTimings { it.copy(servicesMs = t2 - t1) }
            val missing = missingParts(services)
            if (missing.isNotEmpty()) return terminate(ep, ConnectFailure.ServiceMissing(missing))

            // MTU falho/recusado/pequeno demais é FATAL: sem ele o quadro de 27 B chegaria truncado (payload = MTU-3).
            val tMtu = clock.nowMs()
            val granted = step(ConnectStep.Mtu, config.mtuTimeoutMs) { gatt.requestMtu(config.requestedMtu) }
            patchTimings { it.copy(mtuMs = clock.nowMs() - tMtu) }
            if (granted < MIN_USABLE_MTU) return terminate(ep, ConnectFailure.MtuTooSmall(granted))

            // Coleta ANTES de gravar o CCCD: um quadro que chegue antes de o CCCD terminar não se perde.
            val firstTelemetryAt = CompletableDeferred<Long>()
            scope.launch(sess, start = CoroutineStart.UNDISPATCHED) {
                gatt.notifications.collect { onNotification(it, firstTelemetryAt) }
            }
            val t3 = clock.nowMs()
            step(ConnectStep.Subscribe, config.subscribeTimeoutMs) { gatt.enableNotifications(ScooterGatt.SERVICE, ScooterGatt.RX) }
            patchTimings { it.copy(subscribeMs = clock.nowMs() - t3) }

            val arrivedAt = withTimeoutOrNull(config.firstTelemetryTimeoutMs) { firstTelemetryAt.await() }
                ?: return terminate(ep, ConnectFailure.NoTelemetry)
            patchTimings { it.copy(firstTelemetryMs = arrivedAt - t3) }
            if (ep == epoch) _connection.value = ConnectionStatus.Connected
        } catch (e: CancellationException) {
            throw e
        } catch (e: StepTimeout) {
            terminate(ep, ConnectFailure.Timeout(e.step))
        } catch (e: GattException) {
            terminate(ep, if (e.error == GattError.Disconnected) ConnectFailure.LinkLost else ConnectFailure.Gatt(e.error))
        } catch (e: Exception) {
            terminate(ep, ConnectFailure.Unexpected(e.message ?: e::class.simpleName.orEmpty()))
        }
    }

    private suspend fun <T> step(step: ConnectStep, timeoutMs: Long, block: suspend () -> T): T =
        try {
            withTimeout(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            throw StepTimeout(step)
        }

    /** Exige FF60 com FF61 e FF62. Devolve o que falta (vazio = ok). */
    private fun missingParts(services: List<GattServiceInfo>): List<java.util.UUID> {
        val service = services.firstOrNull { it.uuid == ScooterGatt.SERVICE } ?: return listOf(ScooterGatt.SERVICE)
        return listOf(ScooterGatt.RX, ScooterGatt.WR).filter { it !in service.characteristics }
    }

    private fun onNotification(raw: ByteArray, firstTelemetryAt: CompletableDeferred<Long>) {
        val now = clock.nowMs()
        when (val parsed = RxFrameParser.parse(raw)) {
            is RxParseResult.Invalid -> _stats.value = _stats.value.copy(invalid = _stats.value.invalid + 1)
            is RxParseResult.Frame -> if (TelemetryDecoder.isMalformedTelemetry(parsed.frame)) {
                // Meter1/Meter2 com tamanho fora do S2 (LEN 18): não é telemetria nem prova de conexão.
                _stats.value = _stats.value.copy(invalid = _stats.value.invalid + 1)
            } else {
                val s = _stats.value
                val t = TelemetryDecoder.decode(parsed.frame, now)
                _stats.value = s.copy(valid = s.valid + 1, telemetry = s.telemetry + if (t != null) 1 else 0)
                if (t != null) {
                    // Meter1 e Meter2 chegam alternados com campos diferentes: acumula, não substitui.
                    val group = checkNotNull(TelemetryDecoder.groupOf(parsed.frame)) // decode != null implica grupo
                    val acc = (_aged.value ?: AgedTelemetry.empty()).receive(t, group)
                    _aged.value = acc
                    // Primeiro quadro: o acumulado == o próprio quadro (empty() só tem receivedAtMs=0, vencido por t).
                    _telemetry.tryEmit(acc.telemetry)
                    firstTelemetryAt.complete(now)
                }
            }
        }
    }

    private fun patchTimings(change: (ConnectionTimings) -> ConnectionTimings) {
        _timings.value = change(_timings.value)
    }

    /** Fecha o GATT, cancela a sessão e passa a Desconectado. Ignora tentativas obsoletas ([ep] antigo) ou já encerradas. */
    private fun terminate(ep: Int, failure: ConnectFailure?) {
        if (ep != epoch || _connection.value == ConnectionStatus.Disconnected) return
        epoch++
        session?.cancel()
        session = null
        gatt.close()
        if (failure != null) _failure.value = failure
        _connection.value = ConnectionStatus.Disconnected
    }

    companion object {
        /** Maior quadro RX da S2 (27 B) + 3 B de cabeçalho de notificação ATT. */
        const val MIN_USABLE_MTU = 30
        const val COMMANDS_DISABLED = "comandos desabilitados na Fase 4.x"
    }
}
