package br.dev.evaldo.meuscooter.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S1. Fator exato: 1 milha = 1,609344 km → 100 km = 62,1371 mi; 60 km/h = 37,2823 mph. Padrões: design/screens.py (Sistema · km e km/h · reconectar ligado · guardar trajeto ligado). */
class PreferencesTest {
    @Test fun `quilometros para milhas`() {
        assertEquals(62.1371f, Units.Imperial.distance(100f), 0.001f)
        assertEquals(100f, Units.Metric.distance(100f), 0f)
    }

    @Test fun `km por hora para mph`() {
        assertEquals(37.2823f, Units.Imperial.speed(60f), 0.001f)
        assertEquals(60f, Units.Metric.speed(60f), 0f)
    }

    @Test fun `consumo por milha`() {
        assertEquals(38.6243f, Units.Imperial.perDistance(24f), 0.001f)   // 24 Wh/km × 1,609344
        assertEquals(24f, Units.Metric.perDistance(24f), 0f)
    }

    @Test fun `rotulos das unidades`() {
        assertEquals("km", Units.Metric.distanceLabel); assertEquals("km/h", Units.Metric.speedLabel)
        assertEquals("mi", Units.Imperial.distanceLabel); assertEquals("mph", Units.Imperial.speedLabel)
    }

    /** 11 m = 36,09 ft (1 m = 3,28084 ft). */
    @Test fun `altimetria em metros ou pes`() {
        assertEquals(11f, Units.Metric.altitude(11f), 0f); assertEquals("m", Units.Metric.altitudeLabel)
        assertEquals(36.0892f, Units.Imperial.altitude(11f), 0.001f); assertEquals("ft", Units.Imperial.altitudeLabel)
    }

    @Test fun `padroes seguem o prototipo`() {
        val p = Preferences()
        assertEquals(ThemeMode.System, p.theme); assertEquals(Units.Metric, p.units)
        assertTrue(p.autoReconnect); assertTrue(p.saveRoute)
        assertFalse("primeiro uso ainda nao feito", p.onboardingDone)
        assertFalse("Air Lock desmarcado por padrao (= valor que o original enviava; ADR 0011)", p.lockWithPin)
    }

    @Test fun `store aplica e emite as alteracoes`() = runTest {
        val store = InMemoryPreferencesStore()
        store.update { it.copy(theme = ThemeMode.Dark, units = Units.Imperial) }
        val p = store.preferences.first()
        assertEquals(ThemeMode.Dark, p.theme); assertEquals(Units.Imperial, p.units)
    }

    @Test fun `store guarda lockWithPin`() = runTest {
        val store = InMemoryPreferencesStore()
        store.update { it.copy(lockWithPin = true) }
        assertTrue(store.preferences.first().lockWithPin)
    }

    @Test fun `store comeca com os valores iniciais`() = runTest {
        assertEquals(Preferences(autoReconnect = false), InMemoryPreferencesStore(Preferences(autoReconnect = false)).preferences.first())
    }
}
