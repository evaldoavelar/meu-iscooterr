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
    /** Primeiro uso concluído. "Esquecer patinete" volta a `false`. */
    val onboardingDone: Boolean = false,
    /** Só preferência guardada: ainda sem efeito no patinete (bloqueio com PIN está bloqueado no protocolo). */
    val lockWithPin: Boolean = false,
    /** MAC do patinete cadastrado (ADR 0006), em maiúsculas; `null` = sem cadastro (transporte simulado). Nunca vai a log/estado de UI. */
    val scooterAddress: String? = null,
) {
    companion object {
        private val MAC = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        /** Normaliza um MAC (maiúsculas) ou devolve `null` se não for um endereço válido. */
        fun normalizeAddress(raw: String?): String? = raw?.trim()?.takeIf { MAC.matches(it) }?.uppercase()
    }
}

/** Armazenamento das preferências (atrás desta interface: memória, ou chave-valor persistente no Android). */
interface PreferencesStore {
    /** Valor atual, síncrono: evita mostrar os padrões (ex.: "Vamos conectar") antes de o fluxo emitir. */
    val current: Preferences
    val preferences: Flow<Preferences>
    suspend fun update(transform: (Preferences) -> Preferences)
}

class InMemoryPreferencesStore(initial: Preferences = Preferences()) : PreferencesStore {
    private val state = MutableStateFlow(initial)
    override val current: Preferences get() = state.value
    override val preferences: Flow<Preferences> = state
    override suspend fun update(transform: (Preferences) -> Preferences) { state.update(transform) }
}

/** Texto chave-valor persistente (no Android: SharedPreferences). `putAll` grava tudo de uma vez. */
interface KeyValueStorage {
    fun get(key: String): String?
    fun putAll(values: Map<String, String>)
}

/** Preferências sobre [KeyValueStorage]. Valor ausente ou inválido cai no padrão do campo, nunca lança. */
class KeyValuePreferencesStore(private val storage: KeyValueStorage) : PreferencesStore {
    private val state = MutableStateFlow(read())
    override val current: Preferences get() = state.value
    override val preferences: Flow<Preferences> = state

    override suspend fun update(transform: (Preferences) -> Preferences) {
        synchronized(this) {
            val next = transform(state.value)
            storage.putAll(write(next))
            state.value = next
        }
    }

    private fun read(): Preferences {
        val d = Preferences()
        fun bool(key: String, default: Boolean) = storage.get(key)?.toBooleanStrictOrNull() ?: default
        return Preferences(
            theme = storage.get("theme")?.let { n -> ThemeMode.entries.firstOrNull { it.name == n } } ?: d.theme,
            units = storage.get("units")?.let { n -> Units.entries.firstOrNull { it.name == n } } ?: d.units,
            autoReconnect = bool("autoReconnect", d.autoReconnect),
            saveRoute = bool("saveRoute", d.saveRoute),
            onboardingDone = bool("onboardingDone", d.onboardingDone),
            lockWithPin = bool("lockWithPin", d.lockWithPin),
            scooterAddress = Preferences.normalizeAddress(storage.get("scooterAddress")),
        )
    }

    private fun write(p: Preferences) = mapOf(
        "theme" to p.theme.name, "units" to p.units.name, "autoReconnect" to p.autoReconnect.toString(),
        "saveRoute" to p.saveRoute.toString(), "onboardingDone" to p.onboardingDone.toString(), "lockWithPin" to p.lockWithPin.toString(),
        // putAll só grava: "" = sem cadastro (a leitura trata como nulo).
        "scooterAddress" to (Preferences.normalizeAddress(p.scooterAddress) ?: ""),
    )
}
