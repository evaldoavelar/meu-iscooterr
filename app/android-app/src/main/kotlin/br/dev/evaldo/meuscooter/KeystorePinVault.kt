package br.dev.evaldo.meuscooter

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import br.dev.evaldo.meuscooter.data.PinBlob
import br.dev.evaldo.meuscooter.domain.PinLoadResult
import br.dev.evaldo.meuscooter.domain.PinSaveResult
import br.dev.evaldo.meuscooter.domain.PinVault
import br.dev.evaldo.meuscooter.protocol.Pin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.CharBuffer
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/**
 * Cofre do PIN no Android Keystore (ADR 0006 §5): chave AES-256-GCM sem exportação, `userAuthenticationRequired` com
 * biometria forte A CADA USO (timeout 0, `CryptoObject`), invalidada se uma nova biometria for cadastrada. Cifra e
 * decifra só depois do `BiometricPrompt` do framework (sem dependência nova). Em disco fica só IV + texto cifrado
 * ([PinBlob]); o PIN nunca vai a log nem a estado, e os arrays temporários são zerados.
 *
 * API 30+ (`setUserAuthenticationParameters`, `Authenticators`): abaixo disso [createPinVault] devolve [PinVault.None]
 * (desvio conservador do "API 28+" do ADR; registrado no ADR 0006). Não há teste na JVM (Keystore/biometria
 * exigem aparelho): `[ESTÁTICO]` até o teste real.
 */
@RequiresApi(Build.VERSION_CODES.R)
class KeystorePinVault internal constructor(private val context: Context) : PinVault {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _has = MutableStateFlow(prefs.getString(KEY_BLOB, null) != null)
    override val hasSavedPin: StateFlow<Boolean> = _has.asStateFlow()

    override val available: Boolean
        get() = context.getSystemService(BiometricManager::class.java)?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /** Alias da chave do blob atual (versionado: gravar nova chave nunca destrói a antiga antes de a nova estar gravada). */
    private fun currentAlias(): String = prefs.getString(KEY_ALIAS, null) ?: ALIAS

