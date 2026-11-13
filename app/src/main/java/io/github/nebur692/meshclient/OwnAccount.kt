package io.github.nebur692.meshclient

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.nebur692.meshclient.core.AuthenticatorSetup
import io.github.nebur692.meshclient.core.BackupCode
import io.github.nebur692.meshclient.core.BackupCodeAction
import io.github.nebur692.meshclient.core.LoginToken
import io.github.nebur692.meshclient.core.MeshClientException
import io.github.nebur692.meshclient.core.MeshFailure
import io.github.nebur692.meshclient.core.MeshSession
import io.github.nebur692.meshclient.core.SecurityKey
import io.github.nebur692.meshclient.core.ServerStats
import io.github.nebur692.meshclient.core.UserInfo
import io.github.nebur692.meshclient.core.backupCodes
import io.github.nebur692.meshclient.core.changePassword
import io.github.nebur692.meshclient.core.confirmAuthenticator
import io.github.nebur692.meshclient.core.removeAuthenticator
import io.github.nebur692.meshclient.core.removeSecurityKey
import io.github.nebur692.meshclient.core.securityKeys
import io.github.nebur692.meshclient.core.serverStats
import io.github.nebur692.meshclient.core.startAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The person's own account: password, authenticator app, backup codes, security keys and login tokens. What the server
 * keeps is asked when the screen opens; after a change, the account is read again from the session, which the server's
 * live event keeps up to date.
 * La cuenta propia: contraseña, app de autenticación, códigos de respaldo, llaves de seguridad y tokens de acceso. Lo que
 * guarda el servidor se pide al abrir la pantalla; tras un cambio, la cuenta se vuelve a leer de la sesión, que el aviso en
 * vivo del servidor mantiene al día.
 */
class OwnAccountModel(private val session: () -> MeshSession?, private val scope: CoroutineScope) {
    var me by mutableStateOf(session()?.user)
        private set
    var keys by mutableStateOf<List<SecurityKey>?>(null)
        private set
    var tokens by mutableStateOf<List<LoginToken>?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<Notice?>(null)
    /** An authenticator being added: shown until its first code is right. / Una app que se está añadiendo. */
    var setup by mutableStateOf<AuthenticatorSetup?>(null)
    var codeWrong by mutableStateOf(false)
        private set
    /** Backup codes on screen (each shown only while the sheet is open). / Códigos de respaldo en pantalla. */
    var codes by mutableStateOf<List<BackupCode>?>(null)
    /** A token just created, the only time its password is seen. / Un token recién creado: la única vez que se ve su contraseña. */
    var newToken by mutableStateOf<LoginToken?>(null)
    var passwordDone by mutableIntStateOf(0)
        private set

    private fun current() = session() ?: throw MeshClientException(MeshFailure.ConnectionLost, "usuarios/cambiar-ajustes/conexion-cortada", "no session")

    fun load() {
        me = session()?.user
        scope.launch {
            runCatching { keys = current().securityKeys() }
            runCatching { tokens = current().loginTokens().sortedByDescending { it.created } }
        }
    }

    /** One change: busy meanwhile, its failure said. / Un cambio: ocupado mientras, y su fallo dicho. */
    private fun change(ok: Int?, block: suspend (MeshSession) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block(current())
                ok?.let { notice = Notice(it) }
            } catch (e: MeshClientException) {
                notice = Notice(R.string.action_failed, UiError(e.failure, e.key, e.httpStatus))
            } finally {
                busy = false
            }
        }
    }

    /** Waits (a few seconds at most) for the server's event to show the change on the account. / Espera a que el aviso lo enseñe. */
    private suspend fun awaitUser(done: (UserInfo) -> Boolean) {
        repeat(20) {
            val u = session()?.user
            if (u != null && done(u)) { me = u; return }
            delay(250)
        }
        me = session()?.user
    }

    fun changePassword(current: String, new: String) = change(R.string.acc_password_done) {
        it.changePassword(current, new)
        passwordDone++
    }

    fun startAuthenticator() = change(null) { codeWrong = false; setup = it.startAuthenticator() }

    fun confirmAuthenticator(code: String) {
        val s = setup ?: return
        if (busy) return
        busy = true
        scope.launch {
            try {
                current().confirmAuthenticator(s, code)
                setup = null
                awaitUser { it.hasAuthenticator }
                notice = Notice(R.string.acc_auth_done)
            } catch (e: MeshClientException) {
                if (e.failure == MeshFailure.InvalidTwoFactorCode) codeWrong = true
                else notice = Notice(R.string.action_failed, UiError(e.failure, e.key, e.httpStatus))
            } finally {
                busy = false
            }
        }
    }

    fun removeAuthenticator() = change(R.string.acc_auth_removed) {
        it.removeAuthenticator()
        awaitUser { u -> !u.hasAuthenticator }
    }

    fun backupCodes(action: BackupCodeAction) = change(null) {
        val list = it.backupCodes(action)
        codes = list.takeIf { action != BackupCodeAction.Clear }
        awaitUser { u -> u.hasBackupCodes == (action != BackupCodeAction.Clear && list.any { c -> !c.used }) }
        if (action == BackupCodeAction.Clear) notice = Notice(R.string.acc_codes_cleared)
    }

    fun removeKey(k: SecurityKey) = change(R.string.acc_key_removed) {
        it.removeSecurityKey(k)
        keys = it.securityKeys()
        awaitUser { u -> u.securityKeys == keys?.size }
    }

    /** [days] 0: it never expires. / [days] 0: no caduca nunca. */
    fun createToken(name: String, days: Int) = change(null) {
        newToken = it.createLoginToken(name.trim(), days * 24L * 60)
        tokens = it.loginTokens().sortedByDescending { t -> t.created }
    }

    fun removeToken(t: LoginToken) = change(R.string.acc_token_removed) {
        tokens = it.removeLoginTokens(listOf(t.tokenUser)).sortedByDescending { x -> x.created }
    }
}

/**
 * The server's live state, asked every few seconds only while its screen is on sight: leaving it stops the server's
 * timer too. / El estado en vivo del servidor, pedido cada pocos segundos solo mientras su pantalla está a la vista:
 * al salir se para también el temporizador del servidor.
 */
class ServerStateModel(private val session: () -> MeshSession?, private val scope: CoroutineScope) {
    var stats by mutableStateOf<ServerStats?>(null)
        private set
    var error by mutableStateOf<UiError?>(null)
        private set
    private var job: Job? = null

    fun start() {
        if (job != null) return
        error = null
        job = scope.launch {
            try {
                val s = session() ?: throw MeshClientException(MeshFailure.ConnectionLost, "servidor/abrir-detalle/conexion-cortada", "no session")
                s.serverStats(EVERY_MS).collect { stats = it }
            } catch (e: MeshClientException) {
                error = UiError(e.failure, e.key, e.httpStatus)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    companion object { const val EVERY_MS = 3_000L }
}
