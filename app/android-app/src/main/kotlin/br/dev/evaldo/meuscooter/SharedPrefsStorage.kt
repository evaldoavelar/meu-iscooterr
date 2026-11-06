package br.dev.evaldo.meuscooter

import android.content.Context
import br.dev.evaldo.meuscooter.domain.KeyValueStorage

/** Adaptador fino de [KeyValueStorage] sobre SharedPreferences (a lógica e os testes ficam em `core-domain`). */
class SharedPrefsStorage(context: Context, name: String = "preferences") : KeyValueStorage {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun putAll(values: Map<String, String>) {
        prefs.edit().apply { values.forEach { (k, v) -> putString(k, v) } }.apply()
    }
}
