package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.CommandRefusal
import br.dev.evaldo.meuscooter.protocol.LockRefusal
import br.dev.evaldo.meuscooter.protocol.LockResult
import br.dev.evaldo.meuscooter.protocol.Pin
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import br.dev.evaldo.meuscooter.protocol.ScooterSettingResult
import br.dev.evaldo.meuscooter.protocol.SpeedModeCatalog
import br.dev.evaldo.meuscooter.protocol.SpeedModeOption
import br.dev.evaldo.meuscooter.protocol.Telemetry
import br.dev.evaldo.meuscooter.protocol.UnconfirmedCause
import br.dev.evaldo.meuscooter.protocol.UnlockRefusal
import br.dev.evaldo.meuscooter.protocol.UnlockResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Latências de referência medidas em S2 `[CONFIRMADO]`: ACK ~50 ms, estado ~420 ms (p95 ~680 ms), telemetria ~300 ms. */
data class FakeConfig(
    val ackDelayMs: Long = 50,
    val stateDelayMs: Long = 420,
    val telemetryPeriodMs: Long = 300,
    val connectDelayMs: Long = 800,
)

/**
 * Patinete simulado. NENHUM BLE real. Use sempre dentro do mesmo dispatcher (ex.: Main) — não é thread-safe.
 * Injeções para testes e demonstração: [failNextCommand], [ignoreNextCommand], [freezeTelemetry], [setSpeed].
 */
