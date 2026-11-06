package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.PinLoadResult
import br.dev.evaldo.meuscooter.domain.PinSaveResult
import br.dev.evaldo.meuscooter.domain.PinVault
import br.dev.evaldo.meuscooter.protocol.Pin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Cofre simulado em memória (testes/depuração): sem criptografia, só para exercitar os fluxos. */
class FakePinVault(
    override val available: Boolean = true,
    /** Resposta da "biometria" simulada: `false` = o usuário cancela. */
    var authenticates: Boolean = true,
) : PinVault {
    private var stored: Pin? = null
    private val _has = MutableStateFlow(false)
    override val hasSavedPin: StateFlow<Boolean> = _has.asStateFlow()
    var saves = 0; private set
    var loads = 0; private set

    override suspend fun save(pin: Pin): PinSaveResult {
        if (!available) return PinSaveResult.Unavailable
        if (!authenticates) return PinSaveResult.Cancelled
        saves++
        stored?.wipe()
        stored = pin.duplicate()
        _has.value = true
        return PinSaveResult.Saved
    }

    override suspend fun load(): PinLoadResult {
        if (!available) return PinLoadResult.Unavailable
        val s = stored ?: return PinLoadResult.NotSaved
        if (!authenticates) return PinLoadResult.Cancelled
        loads++
        return PinLoadResult.Loaded(s.duplicate())
    }

    override fun forget() { stored?.wipe(); stored = null; _has.value = false }
}
