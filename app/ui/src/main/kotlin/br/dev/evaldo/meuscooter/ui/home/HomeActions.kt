package br.dev.evaldo.meuscooter.ui.home

import br.dev.evaldo.meuscooter.domain.AppDestination
import br.dev.evaldo.meuscooter.protocol.Pin


/** Callbacks da Home. Padrões vazios facilitam testes e previews. */
data class HomeActions(
    val onMainButton: () -> Unit = {},
    /** Toque no chip de conexão: reconecta sem sair da Home. */
    val onConnectionChip: () -> Unit = {},
    /** PIN confirmado no diálogo (4.4). Quem recebe o apaga depois do uso. */
    val onUnlockPin: (Pin) -> Unit = {},
    /** PIN confirmado com "Salvar PIN" marcado: o ViewModel só o guarda se o desbloqueio for CONFIRMADO (F5.2b). */
    val onUnlockPinAndSave: (Pin) -> Unit = {},
    /** "Usar PIN salvo": o cofre pede a biometria e o ViewModel faz uma tentativa. */
    val onUseSavedPin: () -> Unit = {},
    /** "Esquecer PIN salvo". */
    val onForgetPin: () -> Unit = {},
    val onHeadlight: (Boolean) -> Unit = {},
    val onMotors: (Int) -> Unit = {},
    val onMode: (Int) -> Unit = {},
    val onCruise: (Boolean) -> Unit = {},
    val onZeroStart: (Boolean) -> Unit = {},
    val onLimit: (Int) -> Unit = {},
    val onOpen: (AppDestination) -> Unit = {},
)

/** Estado do cofre do PIN para o diálogo: [available] mostra "Salvar PIN"; [saved] mostra "Usar/Esquecer PIN salvo". */
data class PinVaultUi(val available: Boolean = false, val saved: Boolean = false)
