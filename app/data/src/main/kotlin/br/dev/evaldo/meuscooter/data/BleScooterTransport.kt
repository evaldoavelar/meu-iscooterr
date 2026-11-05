package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.AgedTelemetry
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.FieldGroup
import br.dev.evaldo.meuscooter.protocol.LockRefusal
import br.dev.evaldo.meuscooter.protocol.LockResult
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.RxFrameParser
import br.dev.evaldo.meuscooter.protocol.RxParseResult
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import br.dev.evaldo.meuscooter.protocol.SpeedModeCatalog
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.TelemetryDecoder
import br.dev.evaldo.meuscooter.protocol.TxCommand
import br.dev.evaldo.meuscooter.protocol.TxFrameBuilder
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import br.dev.evaldo.meuscooter.protocol.UnlockRefusal
import br.dev.evaldo.meuscooter.protocol.UnlockResult
import br.dev.evaldo.meuscooter.protocol.isAckOf
import br.dev.evaldo.meuscooter.protocol.isPinRejection
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Transporte BLE real sobre [GattClient] (Fase 4.0–4.3a). Por padrão SOMENTE ESCUTA: a única escrita é o CCCD feito por
 * [GattClient.enableNotifications]. Com [BleConfig.txEnabled] (chave `ble_tx`, desligada por padrão) escreve em FF62
 * apenas os quadros fechados de [TxCommand]: CONNECT 0x30 uma vez após Conectado (espera o ACK) e depois o heartbeat
 * 0x10 a cada [BleConfig.heartbeatPeriodMs]. [send] nunca toca o GATT. Os únicos comandos do usuário são [unlock] (4.4) e [lock] (S20, modo 2 sem PIN),
 * com pré-condições próprias; o PIN não é guardado nem registrado aqui.
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
    /**
     * Velocidade usada pela pré-condição do [unlock] (`null` = desconhecida → recusa).
     * S19: o decoder só fornece km/h conhecido quando parado (0); movimento da roda recusa antes deste seam.
     */
    private val speedKmh: (AgedTelemetry) -> Float? = { it.telemetry.speedKmh },
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

    /** ACK esperado pela escrita em voo (no máximo uma: [writeLock]). */
    private var awaitingAck: Pair<TxCommand, CompletableDeferred<Long>>? = null
    private val writeLock = Mutex()

    /** Último ACK de CONNECT/heartbeat desta conexão; `null` = CONNECT ainda sem ACK (pré-condição do [unlock]). */
    private var lastLinkAckAtMs: Long? = null

    /** Desbloqueio em curso (no máximo um) e o resultado que as notificações/encerramento podem concluir. */
    private var commandInFlight = false
    private var unlockOutcome: CompletableDeferred<UnlockResult>? = null
    /** Bloqueio (modo 2) em curso: usa a mesma trava [commandInFlight] e a mesma fronteira/sequência do desbloqueio. */
    private var lockOutcome: CompletableDeferred<LockResult>? = null
    private var unlockBoundary: GattBoundary? = null
    private var unlockWrittenAfterSequence: Long? = null

    private class StepTimeout(val step: ConnectStep) : Exception()
    private class WriteTimedOutException : Exception()

    override suspend fun connect() {
        if (_connection.value != ConnectionStatus.Disconnected) return
        val ep = ++epoch
        _connection.value = ConnectionStatus.Connecting
        _failure.value = null
        _timings.value = ConnectionTimings(scanMs = discoveryMs)
        _stats.value = RxStats()
        _telemetry.resetReplayCache()
        _aged.value = null
        lastLinkAckAtMs = null
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
        linkEnded()
        _connection.value = ConnectionStatus.Disconnected
    }

    /** Comandos genéricos seguem desabilitados: não toca o GATT. O desbloqueio usa só [unlock]. */
    override suspend fun send(command: ScooterCommand): Ack = Ack.Rejected(COMMANDS_DISABLED)

    override val pinUnlockAvailable: Boolean get() = config.txEnabled

    /**
     * Desbloqueio com PIN (plano Fase 4 §7.4). Pré-condições verificadas antes de qualquer escrita (e de novo já com a
     * vez de escrever): modo TX ligado; Conectado com CONNECT e heartbeat com ACK há no máximo [BleConfig.linkAliveMs];
     * Meter1 com idade < [BleConfig.unlockMaxMeter1AgeMs]; velocidade conhecida e 0; Meter1 `dado[1]` bit5 = 1.
     * Escreve UM quadro `LOCK 0x32` modo 0 e espera até [BleConfig.unlockConfirmMs] (contados do início da escrita):
     * só um Meter1 com bit5 = 0 nesse prazo vale [UnlockResult.Unlocked] (o ACK tipo 05 sozinho não); tipo 0x89 →
     * [UnlockResult.PinRejected]. Sem retry. Escrita sem conclusão em [BleConfig.ackTimeoutMs] → estado indeterminado,
     * GATT encerrado ([ConnectFailure.WriteTimedOut]), como no heartbeat. Uma chamada com outra em curso é recusada.
     */
    override suspend fun unlock(pin: Pin): UnlockResult {
        if (!config.txEnabled) return UnlockResult.Refused(UnlockRefusal.TxDisabled)
        if (commandInFlight) return UnlockResult.Refused(UnlockRefusal.Busy)
        unlockRefusal()?.let { return UnlockResult.Refused(it) }
        commandInFlight = true
        val ep = epoch
        val outcome = CompletableDeferred<UnlockResult>()
        var writeStarted = false
        try {
            val sentAt = writeLock.withLock {
                // Esperou a vez (ex.: heartbeat em voo): o enlace e o Meter1 podem ter mudado.
                if (ep != epoch) return UnlockResult.Refused(UnlockRefusal.LinkNotReady)
                unlockRefusal()?.let { return UnlockResult.Refused(it) }
                val frame = TxFrameBuilder.build(TxCommand.Unlock(pin))
                val boundary = gatt.markBoundary()
                unlockBoundary = boundary
                // Armado ANTES da escrita; carimbos anteriores não pertencem a esta tentativa.
                unlockOutcome = outcome
                try {
                    withTimeoutOrNull(config.ackTimeoutMs) {
                        writeStarted = true
                        gatt.write(ScooterGatt.SERVICE, ScooterGatt.WR, frame)
                    } ?: run {
                        terminate(ep, ConnectFailure.WriteTimedOut)
                        return UnlockResult.Unconfirmed(UnconfirmedCause.WriteTimedOut)
                    }
                } finally {
                    frame.fill(0)
                }
                // Snapshot inclui o que chegou ao callback, mesmo se ainda aguarda consumo no buffer.
                unlockWrittenAfterSequence = gatt.markBoundary().sequence
                boundary.atMs
            }
            // Relógio em ms: mantém a espera durante o último ms inclusivo; o carimbo RX limita a aceitação.
            val remaining = (sentAt + config.unlockConfirmMs + 1 - clock.nowMs()).coerceAtLeast(0)
            return withTimeoutOrNull(remaining) { outcome.await() } ?: UnlockResult.Unconfirmed(UnconfirmedCause.Timeout)
        } catch (e: CancellationException) {
            // Cancelar a espera não cancela a escrita no stack: nenhuma nova tentativa nesta conexão.
            if (writeStarted) withContext(NonCancellable) { terminate(ep, ConnectFailure.WriteTimedOut) }
            throw e
        } catch (e: GattException) {
            return if (e.error == GattError.Disconnected) {
                terminate(ep, ConnectFailure.LinkLost)
                UnlockResult.Unconfirmed(UnconfirmedCause.LinkLost)
            } else {
                terminate(ep, ConnectFailure.Gatt(e.error))
                UnlockResult.Unconfirmed(UnconfirmedCause.WriteFailed)
            }
        } finally {
            unlockOutcome = null
            unlockBoundary = null
            unlockWrittenAfterSequence = null
            commandInFlight = false
        }
    }

    override val lockAvailable: Boolean get() = config.txEnabled

    /**
     * Bloqueio temporário (LOCK 0x32 modo 2, sem PIN; S20). Mesmas regras do [unlock], com a falha segura de nunca
     * travar em movimento: modo TX ligado, enlace com ACK recente, Meter1 fresco, velocidade conhecida e 0, roda parada.
     * UMA escrita, sem retry; só um Meter1 NOVO (após a escrita, ≤ [BleConfig.unlockConfirmMs]) com bit5 = 1 vale
     * [LockResult.Locked] — o ACK tipo 05 sozinho não. Resposta tipo 0x89 → [LockResult.Rejected]; escrita sem conclusão →
     * estado indeterminado, GATT encerrado.
     */
    override suspend fun lock(): LockResult {
        if (!config.txEnabled) return LockResult.Refused(LockRefusal.TxDisabled)
        if (commandInFlight) return LockResult.Refused(LockRefusal.Busy)
        lockRefusal()?.let { return LockResult.Refused(it) }
        commandInFlight = true
        val ep = epoch
        val outcome = CompletableDeferred<LockResult>()
        var writeStarted = false
        try {
            val sentAt = writeLock.withLock {
                if (ep != epoch) return LockResult.Refused(LockRefusal.LinkNotReady)
                lockRefusal()?.let { return LockResult.Refused(it) }
                val frame = TxFrameBuilder.build(TxCommand.Lock)
                val boundary = gatt.markBoundary()
                unlockBoundary = boundary
                lockOutcome = outcome
                try {
                    withTimeoutOrNull(config.ackTimeoutMs) {
                        writeStarted = true
                        gatt.write(ScooterGatt.SERVICE, ScooterGatt.WR, frame)
                    } ?: run {
                        terminate(ep, ConnectFailure.WriteTimedOut)
                        return LockResult.Unconfirmed(UnconfirmedCause.WriteTimedOut)
                    }
                } finally {
                    frame.fill(0)
                }
                unlockWrittenAfterSequence = gatt.markBoundary().sequence
                boundary.atMs
            }
            val remaining = (sentAt + config.unlockConfirmMs + 1 - clock.nowMs()).coerceAtLeast(0)
            return withTimeoutOrNull(remaining) { outcome.await() } ?: LockResult.Unconfirmed(UnconfirmedCause.Timeout)
        } catch (e: CancellationException) {
            if (writeStarted) withContext(NonCancellable) { terminate(ep, ConnectFailure.WriteTimedOut) }
            throw e
        } catch (e: GattException) {
            return if (e.error == GattError.Disconnected) {
                terminate(ep, ConnectFailure.LinkLost)
                LockResult.Unconfirmed(UnconfirmedCause.LinkLost)
            } else {
                terminate(ep, ConnectFailure.Gatt(e.error))
                LockResult.Unconfirmed(UnconfirmedCause.WriteFailed)
            }
        } finally {
            lockOutcome = null
            unlockBoundary = null
            unlockWrittenAfterSequence = null
            commandInFlight = false
        }
    }

    /** `null` = pode enviar. Ordem: enlace, idade do Meter1, movimento da roda, velocidade. */
    private fun lockRefusal(): LockRefusal? {
        val now = clock.nowMs()
        if (_connection.value != ConnectionStatus.Connected) return LockRefusal.LinkNotReady
        val ackAt = lastLinkAckAtMs ?: return LockRefusal.LinkNotReady
        if (now - ackAt > config.linkAliveMs) return LockRefusal.LinkNotReady
        val aged = _aged.value ?: return LockRefusal.TelemetryStale
        val age = aged.ageMs(FieldGroup.Meter1, now) ?: return LockRefusal.TelemetryStale
        if (age >= config.unlockMaxMeter1AgeMs) return LockRefusal.TelemetryStale
        if (aged.telemetry.wheelMoving == true) return LockRefusal.Moving
        val speed = speedKmh(aged) ?: return LockRefusal.SpeedUnknown
        if (speed != 0f) return LockRefusal.Moving
        return null
    }

    /** `null` = pode enviar. Ordem: enlace, idade do Meter1, velocidade, bloqueio. */
    private fun unlockRefusal(): UnlockRefusal? {
        val now = clock.nowMs()
        if (_connection.value != ConnectionStatus.Connected) return UnlockRefusal.LinkNotReady
        val ackAt = lastLinkAckAtMs ?: return UnlockRefusal.LinkNotReady
        if (now - ackAt > config.linkAliveMs) return UnlockRefusal.LinkNotReady
        val aged = _aged.value ?: return UnlockRefusal.TelemetryStale
        val age = aged.ageMs(FieldGroup.Meter1, now) ?: return UnlockRefusal.TelemetryStale
        if (age >= config.unlockMaxMeter1AgeMs) return UnlockRefusal.TelemetryStale
        if (aged.telemetry.wheelMoving == true) return UnlockRefusal.Moving
        val speed = speedKmh(aged) ?: return UnlockRefusal.SpeedUnknown
        if (speed != 0f) return UnlockRefusal.Moving
        if (aged.telemetry.locked != true) return UnlockRefusal.NotLocked
        return null
    }

    /** Enlace encerrado: sem ACK válido e um desbloqueio em espera termina sem confirmação. */
    private fun linkEnded() {
        lastLinkAckAtMs = null
        unlockOutcome?.complete(UnlockResult.Unconfirmed(UnconfirmedCause.LinkLost))
        lockOutcome?.complete(LockResult.Unconfirmed(UnconfirmedCause.LinkLost))
    }

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
            if (ep == epoch) {
                _connection.value = ConnectionStatus.Connected
                if (config.txEnabled) scope.launch(sess) { runTx(ep) }
            }
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

    /**
     * Modo TX: CONNECT uma vez (sem ACK em [BleConfig.ackTimeoutMs] → [ConnectFailure.ConnectNotAcknowledged]) e depois
     * o laço de heartbeat. Escrita sem confirmação no prazo → [ConnectFailure.WriteTimedOut] (GATT encerrado, nenhuma
     * escrita nova). Vive na sessão: para em [disconnect], [terminate] e no cancelamento do escopo. O heartbeat é
     * só manutenção do enlace — não é comando da UI (sem pendência, sem "concluído").
     */
    private suspend fun runTx(ep: Int) {
        try {
            lastLinkAckAtMs = exchange(TxCommand.Connect, config.ackTimeoutMs)
                ?: return terminate(ep, ConnectFailure.ConnectNotAcknowledged)
            var next = clock.nowMs() + config.heartbeatPeriodMs
            var missed = 0
            while (true) {
                delay((next - clock.nowMs()).coerceAtLeast(0))
                // Retomada atrasada (dispatcher/processo parado): os horários vencidos são DESCARTADOS, não recuperados
                // em rajada — o próximo sai no mínimo um período depois deste envio.
                val sentAt = clock.nowMs()
                next = maxOf(next, sentAt) + config.heartbeatPeriodMs
                val ackAt = exchange(TxCommand.Heartbeat, config.heartbeatPeriodMs)
                if (ackAt != null) {
                    missed = 0
                    lastLinkAckAtMs = ackAt
                } else if (++missed >= config.heartbeatMissLimit) {
                    return terminate(ep, ConnectFailure.HeartbeatLost)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WriteTimedOutException) {
            terminate(ep, ConnectFailure.WriteTimedOut)
        } catch (e: GattException) {
            terminate(ep, if (e.error == GattError.Disconnected) ConnectFailure.LinkLost else ConnectFailure.Gatt(e.error))
        } catch (e: Exception) {
            terminate(ep, ConnectFailure.Unexpected(e.message ?: e::class.simpleName.orEmpty()))
        }
    }

    /**
     * Escreve [command] em FF62 e espera o ACK (mesmo CMD, tipo 05; CRC não exigido) até [timeoutMs]. Uma única escrita
     * em voo ([writeLock]); o ACK é armado ANTES da escrita porque a notificação pode chegar antes de
     * `onCharacteristicWrite`. Devolve o instante de RECEPÇÃO do ACK; `null` = escrita confirmada e ACK ausente.
     * Se a ESCRITA não conclui no prazo lança [WriteTimedOutException]: cancelar a espera não cancela a operação no
     * stack, então o estado é indeterminado e quem chama encerra o GATT sem tentar outra escrita.
     */
    private suspend fun exchange(command: TxCommand, timeoutMs: Long): Long? = writeLock.withLock {
        val ack = CompletableDeferred<Long>()
        awaitingAck = command to ack
        try {
            var written = false
            val acked = withTimeoutOrNull(timeoutMs) {
                gatt.write(ScooterGatt.SERVICE, ScooterGatt.WR, TxFrameBuilder.build(command))
                written = true
                ack.await()
            }
            if (!written) throw WriteTimedOutException()
            acked
        } finally {
            awaitingAck = null
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

    private fun onNotification(notification: GattNotification, firstTelemetryAt: CompletableDeferred<Long>) {
        val now = notification.receivedAtMs
        when (val parsed = RxFrameParser.parse(notification.value)) {
            is RxParseResult.Invalid -> _stats.value = _stats.value.copy(invalid = _stats.value.invalid + 1)
            is RxParseResult.Frame -> if (parsed.frame.isPinRejection()) {
                _stats.value = _stats.value.copy(valid = _stats.value.valid + 1)
                if (isCurrentUnlockResponse(notification)) {
                    unlockOutcome?.complete(UnlockResult.PinRejected)
                    lockOutcome?.complete(LockResult.Rejected)
                }
            } else if (awaitingAck?.let { (cmd, ack) -> parsed.frame.isAckOf(cmd) && ack.complete(now) } == true) {
                _stats.value = _stats.value.copy(valid = _stats.value.valid + 1)
            } else if (TelemetryDecoder.isMalformedTelemetry(parsed.frame)) {
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
                    // Só um Meter1 NOVO com bit5 = 0 confirma o desbloqueio (regra 5); o ACK do LOCK não.
                    if (group == FieldGroup.Meter1 && t.locked == false &&
                        isCurrentUnlockResponse(notification) &&
                        unlockWrittenAfterSequence?.let { notification.receivedSequence > it } == true
                    ) unlockOutcome?.complete(UnlockResult.Unlocked)
                    // Idem para o bloqueio (modo 2): só um Meter1 NOVO com bit5 = 1 confirma; o ACK não.
                    if (group == FieldGroup.Meter1 && t.locked == true &&
                        isCurrentUnlockResponse(notification) &&
                        unlockWrittenAfterSequence?.let { notification.receivedSequence > it } == true
                    ) lockOutcome?.complete(LockResult.Locked)
                }
            }
        }
    }

    /** Ordem pela sequência de chegada (inclusive no mesmo ms); prazo pelo instante de recepção, nunca consumo. */
    private fun isCurrentUnlockResponse(notification: GattNotification): Boolean {
        val boundary = unlockBoundary ?: return false
        return notification.receivedSequence > boundary.sequence &&
            notification.receivedAtMs >= boundary.atMs && notification.receivedAtMs - boundary.atMs <= config.unlockConfirmMs
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
        linkEnded()
        if (failure != null) _failure.value = failure
        _connection.value = ConnectionStatus.Disconnected
    }

    companion object {
        /** Maior quadro RX da S2 (27 B) + 3 B de cabeçalho de notificação ATT. */
        const val MIN_USABLE_MTU = 30
        const val COMMANDS_DISABLED = "comandos desabilitados na Fase 4.x"
    }
}
