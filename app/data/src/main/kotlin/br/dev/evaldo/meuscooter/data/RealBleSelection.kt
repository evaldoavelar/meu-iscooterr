package br.dev.evaldo.meuscooter.data

/** Chave de depuração: o BLE real fica DESLIGADO por padrão; o `FakeScooterTransport` continua sendo o transporte normal. */
object RealBleSelection {
    fun enabled(debuggable: Boolean, requested: Boolean, address: String?): Boolean =
        debuggable && requested && !address.isNullOrBlank()

    /**
     * Modo TX da etapa 4.3a (CONNECT 0x30 + heartbeat 0x10): chave `ble_tx` SEPARADA e desligada por padrão. Exige build
     * depurável e o BLE real já ligado ([realBle] = resultado de [enabled]).
     */
    fun txEnabled(debuggable: Boolean, realBle: Boolean, requestedTx: Boolean): Boolean = debuggable && realBle && requestedTx
}

enum class CccdMode { Notify, Indicate }

/** Tipo de escrita em FF62. Qual o app original usa é `[DESCONHECIDO]` (o log s4-3 só mostra `status=0`). */
enum class WriteMode { WithResponse, WithoutResponse }

/** Lógica pura usada pela implementação Android (que não é coberta por testes de JVM). */
object GattProperties {
    private const val PROPERTY_NOTIFY = 0x10
    private const val PROPERTY_INDICATE = 0x20
    private const val PROPERTY_WRITE_NO_RESPONSE = 0x04
    private const val PROPERTY_WRITE = 0x08
    private val ADDRESS = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

    /** Valor a gravar no CCCD conforme as propriedades da característica; `null` = não suporta notificação. */
    fun cccdMode(properties: Int): CccdMode? = when {
        properties and PROPERTY_NOTIFY != 0 -> CccdMode.Notify
        properties and PROPERTY_INDICATE != 0 -> CccdMode.Indicate
        else -> null
    }

    /** Preferir escrita COM resposta (o stack confirma); sem nenhuma das duas, a característica não aceita escrita (`null`). */
    fun writeMode(properties: Int): WriteMode? = when {
        properties and PROPERTY_WRITE != 0 -> WriteMode.WithResponse
        properties and PROPERTY_WRITE_NO_RESPONSE != 0 -> WriteMode.WithoutResponse
        else -> null
    }

    fun isValidAddress(address: String) = ADDRESS.matches(address)
}
