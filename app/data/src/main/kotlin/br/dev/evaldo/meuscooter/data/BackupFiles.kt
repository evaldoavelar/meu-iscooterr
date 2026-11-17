package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.backup.BackupContents
import br.dev.evaldo.meuscooter.domain.backup.BackupPreferences
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Lê/aplica o histórico (`trips.v1` + `routes/`) para o backup `.ixbak` (ADR 0017). [apply] VALIDA tudo antes de tocar no estado atual
 * (parsers de [FileTripRepository] e [FileRouteStore]) e troca em etapas por `rename`, protegidas por um marcador de commit
 * (`trips.v1.bkcommit`) criado DEPOIS de todo o stage validado e ANTES da primeira troca; só é apagado no fim, depois de limpar os `.bkold`.
 *
 * Contrato do chamador:
 * - Chamar [recover] na inicialização do app, ANTES de abrir o [FileTripRepository]/[FileRouteStore] (é idempotente e barato).
 *   Com marcador, completa a troca (se rotas e viagens novas já estão no lugar) ou a desfaz por inteiro: nunca estado misto.
 * - [apply] não pode rodar durante gravação de viagem/rota (viagem ativa) e, depois dele, o [FileTripRepository] vivo está desatualizado: recarregar.
 * - Backup sem `trips.v1` SUBSTITUI (apaga) o histórico atual; a UI confirma antes.
 * - Preferências e PIN não são gravados aqui: [ApplyResult.Applied.preferences] volta saneado; o PIN vai pelo cofre (biometria).
 * Sem fsync (mesmo padrão do [FileTripRepository]). [move] é injetável só para teste; o rollback usa [rollbackMove].
 */
