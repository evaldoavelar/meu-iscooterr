package br.dev.evaldo.meuscooter

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/** Linha `yyyy-MM-dd HH:mm:ss.SSS TAG texto` (hora local do aparelho, Locale.ROOT). */
internal fun formatDebugLogLine(wallMs: Long, tag: String, text: String): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(wallMs)) + " " + tag + " " + text

/** Seam de E/S para exercitar bloqueios e falhas sem depender de permissões do filesystem. */
internal interface LogFileOperations {
    fun openAppend(file: File): BufferedWriter
    fun delete(file: File): Boolean
    fun rename(source: File, target: File): Boolean
}

internal object DiskLogFileOperations : LogFileOperations {
    override fun openAppend(file: File) =
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
    override fun delete(file: File) = file.delete()
    override fun rename(source: File, target: File) = source.renameTo(target)
}

/**
 * Arquivos rotativos `ix7-0.log` (mais novo) .. `ix7-{files-1}.log` em [dir]. Lógica pura (sem Android), NÃO thread-safe:
 * quem chama serializa (ver [FileDebugLog]). Toda falha de E/S é engolida; o log nunca afeta o fluxo.
 */
internal class RotatingLogFiles(
    private val dir: File,
    private val maxBytes: Long,
    private val files: Int,
    private val io: LogFileOperations = DiskLogFileOperations,
) {
    private var writer: BufferedWriter? = null
    private var size = 0L

    /** Linhas descartadas porque a rotação falhou (o arquivo atual nunca passa de [maxBytes]). */
    var rotationDropped = 0L
        private set

    init { require(maxBytes > 0 && files >= 1) }

    fun file(index: Int) = File(dir, "ix7-$index.log")

    /** Acrescenta a linha (com `\n`); roda os arquivos se passar de [maxBytes]. Sem flush. */
    fun write(line: String) {
        try {
            val bytes = line.toByteArray(Charsets.UTF_8).size + 1L
            if (writer == null) open()
            if (size > 0 && size + bytes > maxBytes && !rotate()) {
                rotationDropped++
                return
            }
            val w = writer ?: return
            w.write(line); w.write("\n")
            size += bytes
        } catch (_: IOException) {
            closeQuietly()
        }
    }

    fun flush() { try { writer?.flush() } catch (_: IOException) { closeQuietly() } }

    fun close() { flush(); closeQuietly() }

    private fun open() {
        dir.mkdirs()
        val f = file(0)
        size = if (f.exists()) f.length() else 0L
        writer = io.openAppend(f)
    }

    /** true se rodou; false se delete/rename falhou: o arquivo atual é preservado e a linha fica de fora (nova tentativa na próxima). */
    private fun rotate(): Boolean {
        closeQuietly()
        val oldest = file(files - 1)
        if (oldest.exists() && !io.delete(oldest)) {
            open()
            return false
        }
        for (i in files - 2 downTo 0) {
            val source = file(i)
            if (source.exists() && !io.rename(source, file(i + 1))) {
                open()
                return false
            }
        }
        // Na rotação completa, ix7-0 já não existe: open() cria um novo.
        open()
        return true
    }

    private fun closeQuietly() {
        try { writer?.close() } catch (_: IOException) { }
        writer = null
    }
}

/**
 * Log de depuração em arquivo privado (S48b, só build depurável; ADR 0012 adendo S48b). Thread única daemon, flush a cada
 * [flushMs] ou [flushLines] linhas. Fila de [queueCapacity] linhas aguardando E/S; cheia, descarta a mais nova e conta.
 * O processo usa [shared]; o construtor interno permite instâncias isoladas em testes.
 */
class FileDebugLog internal constructor(
    dir: File,
    maxBytes: Long = DEFAULT_MAX_BYTES,
    files: Int = DEFAULT_FILES,
    private val flushMs: Long = 1_000L,
    private val flushLines: Int = 50,
    private val now: () -> Long = System::currentTimeMillis,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    fileOperations: LogFileOperations = DiskLogFileOperations,
) {
    init { require(flushMs > 0 && flushLines > 0 && queueCapacity > 0) }

    private val rotating = RotatingLogFiles(dir, maxBytes, files, fileOperations)
    private val executor = ScheduledThreadPoolExecutor(
        1, ThreadFactory { r -> Thread(r, "ix7-debuglog").apply { isDaemon = true } },
    ).apply { removeOnCancelPolicy = true }
    private val queueLock = Any()
    // Protegidos por queueLock. O executor recebe apenas uma tarefa de drenagem, nunca uma tarefa por linha.
    private val queue = ArrayDeque<String>()
    private var draining = false
    private var closed = false
    private var dropped = 0L
    // Só tocados na thread do executor.
    private var pending = 0
    private var pendingSinceNs = 0L
    private var flushTask: ScheduledFuture<*>? = null

    fun append(tag: String, line: String) {
        synchronized(queueLock) {
            if (closed) return
            if (queue.size >= queueCapacity) {
                dropped++
                return
            }
            queue.addLast(formatDebugLogLine(now(), tag, line))
            if (!draining) {
                draining = true
                executor.execute { drain() }
            }
        }
    }

    private fun drain() {
        while (true) {
            val (line, lost) = synchronized(queueLock) {
                if (queue.isEmpty()) {
                    draining = false
                    return
                }
                (queue.removeFirst() to dropped).also { dropped = 0 }
            }
            if (lost > 0) writeLine(formatDebugLogLine(now(), TAG_APP, "dropped=$lost"))
            writeLine(line)
        }
    }

    private fun writeLine(line: String) {
        rotating.write(line)
        if (pending++ == 0) pendingSinceNs = System.nanoTime()
        // A drenagem contínua não pode impedir o flush por tempo de rodar.
        if (pending >= flushLines || System.nanoTime() - pendingSinceNs >= TimeUnit.MILLISECONDS.toNanos(flushMs)) {
            flushNow()
        } else if (flushTask == null) {
            try {
                flushTask = executor.schedule({ flushNow() }, flushMs, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) { /* Encerramento: a tarefa de close fará o flush. */ }
        }
    }

    /** Escoa o que está pendente e fecha o arquivo (usado em testes). */
    internal fun shutdownAndFlush() {
        synchronized(queueLock) {
            if (!closed) {
                closed = true
                executor.execute { flushNow(); rotating.close() }
                executor.shutdown()
            }
        }
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun flushNow() {
        rotating.flush()
        pending = 0
        flushTask?.cancel(false)
        flushTask = null
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 8L * 1024 * 1024
        const val DEFAULT_FILES = 4
        const val DEFAULT_QUEUE_CAPACITY = 5_000
        const val TAG_APP = "IX7APP"

        private var processInstance: FileDebugLog? = null

        /** Uma instância por processo em `<filesDir>/debuglog/`; release: `null` (nenhum arquivo). */
        @Synchronized
        fun shared(debuggable: Boolean, filesDir: File): FileDebugLog? {
            if (!debuggable) return null
            return processInstance ?: FileDebugLog(File(filesDir, "debuglog")).also { processInstance = it }
        }
    }
}
