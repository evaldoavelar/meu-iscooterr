package br.dev.evaldo.meuscooter.ui.home

import br.dev.evaldo.meuscooter.domain.AppDestination
import br.dev.evaldo.meuscooter.protocol.Pin


/** Callbacks da Home. Padrões vazios facilitam testes e previews. */
data class HomeActions(
    val onMainButton: () -> Unit = {},
    /** PIN confirmado no diálogo (4.4). Quem recebe o apaga depois do uso. */
    val onUnlockPin: (Pin) -> Unit = {},
    val onHeadlight: (Boolean) -> Unit = {},
    val onMotors: (Int) -> Unit = {},
    val onMode: (Int) -> Unit = {},
    val onCruise: (Boolean) -> Unit = {},
    val onZeroStart: (Boolean) -> Unit = {},
    val onLimit: (Int) -> Unit = {},
    val onOpen: (AppDestination) -> Unit = {},
)
