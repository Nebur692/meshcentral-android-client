package io.github.nebur692.meshclient.core

/** What went wrong, as the app explains it. / Qué salió mal, tal como lo explica la app. */
enum class MeshFailure {
    InvalidAddress, NameNotResolved, ServerUnreachable, Timeout, UntrustedCertificate, HttpError,
    InvalidCredentials, TwoFactorRequired, InvalidTwoFactorCode, TooManyAttempts, ToolsNotAllowed,
    EmailValidationRequired, SessionExpired, Rejected, ConnectionLost, UnexpectedResponse,
    /** This account has no rights for it on that device. / Esta cuenta no tiene permiso para eso en ese equipo. */
    NotAllowed,
    /** It needs the device's agent connected. / Hace falta el agente del equipo conectado. */
    DeviceOffline,
}

/**
 * A failure with its error key (feature/action/kind, the same keys as the Windows app), which the app turns into a
 * support code. / Un fallo con su clave de error (función/acción/tipo, las mismas que la app de Windows), que la app
 * convierte en un código de soporte.
 */
class MeshClientException(
    val failure: MeshFailure,
    val key: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** What the server said, as it said it. / Lo que dijo el servidor, tal cual. */
    var serverMessage: String? = null
        internal set
    var certificateSha256: String? = null
        internal set
    var certificateSubject: String? = null
        internal set
    var httpStatus: Int? = null
        internal set
    var emailCodeAvailable: Boolean = false
        internal set
    var smsCodeAvailable: Boolean = false
        internal set
    var emailCodeSent: Boolean = false
        internal set
}
