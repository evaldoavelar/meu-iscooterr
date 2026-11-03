package br.dev.evaldo.meuscooter.domain

/** Destinos de primeiro nível do app (design/ANDROID-MAPA.md). */
enum class AppDestination {
    Home, Telemetry, Settings, History;

    companion object {
        /** Itens do menu ⋮ da Home, na ordem do protótipo. */
        val menu: List<AppDestination> = listOf(Telemetry, Settings, History)
    }
}

/** Pilha de navegação simples; a Home é a raiz e nunca sai da pilha. */
class Navigator {
    private val stack = ArrayDeque(listOf(AppDestination.Home))

    val current: AppDestination get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun open(destination: AppDestination) {
        if (destination != current) stack.addLast(destination)
    }

    /** Retorna `true` se voltou; `false` se já estava na Home. */
    fun back(): Boolean {
        if (!canGoBack) return false
        stack.removeLast()
        return true
    }
}
