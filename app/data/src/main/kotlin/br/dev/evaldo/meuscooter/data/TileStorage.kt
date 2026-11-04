package br.dev.evaldo.meuscooter.data

import br.dev.evaldo.meuscooter.domain.CacheEntry
import br.dev.evaldo.meuscooter.domain.StoredTile
import br.dev.evaldo.meuscooter.domain.TileCacheStore
import br.dev.evaldo.meuscooter.domain.TileNetwork
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cache de tiles em disco: um arquivo `<chave>.tile` = 8 bytes (data de gravação, ms) + PNG.
 * O `lastModified` do arquivo guarda o último acesso (usado no despejo).
 */
class DiskTileCacheStore(private val dir: File) : TileCacheStore {
    init { dir.mkdirs() }

    private fun file(key: String) = File(dir, "$key.tile")

    override fun read(key: String): StoredTile? {
        val f = file(key)
        return try {
            val all = f.readBytes()
            if (all.size < HEADER) null else StoredTile(all.copyOfRange(HEADER, all.size), ByteBuffer.wrap(all, 0, HEADER).long)
        } catch (_: IOException) { null }
    }

    override fun write(key: String, bytes: ByteArray, nowMs: Long) {
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(ByteBuffer.allocate(HEADER).putLong(nowMs).array() + bytes)
        tmp.setLastModified(nowMs)
        if (!tmp.renameTo(file(key))) { tmp.delete(); throw IOException("não foi possível gravar o tile $key") }
    }

    override fun touch(key: String, nowMs: Long) { file(key).setLastModified(nowMs) }

    override fun entries(): List<CacheEntry> = dir.listFiles { f -> f.name.endsWith(".tile") }.orEmpty().mapNotNull { f ->
        val stored = try { f.inputStream().use { s -> ByteArray(HEADER).also { if (s.read(it) < HEADER) return@mapNotNull null } } } catch (_: IOException) { return@mapNotNull null }
        CacheEntry(f.name.removeSuffix(".tile"), f.length() - HEADER, ByteBuffer.wrap(stored).long, f.lastModified())
    }

    override fun delete(key: String) { file(key).delete() }

    private companion object { const val HEADER = 8 }
}

/** Download HTTP de um tile (fora da thread principal). Lança [IOException] se status ≠ 200, falha de rede ou corpo > [maxBytes]. */
class HttpTileNetwork(private val timeoutMs: Int = 10_000, private val maxBytes: Int = 512 * 1024) : TileNetwork {
    override suspend fun fetch(url: String, userAgent: String): ByteArray = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = s.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("tile maior que o limite")
                }
                out.toByteArray()
            }
        } finally { c.disconnect() }
    }
}
