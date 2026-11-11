package io.github.nebur692.meshclient.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.BuildConfig
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.Screen
import io.github.nebur692.meshclient.core.CertificateTrust

private const val PRIVACY_URL = "https://nebur692.github.io/meshcentral-android-client/PRIVACY.html"
private const val SOURCE_URL = "https://github.com/Nebur692/meshcentral-android-client"

/** Who is signed in where, the fingerprint switch, and the app. / Quién ha entrado y dónde, el interruptor de la huella y la app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(vm: AppViewModel) {
    val s = vm.session ?: return
    val uri = LocalUriHandler.current
    val saved = vm.savedServers.firstOrNull { it.address == s.server.toString() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_account)) },
            navigationIcon = { IconButton(onClick = { vm.show(Screen.Devices) }) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
        )
    }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottom + 16.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Initial(s.user.name, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, size = 56)
                Column {
                    Text(s.user.name, style = MaterialTheme.typography.titleLarge)
                    Text(listOfNotNull(hostOf(s.server.toString()), s.serverInfo.version?.let { "MeshCentral $it" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Heading(R.string.sec_security)
            if (vm.biometricsAvailable) {
                val on = saved?.hasBiometric == true
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .toggleable(on, enabled = on || vm.canSetBiometric, role = Role.Switch, onValueChange = vm::setBiometric)
                    .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(painterResource(R.drawable.ic_fingerprint), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.sign_in_fingerprint), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.fingerprint_here), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = on, onCheckedChange = null, enabled = on || vm.canSetBiometric)
                }
            }
            // "Accepted" only when the person accepted it; a public one is simply trusted. / «Aceptado» solo si lo aceptó la persona.
            val pinned = s.trust.pinnedSha256
            Item(R.drawable.ic_shield, stringResource(if (pinned != null) R.string.account_certificate else R.string.account_certificate_server),
                pinned?.let(CertificateTrust::display) ?: stringResource(R.string.account_certificate_system))

            Heading(R.string.about)
            Item(R.drawable.ic_lock, stringResource(R.string.privacy), null) { uri.openUri(PRIVACY_URL) }
            Item(R.drawable.ic_doc, stringResource(R.string.source_code), stringResource(R.string.source_code_hint)) { uri.openUri(SOURCE_URL) }
            Item(R.drawable.ic_info, stringResource(R.string.app_version, BuildConfig.VERSION_NAME), null)

            OutlinedButton(onClick = vm::signOut, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp).height(52.dp)) {
                Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Heading(text: Int) {
    Text(stringResource(text), Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Item(icon: Int, title: String, subtitle: String?, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
        .padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
