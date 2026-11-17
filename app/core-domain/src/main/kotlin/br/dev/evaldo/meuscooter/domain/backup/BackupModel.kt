package br.dev.evaldo.meuscooter.domain.backup

/** Resultado de uma operação do backup: sucesso com valor ou um [BackupError] (nunca carrega senha/PIN/conteúdo). */
sealed interface BackupResult<out T> {
    data class Ok<out T>(val value: T) : BackupResult<T> {
        override fun toString() = "Ok(<redigido>)"
    }
    data class Err(val error: BackupError) : BackupResult<Nothing>
}

enum class BackupError {
    /** Senha errada, arquivo adulterado, truncado ou corrompido: o mesmo resultado genérico de propósito. */
    WrongPasswordOrCorrupt,
    /** Versão do formato desconhecida (arquivo de um app mais novo). */
    UnsupportedVersion,
    /** Arquivo ou conteúdo descompactado acima dos tetos (anti zip-bomb). */
    TooLarge,
    /** Conteúdo autenticado mas fora do formato (entrada inesperada, caminho `..`, manifesto divergente). */
    InvalidContent,
    /** Senha com menos de [BackupCodec.MIN_PASSWORD] caracteres (só na escrita). */
    PasswordTooShort,
}

/** Erro lançado só por [BackupCodec.encode] (entrada do chamador inválida); a mensagem nunca tem dados sensíveis. */
class BackupException(val error: BackupError) : Exception(error.name)

/** Cabeçalho em claro do `.ixbak` (nada sensível). [fileSize] = tamanho do arquivo inteiro. */
class BackupHeader(
    val version: Int,
    val createdAtMs: Long,
    val appVersion: String,
    val kdfIterations: Int,
    val salt: ByteArray,
    val iv: ByteArray,
    val headerLength: Int,
    val fileSize: Int,
) {
    override fun toString() = "BackupHeader(v$version, createdAtMs=$createdAtMs, app=$appVersion, kdfIterations=$kdfIterations, size=$fileSize)"
}

/** Resumo para a tela de restauração. [scooterName] vem da preferência `scooterName`, se existir. */
data class BackupSummary(
    val createdAtMs: Long,
    val appVersion: String,
    val tripCount: Int,
    val routeCount: Int,
    val hasPin: Boolean,
    val scooterName: String?,
)

/**
 * Conteúdo lógico do backup. [pin] é o texto do PIN (só em memória; chame [wipe] após o uso). [routes]: id da viagem → bytes do `.route`.
 * `toString` é redigido: nunca imprime preferências, PIN nem bytes.
 */
class BackupContents(
    val preferences: Map<String, String>,
    val tripsFile: ByteArray?,
    val routes: Map<String, ByteArray>,
    val pin: CharArray?,
) {
    fun summary(header: BackupHeader) = BackupSummary(
        createdAtMs = header.createdAtMs,
        appVersion = header.appVersion,
        tripCount = BackupCodec.countTrips(tripsFile),
        routeCount = routes.size,
        hasPin = pin != null,
        scooterName = preferences["scooterName"]?.takeIf { it.isNotBlank() },
    )

    /** Igualdade por conteúdo (arrays comparados byte a byte). */
    fun sameAs(o: BackupContents) = preferences == o.preferences &&
        (tripsFile == null && o.tripsFile == null || tripsFile != null && o.tripsFile != null && tripsFile.contentEquals(o.tripsFile)) &&
        routes.keys == o.routes.keys && routes.all { (k, v) -> v.contentEquals(o.routes[k]) } &&
        (pin == null && o.pin == null || pin != null && o.pin != null && pin.contentEquals(o.pin))

    /** Zera PIN e bytes em memória. */
    fun wipe() {
        pin?.fill('\u0000'); tripsFile?.fill(0); routes.values.forEach { it.fill(0) }
    }

    override fun toString() = "BackupContents(preferences=${preferences.size}, trips=${tripsFile != null}, routes=${routes.size}, pin=${if (pin != null) "presente" else "ausente"})"
}