class BackupFiles(
    private val tripsFile: File,
    private val routesDir: File,
    private val move: (File, File) -> Unit = ::atomicMove,
    private val rollbackMove: (File, File) -> Unit = ::atomicMove,
) {
    sealed interface ApplyResult {
        data class Applied(val preferences: Map<String, String>, val tripCount: Int, val routeCount: Int) : ApplyResult
        /** Backup recusado antes de qualquer alteração (viagens ou trajeto inválido). */
        data object Invalid : ApplyResult
        /** Falha de E/S ao trocar; o estado anterior foi restaurado (ou [recover] o restaurará). */
        data object IoFailure : ApplyResult
    }

    private val stage get() = File(routesDir.path + ".bkstage")
    private val check get() = File(routesDir.path + ".bkcheck")
    private val oldRoutes get() = File(routesDir.path + ".bkold")
    private val oldTrips get() = File(tripsFile.path + ".bkold")
    private val newTrips get() = File(tripsFile.path + ".bkstage")
    private val marker get() = File(tripsFile.path + ".bkcommit")

    /** Foto do estado atual (preferências e PIN vêm do chamador). */
    fun snapshot(preferences: Map<String, String>, pin: CharArray?): BackupContents {
        val routes = routesDir.listFiles { f -> f.isFile && f.name.endsWith(".route") && canonicalId(f.name.removeSuffix(".route")) }
            .orEmpty().associate { it.name.removeSuffix(".route") to it.readBytes() }
        return BackupContents(preferences, if (tripsFile.isFile) tripsFile.readBytes() else null, routes, pin)
    }

    /** Valida sem alterar nada; devolve (viagens, rotas) ou `null` se qualquer parte for inválida. */
    @Synchronized fun validate(contents: BackupContents): Pair<Int, Int>? {
        val trips = contents.tripsFile?.let { FileTripRepository.validateStrict(it) ?: return null } ?: 0
        val dir = check
        return try { if (stageRoutes(contents, dir)) trips to contents.routes.size else null } finally { dir.deleteRecursively() }
    }

    @Synchronized fun apply(contents: BackupContents): ApplyResult {
        val tripCount = contents.tripsFile?.let { FileTripRepository.validateStrict(it) ?: return ApplyResult.Invalid } ?: 0
        try {
            recoverLocked(rollbackMove)
            if (!stageRoutes(contents, stage)) { stage.deleteRecursively(); return ApplyResult.Invalid }
            contents.tripsFile?.let { newTrips.parentFile?.mkdirs(); newTrips.writeBytes(it) }
            routesDir.absoluteFile.parentFile?.mkdirs()
            val tmp = File(marker.path + ".tmp")
            tmp.writeText("newTrips=${if (contents.tripsFile != null) 1 else 0}\n")
            atomicMove(tmp, marker) // ponto sem volta: daqui em diante recover() decide
            if (routesDir.exists()) move(routesDir, oldRoutes)
            if (tripsFile.exists()) move(tripsFile, oldTrips)
            move(stage, routesDir)
            if (contents.tripsFile != null) move(newTrips, tripsFile)
        } catch (_: Exception) {
            try { recoverLocked(rollbackMove) } catch (_: Exception) { /* melhor esforço; recover() na próxima inicialização tenta de novo */ }
            return ApplyResult.IoFailure
        }
        oldRoutes.deleteRecursively(); oldTrips.delete(); marker.delete()
        return ApplyResult.Applied(BackupPreferences.sanitize(contents.preferences), tripCount, contents.routes.size)
    }

    /**
     * Recuperação idempotente (chamar na inicialização). Com marcador: se rotas E viagens novas já estão no lugar, só limpa os `.bkold`;
     * senão desfaz tudo (viagens e rotas voltam juntas ao estado anterior). Sem marcador: descarta stages órfãos e só descarta `.bkold`
     * órfão se o estado atual estiver íntegro (senão restaura/mantém). Nunca apaga um `.bkold` quando só um lado indica troca completa.
     */
    @Synchronized fun recover() = recoverLocked(rollbackMove)

    private fun recoverLocked(mv: (File, File) -> Unit) {
        if (marker.exists()) {
            val expectNewTrips = marker.readText().trim().lines().any { it == "newTrips=1" }
            val done = !stage.exists() && (!expectNewTrips || !newTrips.exists())
            if (done) {
                oldRoutes.deleteRecursively(); oldTrips.delete()
            } else {
                // Rotas: stage ausente = o novo já está em routesDir.
                if (!stage.exists()) routesDir.deleteRecursively()
                if (oldRoutes.exists()) { routesDir.deleteRecursively(); mv(oldRoutes, routesDir) }
                // Viagens: stage ausente (e esperado) = o novo já está em tripsFile.
                if (expectNewTrips && !newTrips.exists()) tripsFile.delete()
                if (oldTrips.exists()) { tripsFile.delete(); mv(oldTrips, tripsFile) }
            }
            marker.delete() // por último: reexecutar recover após queda no meio continua correto
            stage.deleteRecursively(); newTrips.delete(); File(marker.path + ".tmp").delete()
            return
        }
        stage.deleteRecursively(); check.deleteRecursively(); newTrips.delete(); File(marker.path + ".tmp").delete()
        if (oldRoutes.exists()) { if (!routesDir.exists()) mv(oldRoutes, routesDir) else oldRoutes.deleteRecursively() }
        if (oldTrips.exists()) {
            if (!tripsFile.exists()) mv(oldTrips, tripsFile)
            else if (FileTripRepository.validateStrict(tripsFile.readBytes()) != null) oldTrips.delete()
        }
    }

    /** Escreve as rotas em [dir] e valida cada uma com o parser do [FileRouteStore] (rota vazia/ilegível/id não canônico = inválido). */
    private fun stageRoutes(contents: BackupContents, dir: File): Boolean {
        dir.deleteRecursively()
        val store = FileRouteStore(dir)
        for ((id, bytes) in contents.routes) {
            if (!canonicalId(id)) return false
            val tripId = id.toLong()
            File(dir, "$tripId.route").writeBytes(bytes)
            if (store.read(tripId).isEmpty()) return false
        }
        return true
    }

    private companion object {
        /** Id positivo em forma canônica (`tripId.toString() == id`): "007", "+7", "0" não passam. */
        fun canonicalId(id: String) = id.toLongOrNull()?.let { it > 0 && it.toString() == id } == true

        fun atomicMove(from: File, to: File) {
            try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE) } catch (e: IOException) { throw IOException("move") }
        }
    }
}
