package br.dev.evaldo.meuscooter.data

/** Chave de depuração: o BLE real fica DESLIGADO por padrão; o `FakeScooterTransport` continua sendo o transporte normal. */
object RealBleSelection {
    /** Resultado de [resolve]: [real] liga o BLE; [address] é o MAC a usar; [registerExtra] = gravar o MAC dos extras como cadastro. */
    data class Selection(val real: Boolean, val address: String?, val registerExtra: Boolean)

    /**
     * ADR 0006 (F5.1/F5.2): extras de depuração (build depurável + `ble_real` + MAC válido) valem e CADASTRAM o MAC;
     * senão, patinete cadastrado → BLE real sem extras; senão, Fake. `ble_tx` continua separado ([txEnabled]).
     */
    fun resolve(debuggable: Boolean, requestedReal: Boolean, extraAddress: String?, registeredAddress: String?): Selection {
        val extra = if (enabled(debuggable, requestedReal, extraAddress)) normalize(extraAddress) else null
        val saved = normalize(registeredAddress)
        return when {
            extra != null -> Selection(real = true, address = extra, registerExtra = extra != saved)
            saved != null -> Selection(real = true, address = saved, registerExtra = false)
            else -> Selection(real = false, address = null, registerExtra = false)
        }
    }

    private fun normalize(a: String?) = a?.trim()?.takeIf { GattProperties.isValidAddress(it) }?.uppercase()

    fun enabled(debuggable: Boolean, requested: Boolean, address: String?): Boolean =
        debuggable && requested && !address.isNullOrBlank()

    /**
     * Modo TX (CONNECT 0x30 + heartbeat 0x10, Unlock, Lock modo 2; nada além): vale com o BLE real ligado ([realBle]) e
     * (a) patinete já cadastrado ([registered], decisão do proprietário 2026-11-06, ADR 0006) ou (b) build depurável com a
     * chave `ble_tx` ([requestedTx]). Sem BLE real nunca há TX.
     */
    fun txEnabled(debuggable: Boolean, realBle: Boolean, requestedTx: Boolean, registered: Boolean): Boolean =
        realBle && (registered || (debuggable && requestedTx))
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
