package br.dev.evaldo.meuscooter.domain

/** Destinos de primeiro nível do app (design/ANDROID-MAPA.md). */
enum class AppDestination {
    Home, Telemetry, Settings, History;

    companion object {
        /** Itens do menu ⋮ da Home, na ordem do protótipo. */
        val menu: List<AppDestination> = listOf(Telemetry, Settings, History)
    }
}

/** Pilha de navegação imutável; a Home é a raiz e nunca sai da pilha. */
data class Navigator(val stack: List<AppDestination> = listOf(AppDestination.Home)) {
    val current: AppDestination get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** Nomes para salvar/restaurar o estado (recriação da Activity). */
    val names: List<String> get() = stack.map { it.name }

    fun open(destination: AppDestination): Navigator = when {
        destination == AppDestination.Home -> Navigator()
        destination == current -> this
        else -> Navigator(stack + destination)
    }

    fun back(): Navigator = if (canGoBack) Navigator(stack.dropLast(1)) else this

    companion object {
        fun fromNames(names: List<String>): Navigator {
            val parsed = names.mapNotNull { n -> AppDestination.entries.firstOrNull { it.name == n } }
            return if (parsed.firstOrNull() == AppDestination.Home) Navigator(parsed) else Navigator()
        }
    }
}
