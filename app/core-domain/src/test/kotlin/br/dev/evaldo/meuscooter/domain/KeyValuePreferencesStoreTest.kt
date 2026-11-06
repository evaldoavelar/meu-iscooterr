package br.dev.evaldo.meuscooter.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyValuePreferencesStoreTest {
    private class MapStorage(val map: MutableMap<String, String> = mutableMapOf()) : KeyValueStorage {
        override fun get(key: String): String? = map[key]
        override fun putAll(values: Map<String, String>) { map += values }
    }

    @Test fun `armazenamento vazio entrega os padroes`() = runTest {
        val store = KeyValuePreferencesStore(MapStorage())
        assertEquals(Preferences(), store.current)
        assertEquals(Preferences(), store.preferences.first())
    }

    @Test fun `update persiste todos os campos e outra instancia le o mesmo valor`() = runTest {
        val storage = MapStorage()
        val wanted = Preferences(ThemeMode.Dark, Units.Imperial, autoReconnect = false, saveRoute = false, onboardingDone = true, lockWithPin = true, scooterAddress = "AA:BB:CC:DD:EE:FF")
        KeyValuePreferencesStore(storage).update { wanted }
        assertEquals(wanted, KeyValuePreferencesStore(storage).current)
    }

    @Test fun `current ja reflete o persistido sem coletar o fluxo`() {
        val storage = MapStorage(mutableMapOf("onboardingDone" to "true"))
        assertEquals(true, KeyValuePreferencesStore(storage).current.onboardingDone)
    }

    @Test fun `update emite o novo valor no fluxo`() = runTest {
        val store = KeyValuePreferencesStore(MapStorage())
        store.update { it.copy(theme = ThemeMode.Light) }
        assertEquals(ThemeMode.Light, store.preferences.first().theme)
    }

    @Test fun `valor desconhecido ou corrompido volta ao padrao do campo`() {
        val storage = MapStorage(mutableMapOf("theme" to "Roxo", "units" to "", "autoReconnect" to "talvez", "onboardingDone" to "true"))
        val p = KeyValuePreferencesStore(storage).current
        assertEquals(ThemeMode.System, p.theme)
        assertEquals(Units.Metric, p.units)
        assertEquals(true, p.autoReconnect)
        assertEquals(true, p.onboardingDone)
    }

    @Test fun `sem cadastro o endereco e nulo e esquecer apaga o cadastro`() = runTest {
        val storage = MapStorage()
        val store = KeyValuePreferencesStore(storage)
        assertEquals(null, store.current.scooterAddress)
        store.update { it.copy(scooterAddress = "AA:BB:CC:DD:EE:FF") }
        store.update { it.copy(scooterAddress = null) }
        assertEquals(null, KeyValuePreferencesStore(storage).current.scooterAddress)
    }

    @Test fun `endereco corrompido na leitura vira sem cadastro`() {
        for (bad in listOf("", "  ", "lixo", "AA:BB:CC:DD:EE", "GG:BB:CC:DD:EE:FF")) {
            assertEquals(bad, null, KeyValuePreferencesStore(MapStorage(mutableMapOf("scooterAddress" to bad))).current.scooterAddress)
        }
    }

    @Test fun `endereco e normalizado para maiusculas`() {
        assertEquals("AA:BB:CC:DD:EE:FF", KeyValuePreferencesStore(MapStorage(mutableMapOf("scooterAddress" to "aa:bb:cc:dd:ee:ff"))).current.scooterAddress)
    }
}
