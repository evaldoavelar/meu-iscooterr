package br.dev.evaldo.meuscooter.ui.home

import br.dev.evaldo.meuscooter.domain.AppDestination
import br.dev.evaldo.meuscooter.protocol.Pin


/** Callbacks da Home. Padrões vazios facilitam testes e previews. */
data class HomeActions(
    val onMainButton: () -> Unit = {},
    /** Toque no chip de conexão: reconecta sem sair da Home. */
    val onConnectionChip: () -> Unit = {},
    /** Opção escolhida no menu do chip (pressionar e segurar). */
    val onConnectionMenu: (br.dev.evaldo.meuscooter.ui.ConnectionMenuAction) -> Unit = {},
    /** PIN confirmado no diálogo (4.4). Quem recebe o apaga depois do uso. */
    val onUnlockPin: (Pin) -> Unit = {},
    /** PIN confirmado com "Acessar com Biometria" marcado: o ViewModel só o guarda se o desbloqueio for CONFIRMADO (F5.2b). */
    val onUnlockPinAndSave: (Pin) -> Unit = {},
    /** Ícone de digital (PIN salvo): o cofre pede a biometria e o ViewModel faz uma tentativa. */
    val onUseSavedPin: () -> Unit = {},
    /** "Esquecer PIN" (link sob o teclado). */
    val onForgetPin: () -> Unit = {},
    val onHeadlight: (Boolean) -> Unit = {},
    val onMotors: (Int) -> Unit = {},
    val onMode: (Int) -> Unit = {},
    val onCruise: (Boolean) -> Unit = {},
    val onZeroStart: (Boolean) -> Unit = {},
    val onLimit: (Int) -> Unit = {},
    val onOpen: (AppDestination) -> Unit = {},
)

/**
 * Estado do cofre do PIN: [available] mostra o checkbox "Acessar com Biometria"; [saved] faz o botão Desbloquear pedir a biometria primeiro
 * (sem teclado) e mostra o ícone de digital no teclado e o link "Esquecer PIN". [keypadRequest] sobe quando a biometria
 * foi cancelada/falhou: a tela abre o teclado.
 */
data class PinVaultUi(val available: Boolean = false, val saved: Boolean = false, val notice: String? = null, val keypadRequest: Int = 0)
