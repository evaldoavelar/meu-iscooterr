package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.Trip
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Esperado de design/history.py. "Agora" = terça 2026-11-03 20:00 UTC (calculado à mão: 03/11/2026 é terça;
 * sábado 31/10 pertence à semana anterior, que começa na segunda 26/10).
 */
class HistoryUiMapperTest {
    @Test fun `altimetria do detalhe usa GPS filtrado e distancia real da rota`() {
        // Dois trechos de 0,01 grau no equador: 2,2239 km; +18 m e −12 m ultrapassam o limiar.
        val route = listOf(
            br.dev.evaldo.meuscooter.domain.GeoPoint(0.0, 0.0, 742.0),
            br.dev.evaldo.meuscooter.domain.GeoPoint(0.01, 0.0, 760.0),
            br.dev.evaldo.meuscooter.domain.GeoPoint(0.02, 0.0, 748.0),
        )
        val t = trip(1, now, 4.8f, 21, 14f, 27f).copy(route = route)
        val d = mapper.detail(t, now)
        val a = requireNotNull(d.altimetry)
        assertEquals("+18 m", d.altitude); assertEquals("+18 m", a.gain); assertEquals("−12 m", a.loss)
        assertEquals("742 m", a.min); assertEquals("760 m", a.max)
        assertEquals("0 km", a.startDistance); assertEquals("2,2 km", a.endDistance)
        assertEquals(listOf(0f, 0.5f, 1f), a.segments.single().map { it.distanceFraction })
        assertEquals(0f, a.segments.single().first().heightFraction, 0f)
        assertEquals(1f, a.segments.single()[1].heightFraction, 0f)
        val imperial = requireNotNull(mapper.detail(t, now, br.dev.evaldo.meuscooter.domain.Units.Imperial).altimetry)
        assertEquals("+59 ft", imperial.gain); assertEquals("−39 ft", imperial.loss)
        assertEquals("2434 ft", imperial.min); assertEquals("2493 ft", imperial.max)
        assertEquals("0 mi", imperial.startDistance); assertEquals("1,4 mi", imperial.endDistance)
        assertNull(mapper.detail(t.copy(route = route.map { it.copy(altitudeM = null) }), now).altimetry)
    }

    @Test fun `altitude isolada e terreno plano nao dividem por zero nem inventam ganho`() {
        val p = br.dev.evaldo.meuscooter.domain.GeoPoint(0.0, 0.0, -12.0)
        val t = trip(1, now, 0f, 1, 0f, 0f)
        val single = requireNotNull(mapper.detail(t.copy(route = listOf(p)), now).altimetry)
        assertEquals("Sem leitura", single.gain); assertEquals("Sem leitura", single.loss)
        assertEquals("-12 m", single.min); assertEquals("-12 m", single.max)
        assertEquals(0f, single.segments.single().single().distanceFraction, 0f)
        assertEquals(0.5f, single.segments.single().single().heightFraction, 0f)
        val flat = requireNotNull(mapper.detail(t.copy(route = listOf(p, p)), now).altimetry)
        assertEquals("+0 m", flat.gain); assertEquals("−0 m", flat.loss)
    }

