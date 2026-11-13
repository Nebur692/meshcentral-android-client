package io.github.nebur692.meshclient.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.OwnAccountModel
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.Screen
import io.github.nebur692.meshclient.core.BackupCodeAction
import io.github.nebur692.meshclient.core.LoginToken
import io.github.nebur692.meshclient.core.SecurityKey
import io.github.nebur692.meshclient.core.ownSettingsLocked
import java.text.DateFormat
import java.util.Date

/** Copies a secret: Android 13+ then hides it in its own clipboard preview. / Copia un secreto: Android 13+ lo oculta en su vista previa. */
internal fun copySecret(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

private fun dateText(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

/**
 * The own account on the server: password, authenticator app, backup codes, security keys and login tokens, as the
 * website's "My account". / La cuenta propia en el servidor, como «Mi cuenta» de la web.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(vm: AppViewModel) {
    val m = vm.ownAccount ?: run { LaunchedEffect(Unit) { vm.show(Screen.Account) }; return }
    val s = vm.session ?: return
    val me = m.me ?: s.user
    val locked = s.ownSettingsLocked
    val context = LocalContext.current
    var changing by remember { mutableStateOf(false) }
    var removingAuth by remember { mutableStateOf(false) }
    var newCodes by remember { mutableStateOf(false) }
    var removingKey by remember { mutableStateOf<SecurityKey?>(null) }
    var removingToken by remember { mutableStateOf<LoginToken?>(null) }
    var creatingToken by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        Column {
            TopAppBar(expandedHeight = BarHeight,
                title = { Text(stringResource(R.string.acc_security)) },
                navigationIcon = { IconButton(onClick = { vm.show(Screen.Account) }) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
            )
            if (m.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
            m.notice?.let { n ->
                LaunchedEffect(n) { kotlinx.coroutines.delay(5000); if (m.notice === n) m.notice = null }
                Surface(color = if (n.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
                    Text(stringResource(n.text) + (n.error?.let { " " + errorText(it) } ?: ""), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottom + 16.dp)) {
            Text(me.name, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
            if (locked) Hint(stringResource(R.string.acc_locked))

            Section(R.string.acc_password)
            Row(R.drawable.ic_lock, stringResource(R.string.acc_password_change), null, enabled = !locked && !m.busy) { changing = true }

            Section(R.string.acc_two_factor)
            Row(R.drawable.ic_phone, stringResource(R.string.acc_auth_app), stringResource(if (me.hasAuthenticator) R.string.acc_on else R.string.acc_off), enabled = !locked && !m.busy) {
                if (me.hasAuthenticator) removingAuth = true else m.startAuthenticator()
            }
            Hint(stringResource(if (me.hasAuthenticator) R.string.acc_auth_remove_hint else R.string.acc_auth_add_hint))
            if (me.hasTwoFactor) {
                Row(R.drawable.ic_doc, stringResource(R.string.acc_codes), stringResource(if (me.hasBackupCodes) R.string.acc_codes_some else R.string.acc_codes_none), enabled = !m.busy) {
                    if (me.hasBackupCodes) m.backupCodes(BackupCodeAction.Show) else newCodes = true
                }
            }
            m.keys?.takeIf { it.isNotEmpty() }?.let { keys ->
                Section(R.string.acc_keys)
                keys.forEach { k -> Row(R.drawable.ic_shield, k.name.ifBlank { stringResource(R.string.acc_key) }, k.type, enabled = !locked && !m.busy) { removingKey = k } }
                Hint(stringResource(R.string.acc_keys_hint))
            }

            Section(R.string.acc_tokens)
            Hint(stringResource(R.string.acc_tokens_hint))
            m.tokens?.forEach { t ->
                Row(R.drawable.ic_person, t.name.ifBlank { t.tokenUser },
                    if (t.expire > 0) stringResource(R.string.acc_token_until, dateText(t.expire)) else stringResource(R.string.acc_token_forever), enabled = !m.busy) { removingToken = t }
            }
            OutlinedButton(onClick = { creatingToken = true }, enabled = !m.busy, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.acc_token_new))
            }
        }
    }

    if (changing) PasswordDialog(m) { changing = false }
    m.setup?.let { setup ->
        var code by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { m.setup = null },
            title = { Text(stringResource(R.string.acc_auth_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.acc_auth_steps))
                    Button(onClick = {
                        // The authenticator on this same phone opens otpauth:// links (Google Authenticator, Microsoft
                        // Authenticator, Aegis…); a QR code would be no use on the phone's own screen.
                        // La app de autenticación de este mismo móvil abre los enlaces otpauth://; un QR no serviría en su propia pantalla.
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, setup.uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { m.notice = io.github.nebur692.meshclient.Notice(R.string.acc_auth_no_app) }
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.acc_auth_open)) }
                    OutlinedButton(onClick = { copySecret(context, "MeshCentral", setup.secret) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.acc_auth_copy))
                    }
                    Text(setup.secret.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text(stringResource(R.string.acc_auth_code)) }, singleLine = true,
                        isError = m.codeWrong, supportingText = if (m.codeWrong) ({ Text(stringResource(R.string.acc_auth_wrong)) }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { m.confirmAuthenticator(code) }, enabled = code.length == 6 && !m.busy) { Text(stringResource(R.string.acc_auth_confirm)) } },
            dismissButton = { TextButton(onClick = { m.setup = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (removingAuth) Ask(stringResource(R.string.acc_auth_remove_title), stringResource(R.string.acc_auth_remove_text), stringResource(R.string.remove_it), { removingAuth = false }) {
        removingAuth = false; m.removeAuthenticator()
    }
    if (newCodes) Ask(stringResource(R.string.acc_codes_new_title), stringResource(R.string.acc_codes_new_text), stringResource(R.string.acc_codes_new), { newCodes = false }, danger = false) {
        newCodes = false; m.backupCodes(BackupCodeAction.New)
    }
    m.codes?.let { codes ->
        AlertDialog(
            onDismissRequest = { m.codes = null },
            title = { Text(stringResource(R.string.acc_codes)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.acc_codes_hint), style = MaterialTheme.typography.bodySmall)
                    codes.chunked(2).forEach { pair ->
                        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            pair.forEach { c ->
                                Text(c.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium,
                                    textDecoration = if (c.used) TextDecoration.LineThrough else null,
                                    color = if (c.used) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { copySecret(context, "MeshCentral", codes.filter { !it.used }.joinToString("\n") { it.code }) }) { Text(stringResource(R.string.acc_codes_copy)) }
                        TextButton(onClick = { m.codes = null; newCodes = true }, enabled = !locked) { Text(stringResource(R.string.acc_codes_new)) }
                    }
                    TextButton(onClick = { m.codes = null; m.backupCodes(BackupCodeAction.Clear) }, enabled = !locked) {
                        Text(stringResource(R.string.acc_codes_clear), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { m.codes = null }) { Text(stringResource(R.string.close)) } },
        )
    }
    removingKey?.let { k ->
        Ask(stringResource(R.string.acc_key_remove_title), k.name, stringResource(R.string.remove_it), { removingKey = null }) { removingKey = null; m.removeKey(k) }
    }
    removingToken?.let { t ->
        Ask(stringResource(R.string.acc_token_remove_title), stringResource(R.string.acc_token_remove_text, t.name.ifBlank { t.tokenUser }), stringResource(R.string.remove_it),
            { removingToken = null }) { removingToken = null; m.removeToken(t) }
    }
    if (creatingToken) TokenDialog(m) { creatingToken = false }
    m.newToken?.let { t ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.acc_token_made)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.acc_token_made_hint))
                    Text(stringResource(R.string.username), style = MaterialTheme.typography.labelMedium)
                    Text(t.tokenUser, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { copySecret(context, "MeshCentral", t.tokenUser) }) { Text(stringResource(R.string.acc_copy_user)) }
                    Text(stringResource(R.string.password), style = MaterialTheme.typography.labelMedium)
                    Text(t.tokenPass.orEmpty(), fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { copySecret(context, "MeshCentral", t.tokenPass.orEmpty()) }) { Text(stringResource(R.string.acc_copy_password)) }
                }
            },
            confirmButton = { TextButton(onClick = { m.newToken = null }) { Text(stringResource(R.string.acc_token_saved)) } },
        )
    }
}

@Composable
private fun PasswordDialog(m: OwnAccountModel, close: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val start = remember { mutableIntStateOf(m.passwordDone) }
    // Closed once the server said it changed. / Se cierra cuando el servidor dijo que cambió.
    LaunchedEffect(m.passwordDone) { if (m.passwordDone != start.intValue) close() }
    val mismatch = again.isNotEmpty() && again != new
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.acc_password_change)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple(current, R.string.acc_password_current) { v: String -> current = v },
                    Triple(new, R.string.acc_password_new) { v: String -> new = v },
                    Triple(again, R.string.acc_password_again) { v: String -> again = v }).forEach { (value, label, set) ->
                    OutlinedTextField(value, { set(it.take(256)) }, label = { Text(stringResource(label)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), isError = label == R.string.acc_password_again && mismatch,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                }
                if (mismatch) Text(stringResource(R.string.acc_password_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { m.changePassword(current, new) }, enabled = current.isNotEmpty() && new.isNotEmpty() && new == again && !m.busy) {
                Text(stringResource(R.string.acc_password_change_short))
            }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TokenDialog(m: OwnAccountModel, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var days by remember { mutableIntStateOf(30) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.acc_token_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text(stringResource(R.string.acc_token_name)) }, singleLine = true,
                    placeholder = { Text("meshctrl") }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.acc_token_expires), style = MaterialTheme.typography.labelLarge)
                listOf(1, 7, 30, 90, 0).forEach { d ->
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.RadioButton) { days = d },
                        verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = days == d, onClick = null)
                        Text(if (d == 0) stringResource(R.string.acc_token_forever)
                            else androidx.compose.ui.res.pluralStringResource(R.plurals.acc_token_days, d, d), Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { m.createToken(name, days); close() }, enabled = name.isNotBlank() && !m.busy) { Text(stringResource(R.string.acc_token_create)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Ask(title: String, text: String, yes: String, no: () -> Unit, danger: Boolean = true, onYes: () -> Unit) {
    AlertDialog(
        onDismissRequest = no,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onYes) { Text(yes, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = no) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Section(text: Int) {
    Text(stringResource(text), Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Row(icon: Int, title: String, subtitle: String?, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * The server's live state: memory, load and what is connected, refreshed every few seconds only while on screen.
 * El estado en vivo del servidor: memoria, carga y lo conectado, actualizado cada pocos segundos solo mientras se ve.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerStateScreen(vm: AppViewModel) {
    val m = vm.serverState
    // Asked only while the screen is started: hidden or left, the server stops sending. / Solo mientras se ve.
    LifecycleStartEffect(m) {
        m.start()
        onStopOrDispose { m.stop() }
    }
    Scaffold(topBar = {
        TopAppBar(expandedHeight = BarHeight,
            title = { Text(stringResource(R.string.srv_state)) },
            navigationIcon = { IconButton(onClick = { vm.show(Screen.Account) }) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
        )
    }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottom + 16.dp)) {
            m.error?.let { Hint(errorText(it)) }
            val st = m.stats
            if (st == null) {
                if (m.error == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp))
                    Hint(stringResource(R.string.srv_waiting))
                }
                return@Column
            }
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            Section(R.string.srv_memory)
            val total = st.totalMemory.coerceAtLeast(1)
            Gauge(stringResource(R.string.srv_memory_used, gb(st.usedMemory, locale), gb(st.totalMemory, locale)), st.usedMemory.toFloat() / total)
            if (st.cpuAverage.size >= 3) {
                Section(R.string.srv_load)
                Hint(stringResource(R.string.srv_load_values, "%.2f".format(locale, st.cpuAverage[0]), "%.2f".format(locale, st.cpuAverage[1]), "%.2f".format(locale, st.cpuAverage[2])))
            }
            Section(R.string.srv_now)
            listOf("ConnectedUsers" to R.string.srv_users_connected, "UsersSessions" to R.string.srv_user_sessions, "AgentSessions" to R.string.srv_agents,
                "RelaySessions" to R.string.srv_relays, "UserAccounts" to R.string.srv_accounts, "DeviceGroups" to R.string.srv_groups).forEach { (k, label) ->
                st.state[k]?.let { Count(stringResource(label), it) }
            }
            val errors = st.agentErrors.filterValues { it > 0 }
            if (errors.isNotEmpty() || (st.state["RelayErrors"] ?: 0) > 0) {
                Section(R.string.srv_errors)
                st.state["RelayErrors"]?.let { Count(stringResource(R.string.srv_relay_errors), it) }
                errors.forEach { (k, v) -> Count(k, v) }
            }
        }
    }
}

private fun gb(bytes: Long, locale: java.util.Locale) = "%.1f".format(locale, bytes / 1_073_741_824.0)

@Composable
private fun Gauge(label: String, fraction: Float) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Count(label: String, value: Long) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}
