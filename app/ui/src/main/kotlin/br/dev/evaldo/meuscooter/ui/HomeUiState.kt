package br.dev.evaldo.meuscooter.ui

enum class MainButtonKind { Search, Connecting, Unlock, Lock, Moving, Pending, Confirmed, Retry }

data class MainButtonUi(
    val kind: MainButtonKind,
    val label: String,
    val enabled: Boolean,
    val disabledReason: String? = null,
)

enum class ControlStatus { Idle, Pending, Confirmed, Failed, TimedOut }

/** Estado comum de um controle: habilitação, motivo (sem jargão) e resultado da última operação. */
data class ControlUi(
    val status: ControlStatus,
    val enabled: Boolean,
    val disabledReason: String? = null,
    val message: String? = null,
)

data class ToggleUi(val checked: Boolean, val control: ControlUi)

data class ChoiceUi(val options: List<ChoiceOption>, val selectedRaw: Int?, val control: ControlUi)

data class ChoiceOption(val raw: Int, val label: String)

data class LimitUi(val kmh: Int, val control: ControlUi) {
    companion object {
        val RANGE = 20..50
    }
}

enum class ConnectionKind { Disconnected, Connecting, Connected, Error }

data class ConnectionUi(val kind: ConnectionKind, val label: String)

data class HomeUiState(
    val connection: ConnectionUi,
    val battery: String,
    val speed: String,
    val total: String,
    val mainButton: MainButtonUi,
    val headlight: ToggleUi,
    val motors: ChoiceUi,
    val mode: ChoiceUi,
    val cruise: ToggleUi,
    val zeroStart: ToggleUi,
    val limit: LimitUi,
    val telemetryStale: Boolean,
)
