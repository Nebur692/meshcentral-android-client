package io.github.nebur692.meshclient.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A server the person signed in to: never the password in the clear. / Un servidor en el que entró la persona: nunca la contraseña en claro. */
data class SavedServer(
    val address: String,
    val username: String,
    /** The fingerprint the person accepted, for a server with its own certificate. / La huella que aceptó la persona. */
    val pinnedSha256: String?,
    val lastUsed: Long,
    /** IV and ciphertext, only if the person asked to remember it. / IV y texto cifrado, solo si lo pidió. */
    internal val sealedPassword: String? = null,
    /** The single sign-on strategy ("oidc"…) when this account signs in that way. / La estrategia de inicio único, si entra así. */
    val singleSignOn: String? = null,
    /** A MeshCentral login token ("user\npassword"), sealed, only if the person asked to remember the session.
     *  Un token de entrada de MeshCentral, sellado, solo si la persona pidió recordar la sesión. */
    internal val sealedToken: String? = null,
    /** When that token stops working (ms since 1970). / Cuándo deja de valer ese token. */
    val tokenExpires: Long = 0,
) {
    val hasPassword: Boolean get() = sealedPassword != null
    val hasSession: Boolean get() = sealedToken != null && tokenExpires > System.currentTimeMillis()
}

/** Kept in the app's private storage, excluded from every backup (data_extraction_rules). / En el almacén privado, fuera de toda copia. */
class SavedServers(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("servers", Context.MODE_PRIVATE)
    private val vault = PasswordVault()

    fun all(): List<SavedServer> = read().sortedByDescending { it.lastUsed }

    fun remember(address: String, username: String, pinnedSha256: String?, password: String?) {
        val sealed = password?.let(vault::seal)
        val rest = read().filterNot { it.address == address }
        write(rest + SavedServer(address, username, pinnedSha256, System.currentTimeMillis(), sealed))
    }

    /** A single sign-on account: [token] only if the person asked to remember the session. / Una cuenta de inicio único. */
    fun rememberSingleSignOn(address: String, username: String, pinnedSha256: String?, strategy: String, token: Pair<String, String>?, expires: Long) {
        val sealed = token?.let { vault.seal(it.first + "\n" + it.second) }
        val rest = read().filterNot { it.address == address }
        write(rest + SavedServer(address, username, pinnedSha256, System.currentTimeMillis(), null, strategy, sealed, if (sealed != null) expires else 0))
    }

    fun forget(address: String) = write(read().filterNot { it.address == address })

    /** The remembered session's token user and password, while it lasts. / El usuario y la contraseña del token recordado, mientras dure. */
    fun token(server: SavedServer): Pair<String, String>? {
        if (!server.hasSession) return null
        val both = server.sealedToken?.let(vault::open)?.split("\n", limit = 2) ?: return null
        return if (both.size == 2) both[0] to both[1] else null
    }

    /** Null when there is none or the key is gone (lock screen removed, for example). / Null si no hay o la llave ya no existe. */
    fun password(server: SavedServer): String? = server.sealedPassword?.let(vault::open)

    private fun read(): List<SavedServer> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SavedServer(o.getString("address"), o.getString("username"), o.optString("pin").ifEmpty { null },
                o.optLong("lastUsed"), o.optString("password").ifEmpty { null }, o.optString("sso").ifEmpty { null },
                o.optString("token").ifEmpty { null }, o.optLong("tokenExpires"))
        }
    } catch (_: JSONException) {
        emptyList()
    }

    private fun write(list: List<SavedServer>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("address", s.address).put("username", s.username).put("pin", s.pinnedSha256 ?: "")
                .put("lastUsed", s.lastUsed).put("password", s.sealedPassword ?: "").put("sso", s.singleSignOn ?: "")
                .put("token", s.sealedToken ?: "").put("tokenExpires", s.tokenExpires))
        }
        prefs.edit { putString(KEY, arr.toString()) }
    }

    private companion object { const val KEY = "list" }
}

/** AES-GCM with a key that never leaves Android's keystore. / AES-GCM con una llave que nunca sale del almacén de Android. */
private class PasswordVault {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    fun seal(plain: String): String? = try {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(c.iv + body, Base64.NO_WRAP)
    } catch (_: GeneralSecurityException) {
        null // not remembered rather than a crash / no se recuerda, en vez de cerrarse
    } catch (_: ProviderException) {
        null
    }

    fun open(sealed: String): String? = try {
        val all = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV)) }
        String(c.doFinal(all, IV, all.size - IV), Charsets.UTF_8)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ProviderException) {
        null
    }

    private companion object {
        const val ALIAS = "saved-server-passwords"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV = 12
    }
}
