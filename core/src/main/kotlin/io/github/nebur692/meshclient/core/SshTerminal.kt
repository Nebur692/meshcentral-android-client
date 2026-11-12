package io.github.nebur692.meshclient.core

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo as JschUserInfo
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * A terminal the terminal screen can show, whoever carries it (the agent's own terminal, or SSH).
 * Una terminal que la pantalla de terminal puede enseñar, la lleve quien la lleve (la del agente, o SSH).
 */
interface TerminalConnection {
    /** What the other side prints, in order; it ends with the reason as its error (none if closed here). */
    val output: Flow<ByteArray>
    val isRecorded: Boolean
    fun write(text: String)
    fun resize(cols: Int, rows: Int)
    fun close()
}

/** How to sign in to an SSH server: a password, a private key (OpenSSH or PEM, with its passphrase), or both. */
data class SshLogin(val user: String, val password: String? = null, val privateKey: ByteArray? = null, val passphrase: String? = null) {
    override fun equals(other: Any?) = other is SshLogin && other.user == user
    override fun hashCode() = user.hashCode()
    override fun toString() = "SshLogin($user)" // never the secrets / nunca los secretos
}

/** The identity an SSH server shows: e.g. ssh-ed25519 and SHA256:… as OpenSSH writes it. / La identidad del servidor SSH. */
data class SshHostKey(val algorithm: String, val fingerprint: String)

/**
 * An SSH terminal to a device, or to a machine the device reaches, carried by its agent: the app listens on a private
 * 127.0.0.1 port ([PortMapping]) and its own SSH client (JSch) connects there. The server's identity is checked by the
 * caller ([trust]), as OpenSSH's known_hosts does. Ported from the Windows app (SSH.NET there).
 * Una terminal SSH a un equipo, o a una máquina a la que llega el equipo, por su agente: la app escucha en un puerto
 * privado de 127.0.0.1 y su propio cliente SSH (JSch) conecta ahí. La identidad del servidor la comprueba quien llama.
 */
class SshTerminal private constructor(
    private val mapping: PortMapping,
    private val ssh: Session,
    private val shell: ChannelShell,
    private val fromServer: InputStream,
    private val toServer: OutputStream,
    val hostKey: SshHostKey,
) : TerminalConnection {
    private val closed = AtomicBoolean(false)

    override val isRecorded = false

    override val output: Flow<ByteArray> = flow {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = try { fromServer.read(buffer) } catch (e: IOException) {
                if (closed.get()) return@flow
                throw lost(e)
            }
            if (n < 0) break
            if (n > 0) emit(buffer.copyOf(n))
        }
        writeFailure?.let { throw it }
        if (!closed.get()) throw MeshClientException(MeshFailure.ConnectionLost, "ssh/abrir-ssh/sesion-cerrada", "The SSH session ended.")
    }.flowOn(Dispatchers.IO)

    /** Why writing failed, told by [output] when it ends. / Por qué falló escribir, lo dice [output] al acabar. */
    @Volatile private var writeFailure: MeshClientException? = null

    /**
     * Writes go out on a thread of their own, one after another: the terminal calls from the main thread, where Android
     * forbids the network (seen on the emulator: the first key cut the session), and the stream cannot take two at once.
     * Las escrituras salen por un hilo propio, una tras otra: la terminal llama desde el hilo principal, donde Android
     * prohíbe la red (visto en el emulador: la primera tecla cortaba la sesión), y el flujo no admite dos a la vez.
     */
    override fun write(text: String) {
        if (closed.get()) throw lost(null)
        writes.execute {
            try {
                toServer.write(text.toByteArray(Charsets.UTF_8))
                toServer.flush()
            } catch (e: IOException) {
                if (!closed.get()) { writeFailure = lost(e); runCatching { shell.disconnect() } }
            }
        }
    }

    private val writes = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ssh-write").apply { isDaemon = true } }

    // Also network: on the same thread, in order with the keys. / También es red: por el mismo hilo, en orden con las teclas.
    override fun resize(cols: Int, rows: Int) { if (!closed.get()) writes.execute { runCatching { shell.setPtySize(cols, rows, 0, 0) } } }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        writes.shutdown()
        runCatching { shell.disconnect() }
        runCatching { ssh.disconnect() }
        mapping.close()
    }

    companion object {
        init {
            // The same algorithms everywhere (Android lacks Java 11+'s X25519 and Java 15+'s EdDSA): Bouncy Castle's.
            // Los mismos algoritmos en todas partes (Android no trae el X25519 ni el EdDSA de Java): los de Bouncy Castle.
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
            JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        }

        private fun lost(e: Throwable?) = MeshClientException(MeshFailure.ConnectionLost, "ssh/abrir-ssh/conexion-cortada", "The SSH connection was lost.", e)

        /** SHA256:… as OpenSSH writes it. / Como lo escribe OpenSSH. */
        fun fingerprint(key: ByteArray): String =
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

        suspend fun open(
            session: MeshSession, nodeId: String, deviceName: String, host: String?, port: Int,
            login: SshLogin, trust: (SshHostKey) -> Boolean, cols: Int, rows: Int,
        ): SshTerminal = withContext(Dispatchers.IO) {
            if (login.user.isBlank()) throw MeshClientException(MeshFailure.Rejected, "ssh/iniciar-sesion/falta-dato", "The SSH account name is missing.")
            if (login.password.isNullOrEmpty() && login.privateKey == null)
                throw MeshClientException(MeshFailure.Rejected, "ssh/iniciar-sesion/falta-dato", "A password or a private key is needed.")
            val jsch = JSch()
            if (login.privateKey != null) try {
                jsch.addIdentity("key", login.privateKey, null, login.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray())
            } catch (e: JSchException) {
                throw MeshClientException(MeshFailure.Rejected, "ssh/iniciar-sesion/dato-invalido",
                    "The private key could not be read (wrong passphrase, or not an OpenSSH or PEM key).", e)
            }
            var shown: SshHostKey? = null
            var trusted = false
            // The identity goes to the caller, as known_hosts would; nothing is sent before it says yes.
            // La identidad va a quien llama, como haría known_hosts; no se manda nada antes de que diga que sí.
            jsch.hostKeyRepository = object : HostKeyRepository {
                override fun check(host: String?, key: ByteArray): Int {
                    val k = SshHostKey(HostKey(host ?: "", key).type, fingerprint(key))
                    shown = k
                    trusted = trust(k)
                    return if (trusted) HostKeyRepository.OK else HostKeyRepository.NOT_INCLUDED
                }
                override fun add(hostkey: HostKey?, ui: JschUserInfo?) = Unit
                override fun remove(host: String?, type: String?) = Unit
                override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
                override fun getKnownHostsRepositoryID() = "meshclient"
                override fun getHostKey(): Array<HostKey> = emptyArray()
                override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
            }
            val mapping = PortMapping.start(session, PortTarget(nodeId, deviceName, port, host))
            var ssh: Session? = null
            try {
                ssh = jsch.getSession(login.user, "127.0.0.1", mapping.localPort).apply {
                    login.password?.takeIf { it.isNotEmpty() }?.let { setPassword(it) }
                    setConfig("StrictHostKeyChecking", "yes")
                    setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
                    // One go per method: answering every prompt again made sshd cut with "Too many authentication failures".
                    // Un intento por método: contestar otra vez cada pregunta hacía que sshd cortase por demasiados fallos.
                    setConfig("MaxAuthTries", "2")
                    var asked = 0
                    userInfo = object : JschUserInfo, UIKeyboardInteractive {
                        override fun getPassphrase() = login.passphrase
                        override fun getPassword() = login.password
                        override fun promptPassword(message: String?) = !login.password.isNullOrEmpty()
                        override fun promptPassphrase(message: String?) = !login.passphrase.isNullOrEmpty()
                        override fun promptYesNo(message: String?) = false
                        override fun showMessage(message: String?) = Unit
                        override fun promptKeyboardInteractive(destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?) =
                            if (asked++ > 0) null else login.password?.takeIf { it.isNotEmpty() }?.let { p -> Array(prompt?.size ?: 0) { p } }
                    }
                }
                ssh.connect(session.timeoutMs.toInt())
                val shell = ssh.openChannel("shell") as ChannelShell
                shell.setPtyType("xterm-256color", cols, rows, 0, 0)
                val input = shell.inputStream
                val outputStream = shell.outputStream
                shell.connect(session.timeoutMs.toInt())
                SshTerminal(mapping, ssh, shell, input, outputStream, shown ?: SshHostKey("", ""))
            } catch (e: JSchException) {
                runCatching { ssh?.disconnect() }
                val why = mapping.stats.value.lastError
                mapping.close()
                val text = e.message.orEmpty()
                throw when {
                    shown != null && !trusted -> MeshClientException(MeshFailure.UntrustedCertificate, "ssh/abrir-ssh/tls",
                        "The SSH server's identity (${shown!!.fingerprint}) was not trusted, so nothing was sent to it.", e)
                    text.startsWith("Auth fail") || text.startsWith("Auth cancel") || "authentication failures" in text -> MeshClientException(MeshFailure.InvalidCredentials, "ssh/iniciar-sesion/credencial-invalida",
                        "The SSH server did not accept \"${login.user}\" with that password or key.", e)
                    why != null -> why
                    else -> MeshClientException(MeshFailure.ConnectionLost, "ssh/abrir-ssh/sin-conexion",
                        "Nothing answered as an SSH server at ${host?.takeIf { it.isNotBlank() } ?: deviceName}:$port (is SSH running there?).", e)
                }
            } catch (e: Throwable) {
                runCatching { ssh?.disconnect() }
                mapping.close()
                throw e
            }
        }
    }
}
