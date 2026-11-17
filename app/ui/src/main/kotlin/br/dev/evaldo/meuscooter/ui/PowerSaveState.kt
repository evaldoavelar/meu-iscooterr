package br.dev.evaldo.meuscooter.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fonte do estado de Economia de energia do Android.
 *
 * Produz `true` quando o modo de economia de energia está ativo E o GPS pode ser bloqueado
 * com a tela apagada (condição que faz a rota da viagem se perder; veja `docs/research/parts/S45-passeio-patinete.md`).
 *
 * Em API < 28 basta `PowerManager.isPowerSaveMode`; em API ≥ 28 também requer
 * `locationPowerSaveMode` ∈ {1, 2, 4} (`LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF`,
 * `LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF`, `LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF`).
 */
interface PowerSaveState {
    /** `true` quando a economia de energia compromete o GPS (atualiza via `ACTION_POWER_SAVE_MODE_CHANGED`). */
    val gpsImpaired: StateFlow<Boolean>
}

/** Fake para testes: valor inicial configurável e mutável. */
class FakePowerSaveState(initial: Boolean = false) : PowerSaveState {
    private val _gpsImpaired = MutableStateFlow(initial)
    override val gpsImpaired: StateFlow<Boolean> = _gpsImpaired

    /** Atualiza o valor para simular ligar/desligar a economia de energia. */
    fun set(value: Boolean) { _gpsImpaired.value = value }
}

/** Sempre `false`: usado quando não há monitoramento Android (testes do ViewModel sem fake explícito). */
object NoOpPowerSaveState : PowerSaveState {
    override val gpsImpaired: StateFlow<Boolean> = MutableStateFlow(false)
}
