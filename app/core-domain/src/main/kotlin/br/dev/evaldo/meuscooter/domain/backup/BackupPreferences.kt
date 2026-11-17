package br.dev.evaldo.meuscooter.domain.backup

import br.dev.evaldo.meuscooter.domain.KeyValuePreferencesStore
import br.dev.evaldo.meuscooter.domain.KeyValueStorage
import br.dev.evaldo.meuscooter.domain.Preferences
import br.dev.evaldo.meuscooter.domain.PreferencesCodec
import kotlinx.coroutines.runBlocking

/** Preferências do backup: chaves conhecidas, valores validados por [KeyValuePreferencesStore] (inválido → padrão; `scooterAddress` normalizado). */
object BackupPreferences {
    /** Chaves que o app grava (as demais, inclusive desconhecidas, são ignoradas). */
    val KNOWN_KEYS = setOf(
        "theme", "units", "autoReconnect", "saveRoute", "onboardingDone", "lockWithPin", "airLockTouched", "scooterAddress",
        "cruiseWarningAck", "zeroStartWarningAck", "startTripOnOpen", "speedSource",
    )

    fun sanitize(raw: Map<String, String>): Map<String, String> {
        val storage = object : KeyValueStorage {
            val map = LinkedHashMap<String, String>(raw.filterKeys { it in KNOWN_KEYS })
            override fun get(key: String) = map[key]
            override fun putAll(values: Map<String, String>) { map.putAll(values) }
        }
        runBlocking { KeyValuePreferencesStore(storage).update { it } }
        return storage.map
    }

    /** Preferências atuais como entram no backup (só as chaves conhecidas; `restorePromptDone` é do aparelho e não viaja). */
    fun snapshot(p: Preferences): Map<String, String> = PreferencesCodec.write(p).filterKeys { it in KNOWN_KEYS }

    /** Preferências saneadas de um backup, como objeto (inválido → padrão). */
    fun toPreferences(raw: Map<String, String>): Preferences = sanitize(raw).let { m -> PreferencesCodec.read(m::get) }
}
