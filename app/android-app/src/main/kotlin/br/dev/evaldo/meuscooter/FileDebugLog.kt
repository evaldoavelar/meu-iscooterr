package br.dev.evaldo.meuscooter

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Linha `yyyy-MM-dd HH:mm:ss.SSS TAG texto` (hora local do aparelho, Locale.ROOT). */
internal fun formatDebugLogLine(wallMs: Long, tag: String, text: String): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(wallMs)) + " " + tag + " " + text

/**
 * Arquivos rotativos `ix7-0.log` (mais novo) .. `ix7-{files-1}.log` em [dir]. Lógica pura (sem Android), NÃO thread-safe:
 * quem chama serializa (ver [FileDebugLog]). Toda falha de E/S é engolida; o log nunca afeta o fluxo.
 */
internal class RotatingLogFiles(private val dir: File, private val maxBytes: Long, private val files: Int) {
    private var writer: BufferedWriter? = null
    private var size = 0L

    init { require(maxBytes > 0 && files >= 1) }

    fun file(index: Int) = File(dir, "ix7-$index.log")

    /** Acrescenta a linha (com `\n`); roda os arquivos se passar de [maxBytes]. Sem flush. */
    fun write(line: String) {
        try {
            val bytes = line.toByteArray(Charsets.UTF_8).size + 1L
            if (writer == null) open()
            else if (size > 0 && size + bytes > maxBytes) rotate()
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
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
        if (size > 0 && size >= maxBytes) rotate()
    }

    private fun rotate() {
        closeQuietly()
        file(files - 1).delete()
        for (i in files - 2 downTo 0) file(i).renameTo(file(i + 1))
        if (files == 1) file(0).delete()
        size = 0L
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file(0), false), Charsets.UTF_8))
    }

    private fun closeQuietly() {
        try { writer?.close() } catch (_: IOException) { }
        writer = null
    }
}

/**
 * Log de depuração em arquivo privado (S48b, só build depurável; ADR 0012 adendo S48b). Thread única daemon, flush a cada
 * [flushMs] ou [flushLines] linhas. O criador só instancia em build depurável: release não tem instância nem arquivo.
 */
class FileDebugLog internal constructor(
    dir: File,
    maxBytes: Long = DEFAULT_MAX_BYTES,
    files: Int = DEFAULT_FILES,
    private val flushMs: Long = 1_000L,
    private val flushLines: Int = 50,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val rotating = RotatingLogFiles(dir, maxBytes, files)
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ix7-debuglog").apply { isDaemon = true } }
    // Só tocados na thread do executor.
    private var pending = 0
    private var flushScheduled = false

    constructor(dir: File) : this(dir, DEFAULT_MAX_BYTES, DEFAULT_FILES)

    fun append(tag: String, line: String) {
        val formatted = formatDebugLogLine(now(), tag, line)
        try {
            executor.execute {
                rotating.write(formatted)
                if (++pending >= flushLines) flushNow()
                else if (!flushScheduled) {
                    flushScheduled = true
                    try { executor.schedule({ flushNow() }, flushMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: RejectedExecutionException) { flushScheduled = false }
                }
            }
        } catch (_: RejectedExecutionException) { }
    }

    /** Escoa o que está pendente e fecha o arquivo (usado em testes). */
    internal fun shutdownAndFlush() {
        try { executor.execute { rotating.close() } } catch (_: RejectedExecutionException) { }
        executor.shutdown()
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun flushNow() { rotating.flush(); pending = 0; flushScheduled = false }

    companion object {
        const val DEFAULT_MAX_BYTES = 8L * 1024 * 1024
        const val DEFAULT_FILES = 4
        const val TAG_APP = "IX7APP"

        /** Debug: instância em `<filesDir>/debuglog/`; release: `null` (nenhum arquivo). */
        fun create(debuggable: Boolean, filesDir: File): FileDebugLog? =
            if (debuggable) FileDebugLog(File(filesDir, "debuglog")) else null
    }
}
