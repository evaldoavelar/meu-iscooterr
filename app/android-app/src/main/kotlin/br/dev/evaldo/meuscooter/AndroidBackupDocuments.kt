package br.dev.evaldo.meuscooter

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import br.dev.evaldo.meuscooter.domain.backup.BackupCodec
import br.dev.evaldo.meuscooter.ui.BackupDocuments
import br.dev.evaldo.meuscooter.ui.DocumentRead
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Arquivos escolhidos no seletor do Android (SAF; inclui Google Drive). Nenhuma permissão: o URI vem do seletor do sistema.
 * Leitura limitada ao teto de um backup (lê até o teto + 1 byte para saber se passou). Nunca loga URI nem conteúdo.
 */
class AndroidBackupDocuments(private val resolver: ContentResolver) : BackupDocuments {
    override suspend fun read(uri: String): DocumentRead = withContext(Dispatchers.IO) {
        try {
            val parsed = Uri.parse(uri)
            val input = resolver.openInputStream(parsed) ?: return@withContext DocumentRead.Failed
            val bytes = input.use { readLimited(it, BackupCodec.MAX_FILE_BYTES) } ?: return@withContext DocumentRead.TooLarge
            DocumentRead.Data(bytes, queryName(parsed))
        } catch (e: CancellationException) { throw e } catch (_: Exception) { DocumentRead.Failed }
    }

    override suspend fun write(uri: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            // "wt": trunca o destino (o seletor cria o documento vazio).
            val out = resolver.openOutputStream(Uri.parse(uri), "wt") ?: return@withContext false
            out.use { it.write(bytes); it.flush() }
            true
        } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }

    override suspend fun displayName(uri: String): String? = withContext(Dispatchers.IO) { try { queryName(Uri.parse(uri)) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } }

    override suspend fun delete(uri: String) {
        withContext(NonCancellable + Dispatchers.IO) { try { android.provider.DocumentsContract.deleteDocument(resolver, Uri.parse(uri)) } catch (_: Exception) { /* melhor esforço */ } }
    }

    private fun queryName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    internal companion object {
        /** Lê tudo, ou devolve `null` se passar de [max] bytes. */
        fun readLimited(input: InputStream, max: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) return null
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
