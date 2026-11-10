package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Telemetry

/** Estado da gravação da viagem na tela Telemetria (ADR 0012). */
enum class TripRecState { Running, Paused, Stopped }

/**
 * Máquina de estados da viagem (Play/Pause/Parar/Reset). Local: nunca envia nada ao patinete.
 * `Stopped` não acumula nada; `Paused` congela duração, distância, médias, máxima e energia; `Running` acumula.
 * Só [stop] e [zero] devolvem uma viagem para o histórico; [reset] descarta.
 */
class TripSession(initial: TripRecState = TripRecState.Running, private val wallClockMs: () -> Long = System::currentTimeMillis) {
    private val tracker = TripTracker()
    private var startedAtMs = 0L

    var state: TripRecState = initial
        private set

    /** Viagem em curso (congelada se pausada); `null` se parada ou sem amostras. */
    val current: TripSummary? get() = if (state == TripRecState.Stopped) null else tracker.current

    init { if (initial == TripRecState.Paused) tracker.pause() }

    fun onTelemetry(t: Telemetry) {
        if (state == TripRecState.Stopped) return
        if (state == TripRecState.Running && tracker.current == null) startedAtMs = wallClockMs()   // instante (relógio de parede) da 1ª amostra
        tracker.onTelemetry(t)
    }

    /** Parada: começa viagem nova. Pausada: retoma. Contando: nada. */
    fun play() {
        if (state == TripRecState.Running) return
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
        state = TripRecState.Stopped
    }

    /** "Zerar viagem" do menu: devolve a viagem a salvar e recomeça vazia mantendo o estado (contando/pausada/parada). */
    fun zero(): Trip? = closeTrip()

    private fun closeTrip(): Trip? {
        val started = startedAtMs
        val s = tracker.reset() ?: return null
        // Viagem sem distância ou duração (uma amostra só) não é registro útil.
        return if (s.distanceKm > 0f && s.durationMs > 0) Trip.from(s, started) else null
    }
}
