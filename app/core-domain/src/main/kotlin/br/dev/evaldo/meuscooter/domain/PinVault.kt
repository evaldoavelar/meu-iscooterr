package br.dev.evaldo.meuscooter.domain

import br.dev.evaldo.meuscooter.protocol.Pin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Cofre do PIN do patinete (ADR 0006 §5): guarda o PIN cifrado e só o devolve após autenticação do dono (biometria).
 * O PIN nunca aparece em estado de UI, log nem preferência; [Pin.toString] é mascarado. Quem recebe um [Pin] de
 * [load] é responsável por apagá-lo ([Pin.wipe]); [save] NÃO apaga o PIN recebido (quem chama o apaga).
 */
interface PinVault {
    /** O aparelho/OS suporta o cofre (Keystore + biometria cadastrada). `false` esconde as opções na UI. */
    val available: Boolean

    /** Há um PIN guardado. */
    val hasSavedPin: StateFlow<Boolean>

    /** Cifra e guarda [pin] (pede biometria). Só deve ser chamado depois de um desbloqueio CONFIRMADO. */
    suspend fun save(pin: Pin): PinSaveResult

    /** Decifra o PIN guardado (pede biometria). */
    suspend fun load(): PinLoadResult

    /** Apaga o PIN guardado e a chave. Não pede biometria. */
    fun forget()

    /** Sem cofre (padrão em testes/Fake e abaixo da API 28). */
    data object None : PinVault {
        override val available = false
        override val hasSavedPin: StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun save(pin: Pin) = PinSaveResult.Unavailable
        override suspend fun load() = PinLoadResult.Unavailable
        override fun forget() = Unit
    }
}

sealed interface PinSaveResult {
    data object Saved : PinSaveResult
    /** O usuário desistiu da biometria; nada foi guardado. */
    data object Cancelled : PinSaveResult
    data object Unavailable : PinSaveResult
    data object Failed : PinSaveResult
}

sealed interface PinLoadResult {
    /** O chamador apaga o [pin]. */
    class Loaded(val pin: Pin) : PinLoadResult { override fun toString() = "Loaded(****)" }
    data object NotSaved : PinLoadResult
    data object Cancelled : PinLoadResult
    data object Unavailable : PinLoadResult
    data object Failed : PinLoadResult
}
