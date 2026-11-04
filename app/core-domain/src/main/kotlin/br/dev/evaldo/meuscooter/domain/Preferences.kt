package br.dev.evaldo.meuscooter.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class ThemeMode { System, Light, Dark }

/** Unidades de exibição. Comandos e domínio continuam em km/km/h; só a apresentação converte. */
enum class Units(val distanceLabel: String, val speedLabel: String, private val factor: Float) {
    Metric("km", "km/h", 1f),
    Imperial("mi", "mph", 1f / 1.609344f);

    fun distance(km: Float) = km * factor
    fun speed(kmh: Float) = kmh * factor

    /** Consumo: Wh por km → Wh por [distanceLabel]. */
    fun perDistance(whPerKm: Float) = whPerKm / factor
}

data class Preferences(
    val theme: ThemeMode = ThemeMode.System,
    val units: Units = Units.Metric,
    val autoReconnect: Boolean = true,
    val saveRoute: Boolean = true,
)

/** Armazenamento das preferências; a implementação com DataStore vem na Fase 4 (atrás desta interface). */
interface PreferencesStore {
    val preferences: Flow<Preferences>
    suspend fun update(transform: (Preferences) -> Preferences)
}

class InMemoryPreferencesStore(initial: Preferences = Preferences()) : PreferencesStore {
    private val state = MutableStateFlow(initial)
    override val preferences: Flow<Preferences> = state
    override suspend fun update(transform: (Preferences) -> Preferences) { state.update(transform) }
}
