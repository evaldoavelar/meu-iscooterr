package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.protocol.Ack
import br.dev.evaldo.meuscooter.protocol.Clock
import br.dev.evaldo.meuscooter.protocol.ConnectionStatus
import br.dev.evaldo.meuscooter.protocol.ScooterCommand
import br.dev.evaldo.meuscooter.protocol.ScooterTransport
import br.dev.evaldo.meuscooter.protocol.SpeedModeCatalog
import br.dev.evaldo.meuscooter.protocol.SpeedModeOption
import br.dev.evaldo.meuscooter.protocol.Telemetry
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
) : ScooterTransport {

    private val _connection = MutableStateFlow(ConnectionStatus.Disconnected)
    override val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    private val _telemetry = MutableSharedFlow<Telemetry>(replay = 1, extraBufferCapacity = 64)
    override val telemetry: SharedFlow<Telemetry> = _telemetry.asSharedFlow()

    override val speedModes = SpeedModeCatalog { MODES }

    private var snapshot = Telemetry(
        receivedAtMs = 0, locked = true, speedKmh = 0f, batteryPercent = 82, headlightOn = false,
        dualMotor = true, speedModeRaw = 1, cruiseOn = false, zeroStartOn = false, speedLimitKmh = 25, totalKm = 1234.5f,
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

    fun failNextCommand(reason: String) { failNext = reason }
    fun ignoreNextCommand() { ignoreNext = true }
    fun freezeTelemetry(frozen: Boolean) { this.frozen = frozen }
    fun setSpeed(kmh: Float) { snapshot = snapshot.copy(speedKmh = kmh, currentA = kmh * AMPS_PER_KMH) }

    private fun emitNow() {
        _telemetry.tryEmit(snapshot.copy(receivedAtMs = clock.nowMs()))
    }

    private fun apply(t: Telemetry, c: ScooterCommand): Telemetry = when (c) {
        is ScooterCommand.SetLocked -> t.copy(locked = c.locked)
        is ScooterCommand.SetHeadlight -> t.copy(headlightOn = c.on)
        is ScooterCommand.SetDualMotor -> t.copy(dualMotor = c.dual)
        is ScooterCommand.SetSpeedMode -> t.copy(speedModeRaw = c.raw)
        is ScooterCommand.SetCruise -> t.copy(cruiseOn = c.on)
        is ScooterCommand.SetZeroStart -> t.copy(zeroStartOn = c.on)
        is ScooterCommand.SetSpeedLimit -> t.copy(speedLimitKmh = c.kmh)
    }

    companion object {
        /** Corrente simulada proporcional à velocidade `[HIPÓTESE]` — só demonstração. */
        private const val AMPS_PER_KMH = 0.4f

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
