package br.dev.evaldo.meuscooter.ui

import br.dev.evaldo.meuscooter.domain.RecoveryReason

enum class StatusIconKind { Scooter, Bluetooth, Alert, Spin }

/** Cor do selo e do ícone (design/screens.py `status`: pending / error / primary). */
enum class StatusTone { Pending, Error, Primary }

data class StatusUi(
    val icon: StatusIconKind,
    val tone: StatusTone,
    val title: String,
    val text: String,
    val primaryLabel: String,
    val primaryTonal: Boolean = false,
    val secondaryLabel: String? = null,
)

/** Textos da recuperação, sem jargão (design/screens.py `status`). */
object RecoveryUiMapper {
    fun map(reason: RecoveryReason): StatusUi = when (reason) {
        RecoveryReason.OutOfRange -> StatusUi(
            StatusIconKind.Scooter, StatusTone.Pending, "Patinete não encontrado",
            "Ele pode estar desligado ou longe. Ligue e chegue perto: o app reconecta sozinho.", "Tentar agora",
        )
        RecoveryReason.BluetoothOff -> StatusUi(
            StatusIconKind.Bluetooth, StatusTone.Pending, "Bluetooth desligado",
            "O app precisa do Bluetooth para falar com o patinete.", "Ativar Bluetooth",
        )
        RecoveryReason.PermissionDenied -> StatusUi(
            StatusIconKind.Alert, StatusTone.Error, "Falta uma permissão",
            "O Android pede acesso a “Dispositivos próximos” para achar o patinete. Usamos só para isso.", "Permitir",
            secondaryLabel = "Abrir configurações do Android",
        )
        is RecoveryReason.Reconnecting -> StatusUi(
            StatusIconKind.Spin, StatusTone.Primary, "Reconectando…",
            "${reason.attemptLabel}. Mantenha o patinete ligado e por perto.", "Cancelar", primaryTonal = true,
        )
    }
}
