package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Telemetry

/** Estado da gravação da viagem na tela Telemetria (ADR 0013). */
enum class TripRecState { Running, Paused, Stopped }

/**
 * Máquina de estados da viagem (Play/Pause/Parar/Reset). Local: nunca envia nada ao patinete.
 * `Stopped` não acumula nada; `Paused` congela duração, distância, médias, máxima e energia; `Running` acumula.
 * Só [stop] e [zero] devolvem uma viagem para o histórico; [reset] descarta.
 */
class TripSession(initial: TripRecState = TripRecState.Running, private val wallClockMs: () -> Long = System::currentTimeMillis) {
    private val tracker = TripTracker()
    private var startedAtMs = 0L
    private val modeEvents = ArrayList<TripModeEvent>()
    private var hasRecordedInitial = false
    private var lastRecordedMode: Int? = null
    private var lastRecordedMotor: Boolean? = null

    var state: TripRecState = initial
        private set

    /** Viagem em curso (congelada se pausada); `null` se parada ou sem amostras. */
    val current: TripSummary? get() = if (state == TripRecState.Stopped) null else tracker.current

    init { if (initial == TripRecState.Paused) tracker.pause() }

    fun onTelemetry(t: Telemetry, meter1Fresh: Boolean = true) {
        if (state == TripRecState.Stopped) return
        if (state == TripRecState.Running && tracker.current == null) startedAtMs = wallClockMs()   // instante (relógio de parede) da 1ª amostra
        tracker.onTelemetry(t)
        if (state == TripRecState.Running && meter1Fresh) {
            val mode = t.speedModeRaw
            val motor = t.dualMotor
            if (mode != null || motor != null) {
                val elapsed = tracker.current?.durationMs ?: 0L
                if (!hasRecordedInitial) {
                    hasRecordedInitial = true
                    lastRecordedMode = mode
                    lastRecordedMotor = motor
                    modeEvents.add(TripModeEvent(elapsedMs = 0L, mode = mode, dualMotor = motor))
                } else if (mode != lastRecordedMode || motor != lastRecordedMotor) {
                    lastRecordedMode = mode
                    lastRecordedMotor = motor
                    modeEvents.add(TripModeEvent(elapsedMs = elapsed, mode = mode, dualMotor = motor))
                }
            }
        }
    }

    /** Parada: começa viagem nova. Pausada: retoma. Contando: nada. */
    fun play() {
        if (state == TripRecState.Running) return
        if (state == TripRecState.Stopped) resetModeEvents()
        tracker.resume()
        state = TripRecState.Running
    }

    fun pause() {
        if (state != TripRecState.Running) return
        tracker.pause()
        state = TripRecState.Paused
    }

    /** Encerra, fica parada em 00:00 e devolve a viagem a salvar (`null` se não for um registro útil). */
    fun stop(): Trip? {
        val saved = closeTrip()
        tracker.resume()
        state = TripRecState.Stopped
        return saved
    }

    /** Descarta a viagem em curso sem salvar e fica parada em 00:00. */
    fun reset() {
        tracker.reset()
        tracker.resume()
        resetModeEvents()
        state = TripRecState.Stopped
    }

    /** "Zerar viagem" do menu: devolve a viagem a salvar e recomeça vazia mantendo o estado (contando/pausada/parada). */
    fun zero(): Trip? = closeTrip()

    private fun closeTrip(): Trip? {
        val started = startedAtMs
        val s = tracker.reset() ?: run { resetModeEvents(); return null }
        val events = modeEvents.toList()
        resetModeEvents()
        // Amostra única ou duração zero não é registro útil; distância pode ser 0 (ADR 0013, cavalete).
        return if (s.durationMs > 0) Trip.from(s, started, events) else null
    }

    private fun resetModeEvents() {
        modeEvents.clear()
        hasRecordedInitial = false
        lastRecordedMode = null
        lastRecordedMotor = null
    }
}