class FakeScooterTransport(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val config: FakeConfig = FakeConfig(),
    /** PIN do patinete simulado (sintético). `null` = sem desbloqueio por PIN: a Home usa [send] como antes. */
    private val pinLock: Pin? = null,
) : ScooterTransport {

    private val _connection = MutableStateFlow(ConnectionStatus.Disconnected)
    override val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    private val _telemetry = MutableSharedFlow<Telemetry>(replay = 1, extraBufferCapacity = 64)
    override val telemetry: SharedFlow<Telemetry> = _telemetry.asSharedFlow()

    override val speedModes = SpeedModeCatalog { MODES }

    private var snapshot = Telemetry(
        receivedAtMs = 0, locked = true, speedKmh = 0f, batteryPercent = 82, headlightOn = false,
        dualMotor = true, speedModeRaw = 1, speedUnitMph = false, availableModes = 0x0f,
        cruiseOn = false, zeroStartOn = false, speedLimitKmh = 25, totalKm = 1234.5f,
        voltageV = 54f, currentA = 0f, motor1TempC = 38, motor2TempC = 40,
    )
    private var frozen = false
    private var failNext: String? = null
    private var ignoreNext = false
    private var session: Job = SupervisorJob(scope.coroutineContext[Job])

    override suspend fun connect() {
        if (_connection.value != ConnectionStatus.Disconnected) return
        _connection.value = ConnectionStatus.Connecting
        delay(config.connectDelayMs)
        _connection.value = ConnectionStatus.Connected
        emitNow()
        scope.launch(session) {
            while (true) {
                delay(config.telemetryPeriodMs)
                if (!frozen) emitNow()
            }
        }
    }

    override suspend fun disconnect() {
        session.cancel()
        session = SupervisorJob(scope.coroutineContext[Job])
        _connection.value = ConnectionStatus.Disconnected
    }

    override suspend fun send(command: ScooterCommand): Ack {
        if (_connection.value != ConnectionStatus.Connected) return Ack.Rejected("not-connected")
        delay(config.ackDelayMs)
        failNext?.let { failNext = null; return Ack.Rejected(it) }
        if (ignoreNext) { ignoreNext = false; return Ack.Accepted }
        scope.launch(session) {
            delay(config.stateDelayMs - config.ackDelayMs)
            snapshot = apply(snapshot, command)
            emitNow()
        }
        return Ack.Accepted
    }

    override val pinUnlockAvailable: Boolean get() = pinLock != null
    private var unlocking = false
    private var settingInFlight = false

    override suspend fun setUnit(mph: Boolean): ScooterSettingResult = applySetting { it.copy(speedUnitMph = mph) }

    override suspend fun setDriveMode(mode: Int): ScooterSettingResult {
        if (mode !in 0..3) return ScooterSettingResult.Refused(CommandRefusal.InvalidValue)
        if (snapshot.availableModes?.and(1 shl mode) != 1 shl mode) return ScooterSettingResult.Refused(CommandRefusal.ModeUnavailable)
        return applySetting { it.copy(speedModeRaw = mode) }
    }

    override suspend fun setSpeedLimit(kmh: Int): ScooterSettingResult {
        if (kmh !in 1..60) return ScooterSettingResult.Refused(CommandRefusal.InvalidValue)
        if (snapshot.speedUnitMph != false) return ScooterSettingResult.Refused(CommandRefusal.UnitMustBeKmh)
        val minimum = if (snapshot.dualMotor == true) 15 else 20
        val maximum = if (snapshot.dualMotor == true) 60 else 50
        if (kmh !in minimum..maximum) return ScooterSettingResult.Refused(CommandRefusal.OutOfRange)
        return applySetting { it.copy(speedLimitKmh = kmh) }
    }

    override suspend fun setDrive(twoMotors: Boolean): ScooterSettingResult = applySetting {
        val range = if (twoMotors) 15..60 else 20..50
        it.copy(dualMotor = twoMotors, speedLimitKmh = (it.speedLimitKmh ?: 25).coerceIn(range))
    }

    private suspend fun applySetting(change: (Telemetry) -> Telemetry): ScooterSettingResult {
        if (_connection.value != ConnectionStatus.Connected) return ScooterSettingResult.Refused(CommandRefusal.LinkNotReady)
        if (settingInFlight || unlocking) return ScooterSettingResult.Refused(CommandRefusal.Busy)
        if (clock.nowMs() - snapshot.receivedAtMs >= MAX_TELEMETRY_AGE_MS) return ScooterSettingResult.Refused(CommandRefusal.TelemetryStale)
        if (snapshot.wheelMoving == true || snapshot.speedKmh?.let { it != 0f } == true) return ScooterSettingResult.Refused(CommandRefusal.Moving)
        if (snapshot.speedKmh == null) return ScooterSettingResult.Refused(CommandRefusal.SpeedUnknown)
        if (snapshot.locked != false) return ScooterSettingResult.Refused(CommandRefusal.Locked)
        settingInFlight = true
        val s = session
        try {
            delay(config.ackDelayMs)
            if (!s.isActive) return ScooterSettingResult.Unconfirmed(UnconfirmedCause.LinkLost)
            failNext?.let { failNext = null; return ScooterSettingResult.Rejected }
            if (ignoreNext) {
                ignoreNext = false
                delay(UNLOCK_CONFIRM_MS - config.ackDelayMs)
                return ScooterSettingResult.Unconfirmed(if (s.isActive) UnconfirmedCause.Timeout else UnconfirmedCause.LinkLost)
            }
            delay(config.stateDelayMs - config.ackDelayMs)
            if (!s.isActive) return ScooterSettingResult.Unconfirmed(UnconfirmedCause.LinkLost)
            snapshot = change(snapshot)
            emitNow()
            return ScooterSettingResult.Confirmed
        } finally {
            settingInFlight = false
        }
    }

    /**
     * Simula o desbloqueio com PIN: mesmas recusas do real (sem escrever nada), PIN errado → [UnlockResult.PinRejected],
     * [ignoreNextCommand] → sem confirmação em 3 s, desconexão no meio → [UnconfirmedCause.LinkLost].
     */
    override suspend fun unlock(pin: Pin): UnlockResult {
        val expected = pinLock ?: return UnlockResult.Refused(UnlockRefusal.NotSupported)
        if (_connection.value != ConnectionStatus.Connected) return UnlockResult.Refused(UnlockRefusal.LinkNotReady)
        if (unlocking) return UnlockResult.Refused(UnlockRefusal.Busy)
        if (clock.nowMs() - snapshot.receivedAtMs >= MAX_TELEMETRY_AGE_MS) return UnlockResult.Refused(UnlockRefusal.TelemetryStale)
        val speed = snapshot.speedKmh ?: return UnlockResult.Refused(UnlockRefusal.SpeedUnknown)
        if (speed != 0f) return UnlockResult.Refused(UnlockRefusal.Moving)
        if (snapshot.locked != true) return UnlockResult.Refused(UnlockRefusal.NotLocked)
        unlocking = true
        val s = session
        try {
            delay(config.ackDelayMs)
            if (!s.isActive) return UnlockResult.Unconfirmed(UnconfirmedCause.LinkLost)
            if (!pin.sameAs(expected)) return UnlockResult.PinRejected
            if (ignoreNext) {
                ignoreNext = false
                delay(UNLOCK_CONFIRM_MS - config.ackDelayMs)
                return UnlockResult.Unconfirmed(if (s.isActive) UnconfirmedCause.Timeout else UnconfirmedCause.LinkLost)
            }
            delay(config.stateDelayMs - config.ackDelayMs)
            if (!s.isActive) return UnlockResult.Unconfirmed(UnconfirmedCause.LinkLost)
            snapshot = snapshot.copy(locked = false)
            emitNow()
            return UnlockResult.Unlocked
        } finally {
            unlocking = false
        }
    }

    override val lockAvailable: Boolean get() = pinLock != null

    /**
     * Simula o bloqueio temporário (modo 2, sem PIN) com o mesmo contrato do real: recusas sem alterar nada,
     * [ignoreNextCommand] → sem confirmação em 3 s, desconexão no meio → [UnconfirmedCause.LinkLost]; só a telemetria
     * nova (bloqueado) conclui. Disponível no modo "real-like" (o mesmo de [pinUnlockAvailable]).
     */
    override suspend fun lock(): LockResult {
        if (pinLock == null) return LockResult.Refused(LockRefusal.NotSupported)
        if (_connection.value != ConnectionStatus.Connected) return LockResult.Refused(LockRefusal.LinkNotReady)
        if (unlocking || settingInFlight) return LockResult.Refused(LockRefusal.Busy)
        if (clock.nowMs() - snapshot.receivedAtMs >= MAX_TELEMETRY_AGE_MS) return LockResult.Refused(LockRefusal.TelemetryStale)
        val speed = snapshot.speedKmh ?: return LockResult.Refused(LockRefusal.SpeedUnknown)
        if (speed != 0f) return LockResult.Refused(LockRefusal.Moving)
        if (snapshot.locked != false) return LockResult.Refused(LockRefusal.AlreadyLocked)
        unlocking = true
        val s = session
        try {
            delay(config.ackDelayMs)
            if (!s.isActive) return LockResult.Unconfirmed(UnconfirmedCause.LinkLost)
            if (ignoreNext) {
                ignoreNext = false
                delay(UNLOCK_CONFIRM_MS - config.ackDelayMs)
                return LockResult.Unconfirmed(if (s.isActive) UnconfirmedCause.Timeout else UnconfirmedCause.LinkLost)
            }
            delay(config.stateDelayMs - config.ackDelayMs)
            if (!s.isActive) return LockResult.Unconfirmed(UnconfirmedCause.LinkLost)
            snapshot = snapshot.copy(locked = true)
            emitNow()
            return LockResult.Locked
        } finally {
            unlocking = false
        }
    }

    fun failNextCommand(reason: String) { failNext = reason }
    fun ignoreNextCommand() { ignoreNext = true }
    fun freezeTelemetry(frozen: Boolean) { this.frozen = frozen }
    fun setSpeed(kmh: Float) { snapshot = snapshot.copy(speedKmh = kmh, currentA = kmh * AMPS_PER_KMH) }

    private fun emitNow() {
        snapshot = snapshot.copy(receivedAtMs = clock.nowMs())
        _telemetry.tryEmit(snapshot)
    }

    private fun apply(t: Telemetry, c: ScooterCommand): Telemetry = when (c) {
        is ScooterCommand.SetLocked -> t.copy(locked = c.locked)
        is ScooterCommand.SetHeadlight -> t.copy(headlightOn = c.on)
        is ScooterCommand.SetDualMotor -> t.copy(dualMotor = c.dual)
        is ScooterCommand.SetSpeedMode -> t.copy(speedModeRaw = c.raw)
        is ScooterCommand.SetCruise -> t.copy(cruiseOn = c.on)
        is ScooterCommand.SetZeroStart -> t.copy(zeroStartOn = c.on)
        is ScooterCommand.SetSpeedLimit -> t.copy(speedLimitKmh = c.kmh)
        is ScooterCommand.SetSpeedUnit -> t.copy(speedUnitMph = c.mph)
    }

    companion object {
        /** Corrente simulada proporcional à velocidade `[HIPÓTESE]` — só demonstração. */
        private const val AMPS_PER_KMH = 0.4f

        /** Mesmo prazo do transporte real (plano §7.4). */
        private const val UNLOCK_CONFIRM_MS = 3_000L

        /** Mesmo limite exclusivo de idade do Meter1 no real (2 s). */
        private const val MAX_TELEMETRY_AGE_MS = 2_000L

        /**
         * Rótulos PROVISÓRIOS `[HIPÓTESE]`: o app original enviou valores 0–3 no CMD 0x35 `[CONFIRMADO]`,
         * mas a correspondência valor↔nome NÃO foi validada. Ordem segue a direção de UX do proprietário.
         * Esta é a única associação nome/valor do projeto; a UI só lê [SpeedModeCatalog].
         */
        const val PROVISIONAL_MODE_NOTE = "valor 0..3 ↔ Pedestre/Eco/Race/Sport: HIPÓTESE, não validada em teste real"
        private val MODES = listOf(
            SpeedModeOption(0, "Pedestre"), SpeedModeOption(1, "Eco"),
            SpeedModeOption(2, "Race"), SpeedModeOption(3, "Sport"),
        )
    }
}
