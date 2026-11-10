package io.github.nebur692.meshclient.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.core.CertificateTrust
import io.github.nebur692.meshclient.core.SingleSignOn
import io.github.nebur692.meshclient.data.SavedServer

/** A server address as a person reads it: no scheme, no slash. / Una dirección como la lee una persona: sin esquema ni barra. */
fun hostOf(address: String) = address.removePrefix("https://").removePrefix("http://").trimEnd('/')

@Composable
fun LoginScreen(vm: AppViewModel) {
    val f = vm.form
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // The same logo as the Windows app. / El mismo logo que la app de Windows.
                Image(painterResource(R.drawable.ic_logo), null, Modifier.size(36.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            if (f.expanded || vm.savedServers.isEmpty()) LoginForm(vm) else SavedAccounts(vm)
        }
    }
    LoginPrompts(vm)
}

/**
 * Coming back: the saved accounts, and one big button where the thumb is. With the fingerprint set, that button asks
 * for it. / Al volver: las cuentas guardadas y un botón grande donde llega el pulgar. Con la huella puesta, ese botón la pide.
 */
@Composable
private fun SavedAccounts(vm: AppViewModel) {
    val f = vm.form
    val chosen = vm.savedServers.firstOrNull { it.address == f.server } ?: vm.savedServers.first()
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        ) {
            Text(stringResource(R.string.welcome_back), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.choose_account), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            vm.savedServers.forEach { s -> AccountCard(s, s.address == chosen.address, onPick = { vm.pick(s) }, onForget = { vm.forget(s) }) }
            f.error?.let { ErrorCard(it) }
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val bio = chosen.hasBiometric && vm.biometricsAvailable
            Button(onClick = { vm.signInSaved(chosen) }, enabled = !f.busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                when {
                    f.busy -> {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Text(stringResource(R.string.connecting), Modifier.padding(start = 12.dp))
                    }
                    bio -> {
                        Icon(painterResource(R.drawable.ic_fingerprint), null, Modifier.size(22.dp))
                        Text(stringResource(R.string.sign_in_fingerprint), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    chosen.singleSignOn != null && !chosen.hasSession ->
                        Text(stringResource(R.string.signon_button, SingleSignOn(chosen.singleSignOn).displayName), style = MaterialTheme.typography.titleMedium)
                    else -> Text(stringResource(R.string.connect), style = MaterialTheme.typography.titleMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { vm.showForm(chosen) }, enabled = !f.busy, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text(stringResource(if (chosen.singleSignOn != null) R.string.other_way else R.string.with_password), maxLines = 1)
                }
                OutlinedButton(onClick = { vm.showForm(null) }, enabled = !f.busy, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text(stringResource(R.string.other_account), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AccountCard(s: SavedServer, selected: Boolean, onPick: () -> Unit, onForget: () -> Unit) {
    val host = hostOf(s.address)
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onPick),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Initial(s.username, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.weight(1f)) {
                Text(s.username, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(host, s.singleSignOn?.let { SingleSignOn(it).displayName }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.hasBiometric) Icon(painterResource(R.drawable.ic_fingerprint), stringResource(R.string.has_fingerprint), Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary)
            val forget = stringResource(R.string.forget_server, host)
            IconButton(onClick = onForget, modifier = Modifier.semantics { contentDescription = forget }) {
                Icon(painterResource(R.drawable.ic_close), null, Modifier.size(18.dp))
            }
        }
    }
}

/** A person's initial in a circle. / La inicial de una persona en un círculo. */
@Composable
fun Initial(name: String, background: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color, size: Int = 44) {
    Surface(shape = CircleShape, color = background, contentColor = content, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(name.trim().firstOrNull()?.uppercase() ?: "?", style = if (size >= 52) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
        }
    }
}

/** The whole form: the first time, another account, or the password of a saved one. / El formulario entero. */
@Composable
private fun LoginForm(vm: AppViewModel) {
    val f = vm.form
    var showPassword by rememberSaveable { mutableStateOf(false) }
    // Signing in closes the keyboard, and an error scrolls into view: otherwise it appears hidden under the keyboard.
    // Entrar cierra el teclado y un error se desplaza a la vista: si no, sale escondido debajo del teclado.
    val keyboard = LocalSoftwareKeyboardController.current
    val errorView = remember { BringIntoViewRequester() }
    val signIn = { keyboard?.hide(); vm.connect() }
    LaunchedEffect(f.error) { if (f.error != null) errorView.bringIntoView() }
    BackHandler(enabled = vm.savedServers.isNotEmpty()) { vm.showAccounts() }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.sign_in_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.login_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = hostOf(f.server), onValueChange = { v -> vm.edit { copy(server = v) } },
                label = { Text(stringResource(R.string.server)) }, placeholder = { Text(stringResource(R.string.server_hint)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_globe), null) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = f.username, onValueChange = { v -> vm.edit { copy(username = v) } },
                label = { Text(stringResource(R.string.username)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_person), null) },
                singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
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
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
            )
            // The whole row is the checkbox: a person taps the sentence, not the little square.
            // Toda la fila es la casilla: una persona toca la frase, no el cuadradito.
            Row(
                Modifier.fillMaxWidth().toggleable(value = f.remember, role = Role.Checkbox, onValueChange = { v -> vm.edit { copy(remember = v) } }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = f.remember, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                Column {
                    Text(stringResource(R.string.remember_password), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(if (vm.biometricsAvailable) R.string.remember_password_hint_bio else R.string.remember_password_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            f.error?.let { Box(Modifier.bringIntoViewRequester(errorView)) { ErrorCard(it) } }
        }
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = signIn, enabled = !f.busy && f.server.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text(stringResource(R.string.signon_or), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.weight(1f))
                }
                vm.signOnMethods.forEach { m ->
                    OutlinedButton(onClick = { keyboard?.hide(); vm.startSignOn(m) }, enabled = !f.busy && f.server.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(painterResource(R.drawable.ic_shield), null, Modifier.size(20.dp))
                        Text(stringResource(R.string.signon_button, m.displayName), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (vm.savedServers.isNotEmpty()) TextButton(onClick = vm::showAccounts, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.saved_accounts))
            }
        }
    }
}

/** The certificate and second-factor questions, over either view. / Las preguntas del certificado y del doble factor. */
@Composable
private fun LoginPrompts(vm: AppViewModel) {
    val f = vm.form
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