    override suspend fun save(pin: Pin): PinSaveResult = mutex.withLock {
        if (!available) return PinSaveResult.Unavailable
        val oldAlias = currentAlias()
        val newAlias = if (oldAlias == ALIAS) ALIAS_B else ALIAS
        val cipher = try {
            deleteKey(newAlias)
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, generateKey(newAlias)) }
        } catch (e: Exception) {
            deleteKey(newAlias); return PinSaveResult.Failed
        }
        val ready = when (val auth = withContext(Dispatchers.Main) { authenticate(cipher, "Salvar PIN do patinete") }) {
            is Auth.Ok -> auth.cipher
            Auth.Cancelled -> { deleteKey(newAlias); return PinSaveResult.Cancelled }   // o PIN antigo continua valendo
            Auth.Failed -> { deleteKey(newAlias); return PinSaveResult.Failed }
        }
        val plain = pin.toAsciiBytes()
        try {
            val blob = PinBlob.encode(ready.iv, ready.doFinal(plain))
            // Blob e alias novos num único commit; só depois a chave antiga é apagada.
            val ok = prefs.edit().putString(KEY_BLOB, blob).putString(KEY_ALIAS, newAlias).commit()
            if (!ok) { deleteKey(newAlias); return PinSaveResult.Failed }
            deleteKey(oldAlias)
            _has.value = true
            PinSaveResult.Saved
        } catch (e: Exception) {
            deleteKey(newAlias); PinSaveResult.Failed
        } finally {
            plain.fill(0)
        }
    }

    override suspend fun load(): PinLoadResult = mutex.withLock {
        if (!available) return PinLoadResult.Unavailable
        val stored = prefs.getString(KEY_BLOB, null) ?: return PinLoadResult.NotSaved
        val alias = currentAlias()
        val parts = PinBlob.decode(stored) ?: run { forgetLocked(); return PinLoadResult.Failed }
        val key = try { keyStore().getKey(alias, null) as? SecretKey } catch (e: Exception) { null }
        if (key == null) { forgetLocked(); return PinLoadResult.NotSaved }   // chave perdida (restauração de backup, reset)
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, parts.iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            forgetLocked(); return PinLoadResult.Failed   // nova biometria cadastrada: o PIN guardado não é mais recuperável
        } catch (e: Exception) {
            return PinLoadResult.Failed
        }
        val ready = when (val auth = withContext(Dispatchers.Main) { authenticate(cipher, "Usar PIN salvo") }) {
            is Auth.Ok -> auth.cipher
            Auth.Cancelled -> return PinLoadResult.Cancelled
            Auth.Failed -> return PinLoadResult.Failed
        }
        val plain = try {
            ready.doFinal(parts.ciphertext)
        } catch (e: AEADBadTagException) {
            forgetLocked(); return PinLoadResult.Failed   // blob corrompido/adulterado: irrecuperável
        } catch (e: KeyPermanentlyInvalidatedException) {
            forgetLocked(); return PinLoadResult.Failed
        } catch (e: Exception) {
            return PinLoadResult.Failed   // falha transitória: o PIN guardado é mantido
        }
        val chars = CharArray(plain.size) { plain[it].toInt().toChar() }
        try {
            Pin.parse(CharBuffer.wrap(chars))?.let { PinLoadResult.Loaded(it) } ?: run { forgetLocked(); PinLoadResult.Failed }
        } finally {
            plain.fill(0); chars.fill('\u0000')
        }
    }

    /** Serializado com [save]/[load] pelo mutex e fora da thread principal. */
    override suspend fun forget() {
        mutex.withLock { withContext(Dispatchers.IO) { forgetLocked() } }
    }

    private fun forgetLocked() {
        prefs.edit().remove(KEY_BLOB).remove(KEY_ALIAS).commit()
        deleteKey(ALIAS); deleteKey(ALIAS_B)
        _has.value = false
    }

    private fun deleteKey(alias: String) {
        try { keyStore().deleteEntry(alias) } catch (_: Exception) { /* sem chave: nada a apagar */ }
    }

    private sealed interface Auth {
        class Ok(val cipher: Cipher) : Auth
        data object Cancelled : Auth
        data object Failed : Auth
    }

    /** Mostra o `BiometricPrompt` do sistema (thread principal) e devolve o cifrador liberado. Cancelar a corrotina cancela o prompt. */
    private suspend fun authenticate(cipher: Cipher, title: String): Auth = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        val executor = context.mainExecutor
        val finish = { result: Auth -> if (cont.isActive) cont.resume(result) }
        try {
            val prompt = BiometricPrompt.Builder(context)
                .setTitle(title)
                .setSubtitle("Confirme com a biometria")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButton("Cancelar", executor) { _, _ -> finish(Auth.Cancelled) }
                .build()
            prompt.authenticate(BiometricPrompt.CryptoObject(cipher), signal, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    finish(result.cryptoObject?.cipher?.let { Auth.Ok(it) } ?: Auth.Failed)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    finish(
                        when (errorCode) {
                            BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> Auth.Cancelled
                            else -> Auth.Failed
                        },
                    )
                }
                // onAuthenticationFailed (digital não reconhecida): o prompt continua; nada a fazer.
            })
        } catch (e: Exception) {
            finish(Auth.Failed)
        }
    }

    private fun generateKey(alias: String): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "iscooter_pin_v1"
        private const val ALIAS_B = "iscooter_pin_v1b"
        private const val KEY_ALIAS = "alias"
        private const val PREFS = "pin_vault"
        private const val KEY_BLOB = "blob"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

    }
}

/** Cofre real em API 30+; abaixo disso, sem cofre (as opções somem da UI). */
fun createPinVault(context: Context): PinVault =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) KeystorePinVault(context.applicationContext) else PinVault.None
