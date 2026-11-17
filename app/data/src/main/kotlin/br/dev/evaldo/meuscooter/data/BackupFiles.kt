package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.backup.BackupContents
import br.dev.evaldo.meuscooter.domain.backup.BackupPreferences
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Lê/aplica o histórico (`trips.v1` + `routes/`) para o backup `.ixbak` (ADR 0017). [apply] VALIDA tudo antes de tocar no estado atual
 * (parsers de [FileTripRepository] e [FileRouteStore]) e troca em etapas reversíveis: nada é aplicado pela metade; falha no meio restaura o anterior.
 * Preferências e PIN NÃO são gravados aqui: [ApplyResult.Applied.preferences] volta já saneado e o chamador grava (PIN pelo cofre, com biometria).
 * Depois de aplicar, o chamador deve recarregar o repositório (a instância em memória não vê o arquivo novo).
 * [move] é injetável só para teste (simular falha); o rollback usa sempre a movimentação real.
 */
class BackupFiles(
    private val tripsFile: File,
    private val routesDir: File,
    private val move: (File, File) -> Unit = ::atomicMove,
) {
    sealed interface ApplyResult {
        data class Applied(val preferences: Map<String, String>, val tripCount: Int, val routeCount: Int) : ApplyResult
        /** Backup recusado antes de qualquer alteração (viagens ou trajeto inválido). */
        data object Invalid : ApplyResult
        /** Falha de E/S ao trocar; o estado anterior foi restaurado. */
        data object IoFailure : ApplyResult
    }

    /** Foto do estado atual (preferências e PIN vêm do chamador). */
    fun snapshot(preferences: Map<String, String>, pin: CharArray?): BackupContents {
        val routes = routesDir.listFiles { f -> f.isFile && f.name.endsWith(".route") && f.name.removeSuffix(".route").toLongOrNull()?.let { it > 0 } == true }
            .orEmpty().associate { it.name.removeSuffix(".route") to it.readBytes() }
        return BackupContents(preferences, if (tripsFile.isFile) tripsFile.readBytes() else null, routes, pin)
    }

    /** Valida sem alterar nada; devolve (viagens, rotas) ou `null`. Usa um diretório temporário vizinho. */
    fun validate(contents: BackupContents): Pair<Int, Int>? {
        val stage = stageDir()
        return try { if (stageRoutes(contents, stage)) (contents.tripsFile?.let { FileTripRepository.validateStrict(it) } ?: 0) to contents.routes.size else null }
        finally { stage.deleteRecursively() }
    }

    @Synchronized fun apply(contents: BackupContents): ApplyResult {
        val tripCount = contents.tripsFile?.let { FileTripRepository.validateStrict(it) ?: return ApplyResult.Invalid } ?: 0
        val stage = stageDir()
        val oldRoutes = File(routesDir.path + ".bkold")
        val oldTrips = File(tripsFile.path + ".bkold")
        val newTrips = File(tripsFile.path + ".bkstage")
        var routesOut = false; var tripsOut = false; var routesIn = false; var tripsIn = false
        try {
            recoverLeftovers(oldRoutes, oldTrips)
            if (!stageRoutes(contents, stage)) return ApplyResult.Invalid
            contents.tripsFile?.let { newTrips.parentFile?.mkdirs(); newTrips.writeBytes(it) }
            routesDir.absoluteFile.parentFile?.mkdirs()
            if (routesDir.exists()) { move(routesDir, oldRoutes); routesOut = true }
            if (tripsFile.exists()) { move(tripsFile, oldTrips); tripsOut = true }
            move(stage, routesDir); routesIn = true
            if (contents.tripsFile != null) { move(newTrips, tripsFile); tripsIn = true }
        } catch (_: Exception) {
            // rollback com a movimentação real, na ordem inversa
            try {
                if (tripsIn || tripsOut) { tripsFile.delete(); if (oldTrips.exists()) atomicMove(oldTrips, tripsFile) }
                if (routesIn || routesOut) { routesDir.deleteRecursively(); if (oldRoutes.exists()) atomicMove(oldRoutes, routesDir) }
            } catch (_: Exception) { /* melhor esforço; recoverLeftovers tenta de novo na próxima vez */ }
            return ApplyResult.IoFailure
        } finally {
            stage.deleteRecursively(); newTrips.delete()
        }
        oldRoutes.deleteRecursively(); oldTrips.delete()
        return ApplyResult.Applied(BackupPreferences.sanitize(contents.preferences), tripCount, contents.routes.size)
    }

    private fun stageDir() = File(routesDir.path + ".bkstage")

    /** Escreve as rotas em [stage] e valida cada uma com o parser do [FileRouteStore] (rota vazia/ilegível = inválido). */
    private fun stageRoutes(contents: BackupContents, stage: File): Boolean {
        stage.deleteRecursively()
        val store = FileRouteStore(stage)
        for ((id, bytes) in contents.routes) {
            val tripId = id.toLongOrNull()?.takeIf { it > 0 } ?: return false
            File(stage, "$tripId.route").writeBytes(bytes)
            if (store.read(tripId).isEmpty()) return false
        }
        return true
    }

    /** Sobra de uma execução interrompida: se o diretório novo não existe mas o antigo sim, restaura; senão descarta o antigo. */
    private fun recoverLeftovers(oldRoutes: File, oldTrips: File) {
        if (oldRoutes.exists()) { if (routesDir.exists()) oldRoutes.deleteRecursively() else atomicMove(oldRoutes, routesDir) }
        if (oldTrips.exists()) { if (tripsFile.exists()) oldTrips.delete() else atomicMove(oldTrips, tripsFile) }
    }

    private companion object {
        fun atomicMove(from: File, to: File) {
            try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE) } catch (e: IOException) { throw IOException("move") }
        }
    }
}
