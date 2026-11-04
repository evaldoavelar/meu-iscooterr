package br.dev.evaldo.meuscooter.domain

/** Etapas do primeiro uso (design/screens.py `primeiro_uso`). [Ready] = "Começar" tocado; [Cancelled] = "Cancelar". */
enum class OnboardingStep { Searching, Found, Connected, Ready, Cancelled }

/**
 * Fluxo imutável do primeiro uso. Transições fora de ordem são ignoradas (devolvem o mesmo fluxo).
 * [failed]: a última tentativa de conexão expirou. O app só conecta depois que o usuário confirma "Conectar a este patinete" ([confirmed]).
 */
data class OnboardingFlow(val step: OnboardingStep = OnboardingStep.Searching, val confirmed: Boolean = false, val failed: Boolean = false) {
    fun scooterFound(): OnboardingFlow = if (step == OnboardingStep.Searching) copy(step = OnboardingStep.Found) else this
    fun confirm(): OnboardingFlow = if (step == OnboardingStep.Found) copy(confirmed = true, failed = false) else this
    fun connectionEstablished(): OnboardingFlow = if (step == OnboardingStep.Found && confirmed) copy(step = OnboardingStep.Connected) else this
    /** A conexão não completou no prazo: volta a "Encontrado" com [failed], para tentar de novo ou cancelar. */
    fun connectionFailed(): OnboardingFlow = if (step == OnboardingStep.Found && confirmed) copy(confirmed = false, failed = true) else this
    fun finish(): OnboardingFlow = if (step == OnboardingStep.Connected) copy(step = OnboardingStep.Ready) else this

    /** Só enquanto procura ou antes de confirmar; depois de confirmar a conexão segue até o fim. */
    fun cancel(): OnboardingFlow = if (step == OnboardingStep.Searching || (step == OnboardingStep.Found && !confirmed)) copy(step = OnboardingStep.Cancelled) else this
}
