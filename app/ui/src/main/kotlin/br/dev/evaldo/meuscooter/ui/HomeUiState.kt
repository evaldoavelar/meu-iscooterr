package br.dev.evaldo.meuscooter.ui

enum class MainButtonKind { Search, Connecting, Unlock, Lock, Moving, Pending, Confirmed, Retry }

data class MainButtonUi(
    val kind: MainButtonKind,
    val label: String,
    val enabled: Boolean,
    val disabledReason: String? = null,
    /** Desbloqueio com PIN (4.4): o toque abre o diálogo de PIN na tela, não chama `onMainButton`. */
    val asksPin: Boolean = false,
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

/**
 * Limite de velocidade. A barra vai de [minKmh] até [CEILING] (60); valores acima de [maxKmh] (o máximo informado pelo
 * patinete: 50 com 1 motor, 60 com 2) ficam desabilitados e [hint] explica. [scooterMph]: o patinete está em mph, então o
 * valor cru (b12) é mostrado como "mph" sem conversão (HIPÓTESE; ADR 0009) e o controle fica desabilitado.
 */
data class LimitUi(
    val kmh: Int,
    val control: ControlUi,
    val units: br.dev.evaldo.meuscooter.domain.Units = br.dev.evaldo.meuscooter.domain.Units.Metric,
    val minKmh: Int = 20,
    val maxKmh: Int = 50,
    val hint: String? = null,
    val scooterMph: Boolean = false,
) {
    /** Rótulos na unidade escolhida; [kmh] (valor do slider e do comando) continua em km/h. */
    fun valueText(kmh: Int) = when {
        scooterMph -> "$kmh mph"
        units == br.dev.evaldo.meuscooter.domain.Units.Metric -> "$kmh km/h"
        else -> "${label(kmh)} ($kmh km/h)"
    }
    val minText get() = if (scooterMph) "" else label(minKmh)
    val maxText get() = if (scooterMph) "" else label(CEILING)
    private fun label(kmh: Int) = "${Math.round(units.speed(kmh.toFloat()))} ${units.speedLabel}"
    companion object {
        const val CEILING = br.dev.evaldo.meuscooter.domain.HomeRules.LIMIT_CEILING_KMH
        val RANGE = 20..CEILING
    }
}

enum class ConnectionKind { Disconnected, Connecting, Connected, Error }

/** Ação única do chip de conexão (pressionar e segurar), conforme o estado; confirmada por diálogo Sim/Não. */
enum class ConnectionMenuAction(val label: String, val question: String) {
    Connect("Conectar patinete", "Deseja conectar o patinete?"),
    Disconnect("Desconectar patinete", "Deseja desconectar o patinete?"),
    Cancel("Cancelar conexão", "Deseja cancelar a conexão?");
    companion object {
        fun forState(kind: ConnectionKind) = when (kind) {
            ConnectionKind.Connected -> Disconnect
            ConnectionKind.Connecting -> Cancel
            ConnectionKind.Disconnected, ConnectionKind.Error -> Connect
        }
    }
}

data class ConnectionUi(val kind: ConnectionKind, val label: String)

data class HomeUiState(
    val connection: ConnectionUi,
    val battery: String,
    val speed: String,
    val total: String,
    val mainButton: MainButtonUi,
    val headlight: ToggleUi,
    val motors: ChoiceUi,
    /** Unidade do patinete (km/h ↔ mph); `null` = o transporte não a oferece. */
    val unit: ChoiceUi? = null,
    val mode: ChoiceUi,
    val cruise: ToggleUi,
    val zeroStart: ToggleUi,
    val limit: LimitUi,
    val telemetryStale: Boolean,
    /** Motivo (sem jargão) de os controles restritos estarem desabilitados; independe de operações pendentes. */
    val notice: String? = null,
)
