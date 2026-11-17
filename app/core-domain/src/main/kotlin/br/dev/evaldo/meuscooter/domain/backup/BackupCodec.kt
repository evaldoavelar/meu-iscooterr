package br.dev.evaldo.meuscooter.domain.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Formato `.ixbak` v1 (ADR 0017). Cabeçalho em claro, big-endian: `IXBK` | versão(1) | createdAtMs(8) | len(2)+appVersion UTF-8 | kdf(1 = PBKDF2-HMAC-SHA256) |
 * iterações(4) | salt(16) | IV(12). Em seguida AES-256-GCM (tag 128 bits) do payload ZIP, com o cabeçalho INTEIRO como AAD.
 * ZIP: `preferences`, `trips.v1`?, `routes/<id>.route`*, `pin`?, `manifest`. Nunca loga nem põe senha/PIN em exceção/`toString`.
 * O codec não altera a senha do chamador (o dono zera o próprio array); zera só cópias internas (chave, payload, bytes do PIN).
 */
object BackupCodec {
    const val VERSION = 1
    const val KDF_ITERATIONS = 600_000
    const val MIN_PASSWORD = 8
    const val MAX_FILE_BYTES = 32 * 1024 * 1024
    const val MAX_UNCOMPRESSED_BYTES = 64 * 1024 * 1024
    const val MAX_ENTRIES = 20_000
    private const val MAGIC = "IXBK"
    private const val KDF_ID = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val MAX_APP_VERSION = 64
    private const val MAX_KDF_ITERATIONS = 10_000_000
    private val ROUTE_ENTRY = Regex("routes/([A-Za-z0-9_-]{1,64})\\.route")

    fun encode(contents: BackupContents, password: CharArray, createdAtMs: Long, appVersion: String, random: SecureRandom): ByteArray {
        if (password.size < MIN_PASSWORD) throw BackupException(BackupError.PasswordTooShort)
        val zip = buildZip(contents)
        try { return encrypt(zip, password, createdAtMs, appVersion, random) } finally { zip.fill(0) }
    }

    /** Só o cabeçalho (sem senha, sem autenticar): serve para mostrar data/versão/tamanho antes de pedir a senha. */
    fun peek(bytes: ByteArray): BackupResult<BackupHeader> {
        if (bytes.size > MAX_FILE_BYTES) return BackupResult.Err(BackupError.TooLarge)
        val corrupt = BackupResult.Err(BackupError.WrongPasswordOrCorrupt)
        return try {
            val inp = DataInputStream(ByteArrayInputStream(bytes))
            val magic = ByteArray(4).also { inp.readFully(it) }
            if (!magic.contentEquals(MAGIC.toByteArray(StandardCharsets.US_ASCII))) return corrupt
            val version = inp.readUnsignedByte()
            if (version != VERSION) return BackupResult.Err(BackupError.UnsupportedVersion)
            val createdAt = inp.readLong()
            val appLen = inp.readUnsignedShort()
            if (appLen > MAX_APP_VERSION) return corrupt
            val app = String(ByteArray(appLen).also { inp.readFully(it) }, StandardCharsets.UTF_8)
            if (inp.readUnsignedByte() != KDF_ID) return corrupt
            val iterations = inp.readInt()
            if (iterations !in KDF_ITERATIONS..MAX_KDF_ITERATIONS) return corrupt
            val salt = ByteArray(SALT_LEN).also { inp.readFully(it) }
            val iv = ByteArray(IV_LEN).also { inp.readFully(it) }
            val headerLength = bytes.size - inp.available()
            if (bytes.size - headerLength < TAG_BITS / 8) return corrupt
            BackupResult.Ok(BackupHeader(version, createdAt, app, iterations, salt, iv, headerLength, bytes.size))
        } catch (_: IOException) { corrupt }
    }

