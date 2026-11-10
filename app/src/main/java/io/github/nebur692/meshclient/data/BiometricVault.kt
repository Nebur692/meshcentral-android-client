package io.github.nebur692.meshclient.data

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What lets a person in again, sealed with a key that only opens after a strong biometric (fingerprint, or a face
 * unlock Android rates as strong), checked by Android itself on every use: the app never sees the finger, only a cipher
 * the system unlocked. A new fingerprint added to the phone invalidates the key, so whoever adds one cannot use it here.
 * Lo que deja volver a entrar, sellado con una llave que solo se abre tras una biometría fuerte, comprobada por el propio
 * Android en cada uso: la app nunca ve el dedo, solo un cifrado que el sistema desbloqueó. Una huella nueva en el móvil
 * invalida la llave, así que quien la añada no puede usarla aquí.
 */
class BiometricVault {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** Why a cipher could not be prepared. / Por qué no se pudo preparar el cifrado. */
    enum class Problem { KeyChanged, Unavailable }

    class Prepared(val cipher: Cipher?, val problem: Problem?)

    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        // Every use, by a strong biometric only (no screen-lock code). / Cada uso, solo con biometría fuerte (sin el código).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec.build()) }.generateKey()
    }

    /** A cipher to seal something new, for the prompt to unlock. / Un cifrado para sellar algo nuevo, que desbloqueará el aviso. */
    fun forSealing(): Prepared = prepare { Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) } }

    /** A cipher to open [sealed], for the prompt to unlock. / Un cifrado para abrir [sealed], que desbloqueará el aviso. */
    fun forOpening(sealed: String): Prepared = prepare {
        val all = Base64.decode(sealed, Base64.NO_WRAP)
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV)) }
    }

    private fun prepare(make: () -> Cipher): Prepared = try {
        Prepared(make(), null)
    } catch (_: KeyPermanentlyInvalidatedException) {
        // A fingerprint was added or all were removed: the key is gone for good, and with it what it sealed.
        // Se añadió una huella o se quitaron todas: la llave no vuelve, y con ella lo que selló.
        forget()
        Prepared(null, Problem.KeyChanged)
    } catch (_: GeneralSecurityException) {
        Prepared(null, Problem.Unavailable)
    } catch (_: IllegalArgumentException) {
        Prepared(null, Problem.Unavailable)
    } catch (_: ProviderException) {
        Prepared(null, Problem.Unavailable)
    }

    /** After the prompt unlocked [cipher]. / Después de que el aviso desbloqueara [cipher]. */
    fun seal(cipher: Cipher, plain: String): String? = try {
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    fun open(cipher: Cipher, sealed: String): String? = try {
        val all = Base64.decode(sealed, Base64.NO_WRAP)
        String(cipher.doFinal(all, IV, all.size - IV), Charsets.UTF_8)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    fun forget() {
        try { keyStore.deleteEntry(ALIAS) } catch (_: GeneralSecurityException) { }
    }

    companion object {
        private const val ALIAS = "saved-server-biometric"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV = 12

        /** A strong biometric is set up on this phone. / Este móvil tiene una biometría fuerte configurada. */
        fun available(context: Context): Boolean {
            val manager = context.getSystemService(BiometricManager::class.java) ?: return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
            } else {
                @Suppress("DEPRECATION") // the only call there is on Android 10 / la única que hay en Android 10
                manager.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
            }
        }
    }
}
