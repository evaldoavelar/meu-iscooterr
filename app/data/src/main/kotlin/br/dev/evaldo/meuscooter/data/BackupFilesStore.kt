package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.backup.BackupApplyResult
import br.dev.evaldo.meuscooter.domain.backup.BackupContents
import br.dev.evaldo.meuscooter.domain.backup.BackupStore

/** Adaptador de [BackupFiles] para a interface de domínio [BackupStore] (usado pela UI sem depender do módulo `data`). */
class BackupFilesStore(private val files: BackupFiles) : BackupStore {
    override fun snapshot(preferences: Map<String, String>, pin: CharArray?): BackupContents = files.snapshot(preferences, pin)

    override fun apply(contents: BackupContents): BackupApplyResult = when (val r = files.apply(contents)) {
        is BackupFiles.ApplyResult.Applied -> BackupApplyResult.Applied(r.preferences, r.tripCount, r.routeCount)
        BackupFiles.ApplyResult.Invalid -> BackupApplyResult.Invalid
        BackupFiles.ApplyResult.IoFailure -> BackupApplyResult.IoFailure
    }
}