    fun decode(bytes: ByteArray, password: CharArray): BackupResult<BackupContents> {
        val header = when (val h = peek(bytes)) { is BackupResult.Ok -> h.value; is BackupResult.Err -> return h }
        val plain = try {
            val key = deriveKey(password, header.salt, header.kdfIterations)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, header.iv))
                cipher.updateAAD(bytes, 0, header.headerLength)
                cipher.doFinal(bytes, header.headerLength, bytes.size - header.headerLength)
            } finally { key.fill(0) }
        } catch (_: GeneralSecurityException) { return BackupResult.Err(BackupError.WrongPasswordOrCorrupt) }
        try { return parseZip(plain) } finally { plain.fill(0) }
    }

    /** Quantidade de viagens de um `trips.v1` (linhas após o cabeçalho). */
    fun countTrips(tripsFile: ByteArray?): Int =
        if (tripsFile == null) 0 else String(tripsFile, StandardCharsets.UTF_8).lines().drop(1).count { it.isNotBlank() }

    // ---- interno ----

    internal fun encrypt(zip: ByteArray, password: CharArray, createdAtMs: Long, appVersion: String, random: SecureRandom): ByteArray {
        val app = appVersion.toByteArray(StandardCharsets.UTF_8)
        if (app.size > MAX_APP_VERSION) throw BackupException(BackupError.InvalidContent)
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val headerBuf = ByteArrayOutputStream()
        DataOutputStream(headerBuf).run {
            write(MAGIC.toByteArray(StandardCharsets.US_ASCII)); writeByte(VERSION); writeLong(createdAtMs)
            writeShort(app.size); write(app); writeByte(KDF_ID); writeInt(KDF_ITERATIONS); write(salt); write(iv)
        }
        val header = headerBuf.toByteArray()
        val key = deriveKey(password, salt, KDF_ITERATIONS)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            val out = header + cipher.doFinal(zip)
            if (out.size > MAX_FILE_BYTES) throw BackupException(BackupError.TooLarge)
            return out
        } finally { key.fill(0) }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    private fun buildZip(c: BackupContents): ByteArray {
        val total = (c.tripsFile?.size ?: 0).toLong() + c.routes.values.sumOf { it.size.toLong() }
        if (total > MAX_UNCOMPRESSED_BYTES || c.routes.size + 4 > MAX_ENTRIES) throw BackupException(BackupError.TooLarge)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
            val prefs = c.preferences.entries.joinToString("") { (k, v) ->
                if (k.isEmpty() || '=' in k || '\n' in k || '\n' in v || '\r' in k || '\r' in v) throw BackupException(BackupError.InvalidContent)
                "$k=$v\n"
            }
            put("preferences", prefs.toByteArray(StandardCharsets.UTF_8))
            c.tripsFile?.let { put("trips.v1", it) }
            c.routes.forEach { (id, data) ->
                if (!Regex("[A-Za-z0-9_-]{1,64}").matches(id)) throw BackupException(BackupError.InvalidContent)
                put("routes/$id.route", data)
            }
            c.pin?.let { pin ->
                val b = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(pin))
                val arr = ByteArray(b.remaining()).also { b.get(it) }
                if (b.hasArray()) b.array().fill(0)
                try { put("pin", arr) } finally { arr.fill(0) }
            }
            put("manifest", "trips=${countTrips(c.tripsFile)}\nroutes=${c.routes.size}\n".toByteArray(StandardCharsets.UTF_8))
        }
        return out.toByteArray()
    }

    private fun parseZip(plain: ByteArray): BackupResult<BackupContents> {
        val bad = BackupResult.Err(BackupError.InvalidContent)
        val entries = HashMap<String, ByteArray>()
        var remaining = MAX_UNCOMPRESSED_BYTES.toLong()
        try {
            ZipInputStream(ByteArrayInputStream(plain)).use { z ->
                val buf = ByteArray(8192)
                while (true) {
                    val e = z.nextEntry ?: break
                    if (entries.size >= MAX_ENTRIES) return BackupResult.Err(BackupError.TooLarge)
                    val name = e.name
                    val known = name in setOf("preferences", "trips.v1", "pin", "manifest") || ROUTE_ENTRY.matches(name)
                    if (e.isDirectory || !known || name in entries) return bad
                    val data = ByteArrayOutputStream()
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        remaining -= n
                        if (remaining < 0) return BackupResult.Err(BackupError.TooLarge)
                        data.write(buf, 0, n)
                    }
                    entries[name] = data.toByteArray()
                }
            }
        } catch (_: IOException) { return bad } catch (_: IllegalArgumentException) { return bad }
        val prefsRaw = entries["preferences"] ?: return bad
        val manifest = entries["manifest"] ?: return bad
        val prefs = LinkedHashMap<String, String>()
        val text = String(prefsRaw, StandardCharsets.UTF_8)
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val i = line.indexOf('=')
            if (i <= 0) return bad
            prefs[line.substring(0, i)] = line.substring(i + 1)
        }
        val routes = entries.filterKeys { it.startsWith("routes/") }.mapKeys { ROUTE_ENTRY.matchEntire(it.key)!!.groupValues[1] }
        val trips = entries["trips.v1"]
        val m = String(manifest, StandardCharsets.UTF_8).lines().filter { it.isNotBlank() }.associate { l -> l.substringBefore('=') to l.substringAfter('=', "") }
        if (m["trips"]?.toIntOrNull() != countTrips(trips) || m["routes"]?.toIntOrNull() != routes.size) return bad
        val pin = entries["pin"]?.let { b ->
            val chars = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(b))
            CharArray(chars.remaining()).also { chars.get(it) }.also { if (chars.hasArray()) chars.array().fill('\u0000'); b.fill(0) }
        }
        return BackupResult.Ok(BackupContents(prefs, trips, routes, pin))
    }
}
