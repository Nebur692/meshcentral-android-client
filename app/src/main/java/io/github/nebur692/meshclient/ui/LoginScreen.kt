package io.github.nebur692.meshclient.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.core.CertificateTrust

@Composable
fun LoginScreen(vm: AppViewModel) {
    val f = vm.form
    var showPassword by rememberSaveable { mutableStateOf(false) }
    // Signing in closes the keyboard, and an error scrolls into view: otherwise it appears hidden under the keyboard.
    // Entrar cierra el teclado y un error se desplaza a la vista: si no, sale escondido debajo del teclado.
    val keyboard = LocalSoftwareKeyboardController.current
    val errorView = remember { BringIntoViewRequester() }
    val signIn = { keyboard?.hide(); vm.connect() }
    LaunchedEffect(f.error) { if (f.error != null) errorView.bringIntoView() }
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // The same logo as the Windows app. / El mismo logo que la app de Windows.
            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(56.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.login_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (vm.savedServers.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.saved_servers), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.savedServers.forEach { s ->
                            val host = s.address.removePrefix("https://").trimEnd('/')
                            InputChip(
                                selected = f.server == s.address,
                                onClick = { vm.pick(s) },
                                label = { Text("$host · ${s.username}") },
                                trailingIcon = {
                                    val forget = stringResource(R.string.forget_server, host)
                                    IconButton(onClick = { vm.forget(s) }, modifier = Modifier.size(32.dp).semantics { contentDescription = forget }) {
                                        Icon(painterResource(R.drawable.ic_close), null, modifier = Modifier.size(18.dp))
                                    }
                                },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = f.server, onValueChange = { v -> vm.edit { copy(server = v) } },
                label = { Text(stringResource(R.string.server)) }, placeholder = { Text(stringResource(R.string.server_hint)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_globe), null) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = f.username, onValueChange = { v -> vm.edit { copy(username = v) } },
                label = { Text(stringResource(R.string.username)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_person), null) },
                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = f.password, onValueChange = { v -> vm.edit { copy(password = v) } },
                label = { Text(stringResource(R.string.password)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_lock), null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(painterResource(if (showPassword) R.drawable.ic_eye_off else R.drawable.ic_eye),
                            stringResource(if (showPassword) R.string.hide_password else R.string.show_password))
                    }
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (!f.busy) signIn() }),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
            )
            // The whole row is the checkbox: a person taps the sentence, not the little square.
            // Toda la fila es la casilla: una persona toca la frase, no el cuadradito.
            Row(
                Modifier.fillMaxWidth().toggleable(value = f.remember, role = Role.Checkbox, onValueChange = { v -> vm.edit { copy(remember = v) } }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = f.remember, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                Text(stringResource(R.string.remember_password), style = MaterialTheme.typography.bodyMedium)
            }

            f.error?.let { Box(Modifier.bringIntoViewRequester(errorView)) { ErrorCard(it) } }

            Button(onClick = signIn, enabled = !f.busy && f.server.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (f.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Text(stringResource(R.string.connecting), Modifier.padding(start = 12.dp))
                } else {
                    Text(stringResource(R.string.connect), style = MaterialTheme.typography.titleMedium)
                }
            }
            // The single sign-on the server offers (read from its login page), as on its website.
            // El inicio de sesión único que ofrece el servidor (leído de su página de entrada), como en su web.
            if (vm.signOnMethods.isNotEmpty()) {
                Text(stringResource(R.string.signon_or), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                vm.signOnMethods.forEach { m ->
                    OutlinedButton(onClick = { keyboard?.hide(); vm.startSignOn(m) }, enabled = !f.busy && f.server.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(20.dp))
                        Text(stringResource(R.string.signon_button, m.displayName), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    f.certificate?.let { c ->
        AlertDialog(
            onDismissRequest = vm::dismissPrompts,
            title = { Text(stringResource(if (c.changed) R.string.cert_changed_title else R.string.cert_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(if (c.changed) R.string.cert_changed_body else R.string.cert_body, c.host))
                    Text(stringResource(R.string.cert_fingerprint), style = MaterialTheme.typography.labelLarge)
                    Text(CertificateTrust.display(c.sha256), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = vm::acceptCertificate) { Text(stringResource(R.string.accept)) } },
            dismissButton = { TextButton(onClick = vm::dismissPrompts) { Text(stringResource(R.string.cancel)) } },
        )
    }

    f.twoFactor?.let { t ->
        var code by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = vm::dismissPrompts,
            title = { Text(stringResource(R.string.tfa_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(when { t.wrong -> R.string.tfa_wrong; t.emailSent -> R.string.tfa_email_sent; else -> R.string.tfa_body }))
                    OutlinedTextField(
                        value = code, onValueChange = { code = it.filter(Char::isLetterOrDigit).take(12) },
                        label = { Text(stringResource(R.string.tfa_code)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (code.isNotEmpty()) vm.submitCode(code) }),
                    )
                    if (t.emailAvailable && !t.emailSent) TextButton(onClick = vm::emailCode) { Text(stringResource(R.string.tfa_email)) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.submitCode(code) }, enabled = code.isNotEmpty()) { Text(stringResource(R.string.accept)) } },
            dismissButton = { TextButton(onClick = vm::dismissPrompts) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
