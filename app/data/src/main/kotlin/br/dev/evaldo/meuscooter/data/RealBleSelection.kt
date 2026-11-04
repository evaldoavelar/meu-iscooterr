package br.dev.evaldo.meuscooter.data

/** Chave de depuração: o BLE real fica DESLIGADO por padrão; o `FakeScooterTransport` continua sendo o transporte normal. */
object RealBleSelection {
    fun enabled(debuggable: Boolean, requested: Boolean, address: String?): Boolean =
        debuggable && requested && !address.isNullOrBlank()
}

enum class CccdMode { Notify, Indicate }

/** Lógica pura usada pela implementação Android (que não é coberta por testes de JVM). */
object GattProperties {
    private const val PROPERTY_NOTIFY = 0x10
    private const val PROPERTY_INDICATE = 0x20
    private val ADDRESS = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    /** Valor a gravar no CCCD conforme as propriedades da característica; `null` = não suporta notificação. */
    fun cccdMode(properties: Int): CccdMode? = when {
        properties and PROPERTY_NOTIFY != 0 -> CccdMode.Notify
        properties and PROPERTY_INDICATE != 0 -> CccdMode.Indicate
        else -> null
    }

    fun isValidAddress(address: String) = ADDRESS.matches(address)
}
