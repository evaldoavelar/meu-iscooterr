package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Esperado à mão: 30 km/h por 1 s = 30/3600 km. Amostras a cada 1 s (intervalo <= STALE_AFTER_MS).
 * Pausa congela tudo; o tempo pausado não entra na duração (t=0,1,2 s contando; pausa; t=10..11 s; retoma em t=12 s → duração 2 s + 0 s do gap + 1 s).
 */
class TripSessionTest {
    private fun s(tMs: Long, kmh: Float = 30f) = Telemetry(receivedAtMs = tMs, speedKmh = kmh)
    private fun TripSession.feed(vararg t: Long) = t.forEach { onTelemetry(s(it)) }

    @Test fun `contando acumula e comeca com horario de parede da 1a amostra`() {
        val session = TripSession(wallClockMs = { 777L })
        session.feed(0, 1_000, 2_000)
        assertEquals(TripRecState.Running, session.state)
        assertEquals(2_000L, session.current!!.durationMs)
        assertEquals(777L, session.stop()!!.startedAtMs)
    }

    @Test fun `pausada congela duracao distancia e maxima`() {
        val session = TripSession()
        session.feed(0, 1_000, 2_000)
        val before = session.current!!
        session.pause()
        session.onTelemetry(s(3_000, 50f)); session.onTelemetry(s(4_000, 50f))
        assertEquals(TripRecState.Paused, session.state)
        assertEquals(before, session.current)
    }

    @Test fun `retomar nao conta o intervalo pausado nem integra a distancia dele`() {
        val session = TripSession()
        session.feed(0, 1_000, 2_000)
        session.pause()
        session.feed(10_000, 11_000)
        session.play()
        session.feed(12_000, 13_000)
        val trip = session.current!!
        assertEquals(TripRecState.Running, session.state)
        // 2 s antes + 1 s depois da retomada (12 → 13 s); o pulo 2 → 12 s é pausa.
        assertEquals(3_000L, trip.durationMs)
        // Distância: 0→1 s e 1→2 s a 30 km/h (60/3600) + 12→13 s a 30 km/h (30/3600) = 90/3600; o pulo 2→12 s não integra.
        assertEquals(90f / 3600f, trip.distanceKm, 0.00001f)
    }

    @Test fun `parada nao acumula e mostra nada`() {
        val session = TripSession(TripRecState.Stopped)
        session.feed(0, 1_000, 2_000)
        assertNull(session.current)
        assertNull(session.stop())
    }

    @Test fun `play a partir de parada comeca viagem nova`() {
        val session = TripSession(TripRecState.Stopped, wallClockMs = { 5L })
        session.play()
        session.feed(0, 1_000, 2_000)
        assertEquals(2_000L, session.current!!.durationMs)
        assertEquals(5L, session.stop()!!.startedAtMs)
    }

    @Test fun `parar devolve a viagem para salvar e fica parada em zero`() {
        val session = TripSession()
        session.feed(0, 1_000, 2_000)
        val trip = session.stop()
        assertNotNull(trip)
        assertEquals(2_000L, trip!!.durationMs)
        assertEquals(TripRecState.Stopped, session.state)
        assertNull(session.current)
        session.feed(3_000, 4_000)
        assertNull(session.current)
    }

    @Test fun `parar pausada tambem salva a viagem congelada`() {
        val session = TripSession()
        session.feed(0, 1_000, 2_000)
        session.pause()
        assertEquals(2_000L, session.stop()!!.durationMs)
    }

    @Test fun `parar viagem com distancia zero e duracao maior que zero salva com zero km`() {
        val session = TripSession(wallClockMs = { 100L })
        session.onTelemetry(s(0, 0f))
        session.onTelemetry(s(1_000, 0f))
        session.onTelemetry(s(2_000, 0f))
        val trip = session.stop()
        assertNotNull(trip)
        assertEquals(0f, trip!!.distanceKm, 0.0001f)
        assertEquals(2_000L, trip.durationMs)
        assertEquals(0f, trip.avgKmh, 0.0001f)
        assertEquals(100L, trip.startedAtMs)
        assertEquals(TripRecState.Stopped, session.state)
    }

    @Test fun `amostra unica ou duracao zero nao gera registro`() {
        val session = TripSession()
        session.onTelemetry(s(0, 0f))
        assertNull(session.stop())
        assertEquals(TripRecState.Stopped, session.state)

        val sameInstant = TripSession()
        sameInstant.onTelemetry(s(1_000, 0f))
        sameInstant.onTelemetry(s(1_000, 0f))
        assertNull(sameInstant.stop())

        val empty = TripSession()
        assertNull(empty.stop())
    }

    @Test fun `reset descarta sem devolver viagem e fica parada`() {
        val session = TripSession()
        session.feed(0, 1_000, 2_000)
        session.reset()
        assertEquals(TripRecState.Stopped, session.state)
        assertNull(session.current)
        session.play()
        session.feed(5_000, 6_000)
        assertEquals(1_000L, session.current!!.durationMs)
    }

    @Test fun `reset estando pausada tambem fica parada e o proximo play comeca do zero`() {
        val session = TripSession()
        session.feed(0, 1_000, 2_000)
        session.pause()
        session.reset()
        session.play()
        session.feed(8_000, 9_000)
        assertEquals(1_000L, session.current!!.durationMs)
    }

    @Test fun `iniciar pausada nao acumula ate o play`() {
        val session = TripSession(TripRecState.Paused)
        session.feed(0, 1_000)
        assertNull(session.current)
        session.play()
        session.feed(2_000, 3_000)
        assertEquals(1_000L, session.current!!.durationMs)
    }
}