    private val zone = ZoneOffset.UTC
    private val now = ZonedDateTime.of(2026, 11, 3, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val mapper = HistoryUiMapper(zone)

    private fun at(d: Int, m: Int, h: Int, min: Int) = ZonedDateTime.of(2026, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    private fun trip(id: Long, start: Long, km: Float, min: Int, avg: Float, max: Float) =
        Trip(id, start, km, min * 60_000L, avg, max)

    private val trips = listOf(
        trip(1, at(3, 11, 18, 42), 4.8f, 21, 14f, 27f),
        trip(2, at(3, 11, 8, 15), 3.1f, 14, 13f, 25f),
        trip(3, at(2, 11, 19, 3), 7.9f, 32, 15f, 31f),
        trip(4, at(31, 10, 18, 20), 5.2f, 24, 13f, 26f),
        trip(5, at(29, 10, 10, 5), 12.6f, 48, 16f, 34f),
    )

    @Test fun `titulos de data como no protótipo`() {
        val rows = mapper.map(trips, now).rows
        assertEquals(listOf("Hoje, 18:42", "Hoje, 08:15", "Ontem, 19:03", "Sáb 31, 18:20", "Qui 29, 10:05"), rows.map { it.title })
    }

    @Test fun `secoes aparecem so na primeira viagem do grupo`() {
        val rows = mapper.map(trips, now).rows
        assertEquals(listOf("Hoje", null, "Ontem", "Semana passada", null), rows.map { it.section })
    }

    @Test fun `linhas mostram distancia duracao media e maxima`() {
        val r = mapper.map(trips, now).rows.first()
        assertEquals(1L, r.id)
        assertEquals("4,8 km · 21 min", r.headline)
        assertEquals("média 14 km/h · máx 27 km/h", r.subtitle)
    }

    @Test fun `resumo de 30 dias`() {
        val s = mapper.map(trips, now).summary
        assertEquals("5", s.count)
        assertEquals("33,6 km", s.distance)       // 4,8+3,1+7,9+5,2+12,6
        assertEquals("2 h 19", s.time)            // 21+14+32+24+48 = 139 min
    }

    @Test fun `sem viagens mostra estado vazio`() {
        val ui = mapper.map(emptyList(), now)
        assertEquals(true, ui.empty)
        assertEquals(emptyList<HistoryRowUi>(), ui.rows)
    }

    @Test fun `viagem mais antiga que duas semanas vai para Anteriores`() {
        val old = trip(9, at(1, 10, 9, 0), 1f, 5, 12f, 20f)
        assertEquals("Anteriores", mapper.map(listOf(old), now).rows.single().section)
    }

    @Test fun `detalhe traz estatisticas e bateria`() {
        val t = trip(1, at(3, 11, 18, 42), 4.8f, 21, 14f, 27f).copy(avgWhPerKm = 24f, batteryStartPercent = 82, batteryEndPercent = 76)
        val d = mapper.detail(t, now)
        assertEquals("Hoje, 18:42", d.title)
        assertEquals("4,8 km", d.distance); assertEquals("21 min", d.duration)
        assertEquals("14 km/h", d.avg); assertEquals("27 km/h", d.max)
        assertEquals("Sem leitura", d.altitude)
        assertEquals("24 Wh/km", d.consumption)
        assertEquals("82% → 76%", d.battery)
    }

    @Test fun `detalhe sem consumo e sem bateria mostra traco`() {
        val d = mapper.detail(trip(1, at(3, 11, 18, 42), 1f, 5, 12f, 20f), now)
        assertEquals("--", d.consumption)
        assertNull(d.battery)
    }

    /** 4,8 km = 2,98 mi; 14 km/h = 8,70 mph; 27 km/h = 16,78 mph; 33,6 km = 20,88 mi; 24 Wh/km = 38,6 Wh/mi. */
    @Test fun `unidades imperiais na lista, resumo e detalhe`() {
        val imperial = br.dev.evaldo.meuscooter.domain.Units.Imperial
        val ui = mapper.map(trips, now, imperial)
        assertEquals("3,0 mi · 21 min", ui.rows.first().headline)
        assertEquals("média 9 mph · máx 17 mph", ui.rows.first().subtitle)
        assertEquals("20,9 mi", ui.summary.distance)
        val d = mapper.detail(trip(1, at(3, 11, 18, 42), 4.8f, 21, 14f, 27f).copy(avgWhPerKm = 24f), now, imperial)
        assertEquals("3,0 mi", d.distance); assertEquals("9 mph", d.avg); assertEquals("39 Wh/mi", d.consumption)
    }

    @Test fun `viagem com distancia zero formata 0,0 km e media zero sem quebrar`() {
        val zero = trip(8, at(3, 11, 18, 42), 0f, 2, 0f, 15f)
        val ui = mapper.map(listOf(zero), now)
        assertEquals("0,0 km · 2 min", ui.rows.first().headline)
        assertEquals("média 0 km/h · máx 15 km/h", ui.rows.first().subtitle)
        assertEquals("0,0 km", ui.summary.distance)
        val d = mapper.detail(zero, now)
        assertEquals("0,0 km", d.distance)
        assertEquals("0 km/h", d.avg)
        assertEquals("15 km/h", d.max)
        assertEquals("--", d.consumption)
    }

    @Test fun `viagem de menos de 30 s mostra menos de 1 min e 30 s arredonda para 1 min`() {
        val short = trip(7, at(3, 11, 18, 42), 0.1f, 0, 5f, 8f).copy(durationMs = 29_000)
        val ui = mapper.map(listOf(short), now)
        assertEquals("0,1 km · < 1 min", ui.rows.first().headline)
        assertEquals("< 1 min", mapper.detail(short, now).duration)
        assertEquals("1 min", mapper.detail(short.copy(durationMs = 30_000), now).duration)
    }
}
