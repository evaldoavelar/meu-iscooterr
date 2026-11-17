package br.dev.evaldo.meuscooter.domain.backup

/**
 * Armazenamento do histórico que o backup lê e troca (`trips.v1` + `routes/`). A implementação real é `BackupFilesStore` (módulo `data`,
 * sobre `BackupFiles`); nos testes da UI há uma versão simulada. As chamadas bloqueiam (E/S de arquivo): rodar fora da thread principal.
 */
interface BackupStore {
    /** Foto do estado atual (preferências e PIN vêm do chamador). */
    fun snapshot(preferences: Map<String, String>, pin: CharArray?): BackupContents

    /** Troca o histórico pelo conteúdo do backup (tudo ou nada). */
    fun apply(contents: BackupContents): BackupApplyResult
}

sealed interface BackupApplyResult {
    data class Applied(val preferences: Map<String, String>, val tripCount: Int, val routeCount: Int) : BackupApplyResult
    /** Backup recusado antes de qualquer alteração. */
    data object Invalid : BackupApplyResult
    /** Falha de E/S; o estado anterior foi mantido (ou será restaurado por `recover`). */
    data object IoFailure : BackupApplyResult
}
