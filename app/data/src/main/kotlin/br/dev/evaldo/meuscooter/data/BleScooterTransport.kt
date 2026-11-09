package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.AgedTelemetry
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.CommandRefusal
import br.dev.evaldo.meuscooter.protocol.FieldGroup
import br.dev.evaldo.meuscooter.protocol.HeadlightAckCodes
import br.dev.evaldo.meuscooter.protocol.HeadlightRefusal
import br.dev.evaldo.meuscooter.protocol.HeadlightResult
import br.dev.evaldo.meuscooter.protocol.LockRefusal
import br.dev.evaldo.meuscooter.protocol.LockResult
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.RxFrameParser
import br.dev.evaldo.meuscooter.protocol.RxParseResult
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import br.dev.evaldo.meuscooter.protocol.ScooterSettingResult
import br.dev.evaldo.meuscooter.protocol.SpeedModeCatalog
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.TelemetryDecoder
import br.dev.evaldo.meuscooter.protocol.TxCommand
import br.dev.evaldo.meuscooter.protocol.TxFrameBuilder
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import br.dev.evaldo.meuscooter.protocol.UnlockRefusal
import br.dev.evaldo.meuscooter.protocol.UnlockResult
import br.dev.evaldo.meuscooter.protocol.isAckOf
import br.dev.evaldo.meuscooter.protocol.isLightRejection
import br.dev.evaldo.meuscooter.protocol.isPinRejection
import br.dev.evaldo.meuscooter.protocol.isS31Rejection
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
 * 0x10 a cada [BleConfig.heartbeatPeriodMs]. [send] mantém os comandos genéricos desabilitados, com a exceção do farol.
 * As APIs estreitas [unlock], [lock], [setHeadlight], [setUnit], [setDriveMode], [setSpeedLimit] e [setDrive] aplicam
 * suas travas próprias; o PIN não é guardado nem registrado aqui.
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
    /** Auditoria completa (S29): quadros TX/RX em hex (Unlock com PIN e CRC mascarados), GATT, sessão, Unlock/Lock. */
    private val audit: TxAuditLog = TxAuditLog.None,
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

    /** Controle em curso (no máximo um) e seus resultados, concluídos por notificações ou fim do enlace. */
    private val commandInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private var unlockOutcome: CompletableDeferred<UnlockResult>? = null
    /** Bloqueio (modo 2) em curso: usa a mesma trava [commandInFlight] e a mesma fronteira/sequência do desbloqueio. */
    private var lockOutcome: CompletableDeferred<LockResult>? = null
    /** Farol (S31) em curso: mesma trava [commandInFlight] e mesma fronteira/sequência; [headlightTarget] = estado pedido. */
    private var headlightOutcome: CompletableDeferred<HeadlightResult>? = null
    private var headlightTarget: Boolean? = null
    private var settingOutcome: CompletableDeferred<ScooterSettingResult>? = null
    private var settingCommand: TxCommand? = null
    private var settingBoundary: GattBoundary? = null
    private var settingWrittenAfterSequence: Long? = null
    private var speedLimitRangeOutcome: CompletableDeferred<SpeedLimitRange?>? = null
    private var speedLimitRangeBoundary: GattBoundary? = null
    private var speedLimitRange: SpeedLimitRange? = null
    private var unlockBoundary: GattBoundary? = null
    private var unlockWrittenAfterSequence: Long? = null
    /** Última recepção inteiramente consumida por [onNotification], no dispatcher deste transporte. */
    @Volatile private var lastProcessedSequence = 0L
    private var receivedInConnection = false

    private var txSeq = 0L
    private var connectAttempts = 0

    /** Nunca altera o comportamento do transporte: falha do destino do log é ignorada. */
    private fun log(category: String, text: String, atMs: Long = clock.nowMs()) {
        try { audit.record(TxAuditEvent(atMs, category, text)) } catch (_: Exception) { }
    }

    private fun logTx(command: TxCommand, frame: ByteArray) {
        log("TX", "#${++txSeq} ${TxAuditFormat.commandName(command)} ${TxAuditFormat.txFrame(command, frame)}")
    }

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
        receivedInConnection = false
        lastLinkAckAtMs = null
        speedLimitRange = null
        val sess = Job(scope.coroutineContext[Job])
        session = sess
        log("SESSION", "start attempt=${++connectAttempts} ep=$ep")
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
        if (_connection.value != ConnectionStatus.Disconnected) log("SESSION", "end reason=UserDisconnect")
        epoch++
        session?.cancel()
        session = null
        gatt.close()
        linkEnded()
        _connection.value = ConnectionStatus.Disconnected
    }

    /**
     * Comandos genéricos seguem desabilitados: não toca o GATT. Única exceção (S31): [ScooterCommand.SetHeadlight], que
     * vai por [setHeadlight] e só devolve [Ack.Accepted] depois da confirmação pelo Meter1; recusa/falta de confirmação
     * viram [Ack.Rejected] com os códigos de [HeadlightAckCodes]. O desbloqueio usa só [unlock]; o bloqueio, só [lock].
     */
    override suspend fun send(command: ScooterCommand): Ack = when (command) {
        is ScooterCommand.SetHeadlight -> when (val r = setHeadlight(command.on)) {
            HeadlightResult.Confirmed -> Ack.Accepted
            is HeadlightResult.Refused -> Ack.Rejected(HeadlightAckCodes.refused(r.reason))
            HeadlightResult.Rejected -> Ack.Rejected(HEADLIGHT_REJECTED)
            is HeadlightResult.Unconfirmed -> Ack.Rejected(HeadlightAckCodes.unconfirmed(r.cause))
        }
        else -> Ack.Rejected(COMMANDS_DISABLED)
    }

    override val genericCommandsAvailable: Boolean get() = false

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
        log("UNLOCK", "requested")
        val result = try {
            unlockCore(pin)
        } catch (e: CancellationException) {
            log("UNLOCK", "result=Cancelled")
            throw e
        }
        log("UNLOCK", "result=${TxAuditFormat.result(result)}")
        return result
    }

    private suspend fun unlockCore(pin: Pin): UnlockResult {
        if (!config.txEnabled) return UnlockResult.Refused(UnlockRefusal.TxDisabled)
        if (!commandInFlight.compareAndSet(false, true)) return UnlockResult.Refused(UnlockRefusal.Busy)
        val ep = epoch
        val outcome = CompletableDeferred<UnlockResult>()
        var writeStarted = false
        try {
            unlockRefusal()?.let { return UnlockResult.Refused(it) }
            val sentAt = writeLock.withLock {
                // Esperou a vez (ex.: heartbeat em voo): o enlace e o Meter1 podem ter mudado.
                if (ep != epoch) return UnlockResult.Refused(UnlockRefusal.LinkNotReady)
                unlockRefusal()?.let { return UnlockResult.Refused(it) }
                val boundary = gatt.markBoundary()
                // O callback pode já ter recebido movimento que ainda não chegou ao acumulador.
                if (boundary.sequence > lastProcessedSequence) return UnlockResult.Refused(UnlockRefusal.PendingReception)
                val unlockCommand = TxCommand.Unlock(pin)
                val frame = TxFrameBuilder.build(unlockCommand)
                logTx(unlockCommand, frame)
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
                log("UNLOCK", "sent")
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
            commandInFlight.set(false)
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
        log("LOCK", "requested")
        val result = try {
            lockCore()
        } catch (e: CancellationException) {
            log("LOCK", "result=Cancelled")
            throw e
        }
        log("LOCK", "result=${TxAuditFormat.result(result)}")
        return result
    }

    private suspend fun lockCore(): LockResult {
        if (!config.txEnabled) return LockResult.Refused(LockRefusal.TxDisabled)
        if (!commandInFlight.compareAndSet(false, true)) return LockResult.Refused(LockRefusal.Busy)
        val ep = epoch
        val outcome = CompletableDeferred<LockResult>()
        var writeStarted = false
        try {
            lockRefusal()?.let { return LockResult.Refused(it) }
            val sentAt = writeLock.withLock {
                if (ep != epoch) return LockResult.Refused(LockRefusal.LinkNotReady)
                lockRefusal()?.let { return LockResult.Refused(it) }
                val boundary = gatt.markBoundary()
                if (boundary.sequence > lastProcessedSequence) return LockResult.Refused(LockRefusal.PendingReception)
                val frame = TxFrameBuilder.build(TxCommand.Lock)
                logTx(TxCommand.Lock, frame)
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
                log("LOCK", "sent")
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
            commandInFlight.set(false)
        }
    }

    override val headlightAvailable: Boolean get() = config.txEnabled

    override val unitAvailable: Boolean get() = config.txEnabled
    override val driveModeAvailable: Boolean get() = config.txEnabled
    override val speedLimitAvailable: Boolean get() = config.txEnabled
    override val driveAvailable: Boolean get() = config.txEnabled
    override val cruiseAvailable: Boolean get() = config.txEnabled
    override val zeroStartAvailable: Boolean get() = config.txEnabled

    override suspend fun setUnit(mph: Boolean): ScooterSettingResult = guardedSetting(TxCommand.Unit(mph), "UNIT") { t ->
        t.speedUnitMph == mph
    }

    override suspend fun setDriveMode(mode: Int): ScooterSettingResult {
        if (mode !in 0..3) return ScooterSettingResult.Refused(CommandRefusal.InvalidValue)
        return guardedSetting(TxCommand.DriveMode(mode), "MODE", beforeWrite = {
            val mask = _aged.value?.telemetry?.availableModes ?: return@guardedSetting CommandRefusal.ModeUnavailable
            if (mask and (1 shl mode) == 0) CommandRefusal.ModeUnavailable else null
        }) { it.speedModeRaw == mode }
    }

    override suspend fun setSpeedLimit(kmh: Int): ScooterSettingResult {
        if (kmh !in 1..TxCommand.MAX_SPEED_LIMIT_KMH) return ScooterSettingResult.Refused(CommandRefusal.InvalidValue)
        return guardedSetting(TxCommand.SpeedLimit(kmh), "LIMIT", beforeWrite = {
            if (_aged.value?.telemetry?.speedUnitMph != false) return@guardedSetting CommandRefusal.UnitMustBeKmh
            val range = speedLimitRange ?: querySpeedLimitRange()
                ?: return@guardedSetting if (gatt.markBoundary().sequence > lastProcessedSequence) {
                    CommandRefusal.PendingReception
                } else CommandRefusal.RangeUnknown
            if (kmh !in range.minimum..range.maximum) return@guardedSetting CommandRefusal.OutOfRange
            null
        }) { it.speedLimitKmh == kmh }
    }

    override suspend fun setDrive(twoMotors: Boolean): ScooterSettingResult = guardedSetting(
        TxCommand.Drive(twoMotors), "DRIVE", onWriteAttempt = {
            // A written Drive can change the valid limit range even if Meter1 never confirms it.
            speedLimitRange = null
        },
    ) { it.dualMotor == twoMotors }

    /** S32: recusa se o Meter1 atual não traz o campo (nunca envia às cegas); confirma só pelo Meter1 novo. */
    override suspend fun setCruise(on: Boolean): ScooterSettingResult = guardedSetting(TxCommand.Cruise(on), "CRUISE", beforeWrite = {
        if (_aged.value?.telemetry?.cruiseOn == null) CommandRefusal.StateUnknown else null
    }) { it.cruiseOn == on }

    override suspend fun setZeroStart(on: Boolean): ScooterSettingResult = guardedSetting(TxCommand.ZeroStart(on), "ZEROSTART", beforeWrite = {
        if (_aged.value?.telemetry?.zeroStartOn == null) CommandRefusal.StateUnknown else null
    }) { it.zeroStartOn == on }

    private data class SpeedLimitRange(val minimum: Int, val maximum: Int)

    private suspend fun querySpeedLimitRange(): SpeedLimitRange? {
        speedLimitRange?.let { return it }
        val outcome = CompletableDeferred<SpeedLimitRange?>()
        var writeCompleted = false
        val ep = epoch
        try {
            val range = writeLock.withLock {
                if (ep != epoch || _connection.value != ConnectionStatus.Connected) return null
                val boundary = gatt.markBoundary()
                if (boundary.sequence > lastProcessedSequence) return null
                val frame = TxFrameBuilder.build(TxCommand.SpeedLimitRangeQuery)
                speedLimitRangeBoundary = boundary
                speedLimitRangeOutcome = outcome
                try {
                    logTx(TxCommand.SpeedLimitRangeQuery, frame)
                    val completed = withTimeoutOrNull(config.ackTimeoutMs) {
                        gatt.write(ScooterGatt.SERVICE, ScooterGatt.WR, frame)
                        writeCompleted = true
                        outcome.await()
                    }
                    if (!writeCompleted) {
                        terminate(ep, ConnectFailure.WriteTimedOut)
                        return null
                    }
                    completed
                } finally {
                    frame.fill(0)
                    speedLimitRangeOutcome = null
                    speedLimitRangeBoundary = null
                }
            }
            speedLimitRange = range
            return range
        } catch (e: CancellationException) {
            if (!writeCompleted) withContext(NonCancellable) { terminate(ep, ConnectFailure.WriteTimedOut) }
            throw e
        } catch (e: GattException) {
            terminate(ep, if (e.error == GattError.Disconnected) ConnectFailure.LinkLost else ConnectFailure.Gatt(e.error))
            return null
        }
    }

    private suspend fun guardedSetting(
        command: TxCommand,
        category: String,
        beforeWrite: suspend () -> CommandRefusal? = { null },
        onWriteAttempt: () -> Unit = {},
        confirms: (Telemetry) -> Boolean,
    ): ScooterSettingResult {
        log(category, "requested $command")
        if (!config.txEnabled) return ScooterSettingResult.Refused(CommandRefusal.TxDisabled)
        if (!commandInFlight.compareAndSet(false, true)) return ScooterSettingResult.Refused(CommandRefusal.Busy)
        val ep = epoch
        val outcome = CompletableDeferred<ScooterSettingResult>()
        var writeStarted = false
        try {
            guardedRefusal()?.let { return ScooterSettingResult.Refused(it) }
            if (gatt.markBoundary().sequence > lastProcessedSequence) return ScooterSettingResult.Refused(CommandRefusal.PendingReception)
            beforeWrite()?.let { return ScooterSettingResult.Refused(it) }
            val sentAt = writeLock.withLock {
                if (ep != epoch) return ScooterSettingResult.Refused(CommandRefusal.LinkNotReady)
                guardedRefusal()?.let { return ScooterSettingResult.Refused(it) }
                if (gatt.markBoundary().sequence > lastProcessedSequence) return ScooterSettingResult.Refused(CommandRefusal.PendingReception)
                beforeWrite()?.let { return ScooterSettingResult.Refused(it) }
                val boundary = gatt.markBoundary()
                if (boundary.sequence > lastProcessedSequence) return ScooterSettingResult.Refused(CommandRefusal.PendingReception)
                val frame = TxFrameBuilder.build(command)
                logTx(command, frame)
                settingBoundary = boundary
                settingCommand = command
                settingOutcome = outcome
                try {
                    onWriteAttempt()
                    withTimeoutOrNull(config.ackTimeoutMs) {
                        writeStarted = true
                        gatt.write(ScooterGatt.SERVICE, ScooterGatt.WR, frame)
                    } ?: run {
                        terminate(ep, ConnectFailure.WriteTimedOut)
                        return ScooterSettingResult.Unconfirmed(UnconfirmedCause.WriteTimedOut)
                    }
                } finally {
                    frame.fill(0)
                }
                settingWrittenAfterSequence = gatt.markBoundary().sequence
                log(category, "sent")
                boundary.atMs
            }
            val remaining = (sentAt + config.unlockConfirmMs + 1 - clock.nowMs()).coerceAtLeast(0)
            val result = withTimeoutOrNull(remaining) { outcome.await() } ?: ScooterSettingResult.Unconfirmed(UnconfirmedCause.Timeout)
            log(category, "result=${TxAuditFormat.result(result)}")
            return result
        } catch (e: CancellationException) {
            if (writeStarted) withContext(NonCancellable) { terminate(ep, ConnectFailure.WriteTimedOut) }
            throw e
        } catch (e: GattException) {
            terminate(ep, if (e.error == GattError.Disconnected) ConnectFailure.LinkLost else ConnectFailure.Gatt(e.error))
            return ScooterSettingResult.Unconfirmed(if (e.error == GattError.Disconnected) UnconfirmedCause.LinkLost else UnconfirmedCause.WriteFailed)
        } finally {
            settingOutcome = null
            settingCommand = null
            settingBoundary = null
            settingWrittenAfterSequence = null
            commandInFlight.set(false)
        }
    }

    private fun guardedRefusal(): CommandRefusal? {
        val now = clock.nowMs()
        if (_connection.value != ConnectionStatus.Connected) return CommandRefusal.LinkNotReady
        val ackAt = lastLinkAckAtMs ?: return CommandRefusal.LinkNotReady
        if (now - ackAt > config.linkAliveMs) return CommandRefusal.LinkNotReady
        val aged = _aged.value ?: return CommandRefusal.TelemetryStale
        val age = aged.ageMs(FieldGroup.Meter1, now) ?: return CommandRefusal.TelemetryStale
        if (age >= config.unlockMaxMeter1AgeMs) return CommandRefusal.TelemetryStale
        if (aged.telemetry.wheelMoving == true) return CommandRefusal.Moving
        val speed = speedKmh(aged) ?: return CommandRefusal.SpeedUnknown
        if (speed != 0f) return CommandRefusal.Moving
        if (aged.telemetry.locked != false) return CommandRefusal.Locked
        return null
    }

    /**
     * Farol (CMD 0x33; S31, aprovado pelo proprietário). MESMAS travas do [lock] (TX ligado, enlace com ACK recente,
     * Meter1 fresco, roda parada, velocidade conhecida e 0, nenhuma recepção pendente, um comando por vez), MAIS recusa
     * com o patinete BLOQUEADO (como o botão do original; mais seguro). UMA escrita, sem retry. Só um Meter1 NOVO
     * (recebido após a escrita, ≤ [BleConfig.unlockConfirmMs] da fronteira) com dado[0] bit4 igual ao pedido vale
     * [HeadlightResult.Confirmed]; o ACK sozinho não (regra 5). A traseira (bit3) é ignorada. Resposta 0x89 →
     * [HeadlightResult.Rejected]; escrita sem conclusão → estado indeterminado, GATT encerrado.
     */
    suspend fun setHeadlight(on: Boolean): HeadlightResult {
        log("LIGHT", "requested on=$on")
        val result = try {
            headlightCore(on)
        } catch (e: CancellationException) {
            log("LIGHT", "result=Cancelled")
            throw e
        }
        log("LIGHT", "result=${TxAuditFormat.result(result)}")
        return result
    }

    private suspend fun headlightCore(on: Boolean): HeadlightResult {
        if (!config.txEnabled) return HeadlightResult.Refused(HeadlightRefusal.TxDisabled)
        if (!commandInFlight.compareAndSet(false, true)) return HeadlightResult.Refused(HeadlightRefusal.Busy)
        val ep = epoch
        val outcome = CompletableDeferred<HeadlightResult>()
        var writeStarted = false
        try {
            headlightRefusal()?.let { return HeadlightResult.Refused(it) }
            val sentAt = writeLock.withLock {
                if (ep != epoch) return HeadlightResult.Refused(HeadlightRefusal.LinkNotReady)
                headlightRefusal()?.let { return HeadlightResult.Refused(it) }
                val boundary = gatt.markBoundary()
                if (boundary.sequence > lastProcessedSequence) return HeadlightResult.Refused(HeadlightRefusal.PendingReception)
                val command = TxCommand.Light(on)
                val frame = TxFrameBuilder.build(command)
                logTx(command, frame)
                unlockBoundary = boundary
                headlightTarget = on
                headlightOutcome = outcome
                try {
                    withTimeoutOrNull(config.ackTimeoutMs) {
                        writeStarted = true
                        gatt.write(ScooterGatt.SERVICE, ScooterGatt.WR, frame)
                    } ?: run {
                        terminate(ep, ConnectFailure.WriteTimedOut)
                        return HeadlightResult.Unconfirmed(UnconfirmedCause.WriteTimedOut)
                    }
                } finally {
                    frame.fill(0)
                }
                unlockWrittenAfterSequence = gatt.markBoundary().sequence
                log("LIGHT", "sent")
                boundary.atMs
            }
            val remaining = (sentAt + config.unlockConfirmMs + 1 - clock.nowMs()).coerceAtLeast(0)
            return withTimeoutOrNull(remaining) { outcome.await() } ?: HeadlightResult.Unconfirmed(UnconfirmedCause.Timeout)
        } catch (e: CancellationException) {
            if (writeStarted) withContext(NonCancellable) { terminate(ep, ConnectFailure.WriteTimedOut) }
            throw e
        } catch (e: GattException) {
            return if (e.error == GattError.Disconnected) {
                terminate(ep, ConnectFailure.LinkLost)
                HeadlightResult.Unconfirmed(UnconfirmedCause.LinkLost)
            } else {
                terminate(ep, ConnectFailure.Gatt(e.error))
                HeadlightResult.Unconfirmed(UnconfirmedCause.WriteFailed)
            }
        } finally {
            headlightOutcome = null
            headlightTarget = null
            unlockBoundary = null
            unlockWrittenAfterSequence = null
            commandInFlight.set(false)
        }
    }

    /** `null` = pode enviar. Ordem: enlace, idade do Meter1, movimento da roda, velocidade, patinete bloqueado. */
    private fun headlightRefusal(): HeadlightRefusal? {
        val now = clock.nowMs()
        if (_connection.value != ConnectionStatus.Connected) return HeadlightRefusal.LinkNotReady
        val ackAt = lastLinkAckAtMs ?: return HeadlightRefusal.LinkNotReady
        if (now - ackAt > config.linkAliveMs) return HeadlightRefusal.LinkNotReady
        val aged = _aged.value ?: return HeadlightRefusal.TelemetryStale
        val age = aged.ageMs(FieldGroup.Meter1, now) ?: return HeadlightRefusal.TelemetryStale
        if (age >= config.unlockMaxMeter1AgeMs) return HeadlightRefusal.TelemetryStale
        if (aged.telemetry.wheelMoving == true) return HeadlightRefusal.Moving
        val speed = speedKmh(aged) ?: return HeadlightRefusal.SpeedUnknown
        if (speed != 0f) return HeadlightRefusal.Moving
        if (aged.telemetry.locked != false) return HeadlightRefusal.Locked
        return null
    }

    /** `null` = pode enviar. Ordem: enlace, idade do Meter1, movimento da roda, velocidade, já bloqueado. */
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
        if (aged.telemetry.locked != false) return LockRefusal.AlreadyLocked
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
        speedLimitRange = null
        speedLimitRangeOutcome?.complete(null)
        unlockOutcome?.complete(UnlockResult.Unconfirmed(UnconfirmedCause.LinkLost))
        lockOutcome?.complete(LockResult.Unconfirmed(UnconfirmedCause.LinkLost))
        headlightOutcome?.complete(HeadlightResult.Unconfirmed(UnconfirmedCause.LinkLost))
        settingOutcome?.complete(ScooterSettingResult.Unconfirmed(UnconfirmedCause.LinkLost))
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
            log("GATT", "connecting")
            step(ConnectStep.Connect, config.connectTimeoutMs) { gatt.connect(address) }
            val t1 = clock.nowMs()
            log("GATT", "connected")
            patchTimings { it.copy(connectMs = t1 - t0) }
            scope.launch(sess) {
                gatt.state.first { it == GattState.Disconnected }
                log("GATT", "disconnected reason=LinkLost")
                terminate(ep, ConnectFailure.LinkLost)
            }

            val services = step(ConnectStep.DiscoverServices, config.discoverTimeoutMs) { gatt.discoverServices() }
            val t2 = clock.nowMs()
            patchTimings { it.copy(servicesMs = t2 - t1) }
            val missing = missingParts(services)
            log("GATT", if (missing.isEmpty()) "services ok" else "services missing=${missing.size}")
            if (missing.isNotEmpty()) return terminate(ep, ConnectFailure.ServiceMissing(missing))

            // MTU falho/recusado/pequeno demais é FATAL: sem ele o quadro de 27 B chegaria truncado (payload = MTU-3).
            val tMtu = clock.nowMs()
            val granted = step(ConnectStep.Mtu, config.mtuTimeoutMs) { gatt.requestMtu(config.requestedMtu) }
            patchTimings { it.copy(mtuMs = clock.nowMs() - tMtu) }
            log("GATT", "mtu=$granted")
            if (granted < MIN_USABLE_MTU) return terminate(ep, ConnectFailure.MtuTooSmall(granted))

            // Coleta ANTES de gravar o CCCD: um quadro que chegue antes de o CCCD terminar não se perde.
            val firstTelemetryAt = CompletableDeferred<Long>()
            scope.launch(sess, start = CoroutineStart.UNDISPATCHED) {
                gatt.notifications.collect { onNotification(it, firstTelemetryAt) }
            }
            val t3 = clock.nowMs()
            step(ConnectStep.Subscribe, config.subscribeTimeoutMs) { gatt.enableNotifications(ScooterGatt.SERVICE, ScooterGatt.RX) }
            patchTimings { it.copy(subscribeMs = clock.nowMs() - t3) }
            log("GATT", "cccd enabled")

            val arrivedAt = withTimeoutOrNull(config.firstTelemetryTimeoutMs) { firstTelemetryAt.await() }
                ?: return terminate(ep, ConnectFailure.NoTelemetry)
            patchTimings { it.copy(firstTelemetryMs = arrivedAt - t3) }
            if (ep == epoch) {
                log("SESSION", "connected")
                _connection.value = ConnectionStatus.Connected
                if (config.txEnabled) scope.launch(sess) { runTx(ep) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StepTimeout) {
            log("GATT", "timeout step=${e.step}")
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
            val name = TxAuditFormat.commandName(command)
            val acked = withTimeoutOrNull(timeoutMs) {
                val frame = TxFrameBuilder.build(command)
                logTx(command, frame)
                gatt.write(ScooterGatt.SERVICE, ScooterGatt.WR, frame)
                written = true
                ack.await()
            }
            if (!written) {
                log("TX", "$name write-timeout")
                throw WriteTimedOutException()
            }
            log("TX", if (acked != null) "$name acked" else "$name no-ack")
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
        // ADR 0004: o carimbo precede o buffer. Uma lacuna pode esconder Meter1 de movimento;
        // consumir N+1 não torna seguro o Meter1 parado anterior. Só outro Meter1 válido renova sua idade.
        // A primeira recepção ancora cada conexão; GattReception NÃO reinicia a sequência na reconexão.
        if (receivedInConnection && notification.receivedSequence != lastProcessedSequence + 1) {
            _aged.value = _aged.value?.copy(meter1AtMs = null)
            val dropped = (notification.receivedSequence - lastProcessedSequence - 1).coerceAtLeast(0)
            _stats.value = _stats.value.copy(droppedFrames = _stats.value.droppedFrames + dropped)
        }
        val now = notification.receivedAtMs
        log("RX", "#${notification.receivedSequence} ${TxAuditFormat.rxFrame(notification.value)}", now)
        when (val parsed = RxFrameParser.parse(notification.value)) {
            is RxParseResult.Invalid -> _stats.value = _stats.value.copy(invalid = _stats.value.invalid + 1)
            is RxParseResult.Frame -> if (parsed.frame.isPinRejection()) {
                _stats.value = _stats.value.copy(valid = _stats.value.valid + 1)
                if (isCurrentUnlockResponse(notification)) {
                    unlockOutcome?.complete(UnlockResult.PinRejected)
                    lockOutcome?.complete(LockResult.Rejected)
                }
            } else if (parsed.frame.isLightRejection()) {
                _stats.value = _stats.value.copy(valid = _stats.value.valid + 1)
                if (isCurrentUnlockResponse(notification)) headlightOutcome?.complete(HeadlightResult.Rejected)
            } else if (parsed.frame.isS31Rejection()) {
                _stats.value = _stats.value.copy(valid = _stats.value.valid + 1)
                if (settingCommand?.matchesResponse(parsed.frame) == true && isCurrentSettingResponse(notification)) {
                    settingOutcome?.complete(ScooterSettingResult.Rejected)
                }
            } else if (parsed.frame.cmd == TxCommand.CMD_SPEED_LIMIT_RANGE && parsed.frame.type == 0x04 &&
                parsed.frame.payload.size == 2
            ) {
                _stats.value = _stats.value.copy(valid = _stats.value.valid + 1)
                if (speedLimitRangeBoundary?.let { notification.receivedSequence > it.sequence &&
                        notification.receivedAtMs >= it.atMs && notification.receivedAtMs - it.atMs <= config.ackTimeoutMs
                    } == true
                ) {
                    val minimum = parsed.frame.payload[0]
                    val maximum = parsed.frame.payload[1]
                    val range = if (minimum in 1..60 && maximum in minimum..60) SpeedLimitRange(minimum, maximum) else null
                    speedLimitRangeOutcome?.complete(range)
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
                    // Farol (S31): só um Meter1 NOVO com o bit4 igual ao pedido confirma; o ACK não.
                    if (group == FieldGroup.Meter1 && t.headlightOn != null && t.headlightOn == headlightTarget &&
                        isCurrentUnlockResponse(notification) &&
                        unlockWrittenAfterSequence?.let { notification.receivedSequence > it } == true
                    ) headlightOutcome?.complete(HeadlightResult.Confirmed)
                    if (group == FieldGroup.Meter1 && settingCommand != null && confirmsSettingTelemetry(settingCommand!!, t) &&
                        isCurrentSettingResponse(notification) &&
                        settingWrittenAfterSequence?.let { notification.receivedSequence > it } == true
                    ) settingOutcome?.complete(ScooterSettingResult.Confirmed)
                }
            }
        }
        // Inclui ACKs, erros e quadros inválidos: qualquer recepção pendente bloqueia um novo 0x32.
        lastProcessedSequence = notification.receivedSequence
        receivedInConnection = true
    }

    /** Ordem pela sequência de chegada (inclusive no mesmo ms); prazo pelo instante de recepção, nunca consumo. */
    private fun isCurrentUnlockResponse(notification: GattNotification): Boolean {
        val boundary = unlockBoundary ?: return false
        return notification.receivedSequence > boundary.sequence &&
            notification.receivedAtMs >= boundary.atMs && notification.receivedAtMs - boundary.atMs <= config.unlockConfirmMs
    }

    private fun isCurrentSettingResponse(notification: GattNotification): Boolean {
        val boundary = settingBoundary ?: return false
        return notification.receivedSequence > boundary.sequence && notification.receivedAtMs >= boundary.atMs &&
            notification.receivedAtMs - boundary.atMs <= config.unlockConfirmMs
    }

    private fun confirmsSettingTelemetry(command: TxCommand, telemetry: Telemetry): Boolean = when (command) {
        is TxCommand.Unit -> telemetry.speedUnitMph == command.mph
        is TxCommand.DriveMode -> telemetry.speedModeRaw == command.mode
        is TxCommand.SpeedLimit -> telemetry.speedLimitKmh == command.kmh
        is TxCommand.Drive -> telemetry.dualMotor == command.twoMotors
        is TxCommand.Cruise -> telemetry.cruiseOn == command.on
        is TxCommand.ZeroStart -> telemetry.zeroStartOn == command.on
        else -> false
    }

    private fun patchTimings(change: (ConnectionTimings) -> ConnectionTimings) {
        _timings.value = change(_timings.value)
    }

    /** Fecha o GATT, cancela a sessão e passa a Desconectado. Ignora tentativas obsoletas ([ep] antigo) ou já encerradas. */
    private fun terminate(ep: Int, failure: ConnectFailure?) {
        if (ep != epoch || _connection.value == ConnectionStatus.Disconnected) return
        log("SESSION", "end reason=${failure ?: "Cancelled"}")
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
        const val HEADLIGHT_REJECTED = "farol recusado pelo patinete"
    }
}
