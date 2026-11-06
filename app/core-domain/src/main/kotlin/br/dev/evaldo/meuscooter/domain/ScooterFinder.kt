package br.dev.evaldo.meuscooter.domain

/** Patinete achado na varredura (só identidade: nome anunciado e MAC). Nenhuma conexão foi feita. */
data class FoundScooter(val name: String, val address: String)

/** Fronteira do primeiro uso real (F5.2c): suspende até achar um patinete; cancelar interrompe a varredura. Nunca conecta nem escreve. */
interface ScooterFinder {
    suspend fun find(): FoundScooter
}
