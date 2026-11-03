package br.dev.evaldo.meuscooter.ui.home

import br.dev.evaldo.meuscooter.domain.AppDestination


/** Callbacks da Home. Padrões vazios facilitam testes e previews. */
data class HomeActions(
    val onMainButton: () -> Unit = {},
    val onHeadlight: (Boolean) -> Unit = {},
    val onMotors: (Int) -> Unit = {},
    val onMode: (Int) -> Unit = {},
    val onCruise: (Boolean) -> Unit = {},
    val onZeroStart: (Boolean) -> Unit = {},
    val onLimit: (Int) -> Unit = {},
    val onOpen: (AppDestination) -> Unit = {},
)
