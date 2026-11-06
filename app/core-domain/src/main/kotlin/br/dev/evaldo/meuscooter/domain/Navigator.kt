package br.dev.evaldo.meuscooter.domain

/** Destinos de primeiro nível do app (design/ANDROID-MAPA.md). */
enum class AppDestination {
    Home, Telemetry, Settings, History, TripDetail, Recovery, EventLog;

    companion object {
        /** Itens do menu ⋮ da Home, na ordem do protótipo. */
        val menu: List<AppDestination> = listOf(Telemetry, Settings, History, EventLog)
    }
}

/** Pilha de navegação imutável; a Home é a raiz e nunca sai da pilha. */
data class Navigator(val stack: List<AppDestination> = listOf(AppDestination.Home), val tripId: Long? = null) {
    val current: AppDestination get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** Nomes para salvar/restaurar o estado (recriação da Activity). */
    val names: List<String> get() = stack.map { it.name } + listOfNotNull(tripId?.let { "id=$it" })

    fun open(destination: AppDestination): Navigator = when {
        destination == AppDestination.Home -> Navigator()
        destination == current -> this
        else -> Navigator(stack + destination, tripId)
    }

    /** Abre o detalhe da viagem [id] (a partir do Histórico). */
    fun openTrip(id: Long): Navigator = Navigator(stack + AppDestination.TripDetail, id)

    fun back(): Navigator = if (canGoBack) Navigator(stack.dropLast(1), if (current == AppDestination.TripDetail) null else tripId) else this

    companion object {
        fun fromNames(names: List<String>): Navigator {
            val parsed = names.mapNotNull { n -> AppDestination.entries.firstOrNull { it.name == n } }
            val id = names.firstNotNullOfOrNull { it.removePrefix("id=").takeIf { _ -> it.startsWith("id=") }?.toLongOrNull() }
            val valid = parsed.firstOrNull() == AppDestination.Home && (AppDestination.TripDetail !in parsed || id != null)
            return if (valid) Navigator(parsed, id) else Navigator()
        }
    }
}
