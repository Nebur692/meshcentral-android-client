package io.github.nebur692.meshclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.UiError
import io.github.nebur692.meshclient.core.MeshFailure

/** What went wrong in plain words, and the key support can look up. / Qué falló en palabras claras, y la clave para soporte. */
@Composable
fun ErrorCard(error: UiError) {
    val text = errorText(error)
    Surface(
        color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.error_code, error.key), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** The failure in plain words. / El fallo en palabras claras. */
@Composable
fun errorText(error: UiError): String =
    if (error.missingFields) stringResource(R.string.err_missing) else when (error.failure) {
        MeshFailure.NotAllowed -> stringResource(R.string.err_not_allowed)
        MeshFailure.NotFound -> stringResource(R.string.err_not_found)
        MeshFailure.DeviceOffline -> stringResource(R.string.err_device_offline)
        MeshFailure.InvalidAddress -> stringResource(R.string.err_address)
        MeshFailure.NameNotResolved -> stringResource(R.string.err_dns)
        MeshFailure.ServerUnreachable -> stringResource(R.string.err_unreachable)
        MeshFailure.Timeout -> stringResource(R.string.err_timeout)
        MeshFailure.HttpError -> stringResource(R.string.err_http, error.httpStatus ?: 0)
        MeshFailure.InvalidCredentials -> stringResource(R.string.err_credentials)
        MeshFailure.TooManyAttempts -> stringResource(R.string.err_too_many)
        MeshFailure.ToolsNotAllowed -> stringResource(R.string.err_notools)
        MeshFailure.EmailValidationRequired -> stringResource(R.string.err_email_validation)
        MeshFailure.UntrustedCertificate -> stringResource(R.string.err_certificate)
        MeshFailure.ConnectionLost -> stringResource(R.string.err_lost)
        MeshFailure.SessionExpired -> stringResource(R.string.err_session)
        MeshFailure.UnexpectedResponse -> stringResource(R.string.err_unexpected)
        MeshFailure.Rejected, MeshFailure.TwoFactorRequired, MeshFailure.InvalidTwoFactorCode -> stringResource(R.string.err_rejected)
    }
