package io.github.nebur692.meshclient.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.Screen
import io.github.nebur692.meshclient.core.RecordedKind
import io.github.nebur692.meshclient.core.Recording
import io.github.nebur692.meshclient.core.recordingPlayerUrl
import io.github.nebur692.meshclient.core.recordingsPageUrl
import java.text.DateFormat
import java.util.Date

/**
 * The server's recorded sessions: who, which device, what kind and when; tapped, it is downloaded (with a web session)
 * or opened in the browser. / Las sesiones grabadas: quién, qué equipo, qué tipo y cuándo; al tocarla se baja (con
 * sesión web) o se abre en el navegador.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(vm: AppViewModel) {
    val m = vm.recordings ?: run { LaunchedEffect(Unit) { vm.show(Screen.Account) }; return }
    val back = { vm.leaveRecordings() }
    BackHandler(onBack = back)
    LaunchedEffect(m) { m.load() }
    val context = LocalContext.current
    var picked by remember { mutableStateOf<Recording?>(null) }
    var saving by remember { mutableStateOf<Recording?>(null) }
    val pickWhereToSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val r = saving; saving = null
        if (uri != null && r != null) m.download(r, uri)
    }
    Scaffold(topBar = {
        Column {
            TopAppBar(expandedHeight = BarHeight,
                title = { Text(stringResource(R.string.recordings)) },
                navigationIcon = { IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
                actions = { IconButton(onClick = m::load, enabled = !m.loading) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.refresh)) } },
            )
            OutlinedTextField(m.filter, { m.filter = it }, placeholder = { Text(stringResource(R.string.recordings_search)) }, singleLine = true,
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(24.dp))
            val d = m.downloading
            when {
                d != null -> Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.recordings_downloading, d.first, android.text.format.Formatter.formatShortFileSize(context, d.second)),
                        Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
                m.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> HorizontalDivider()
            }
            (m.error?.let { errorText(it) to true } ?: m.notice?.let { n ->
                LaunchedEffect(n) { kotlinx.coroutines.delay(4000); if (m.notice === n) m.notice = null }
                (n.error?.let { stringResource(n.text) + " " + errorText(it) } ?: stringResource(n.text)) to (n.error != null)
            })?.let { (text, bad) ->
                Surface(color = if (bad) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
                    Text(text, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val q = m.filter.trim().lowercase()
        val list = m.list.orEmpty().filter { r ->
            q.isEmpty() || listOfNotNull(r.deviceName, r.groupName, r.fileName).any { q in it.lowercase() } ||
                r.userIds.any { q in it.substringAfterLast('/').lowercase() }
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (m.list?.isEmpty() == true) Text(stringResource(R.string.recordings_none), Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
                items(list, key = { it.fileName }) { r ->
                    val who = r.userIds.joinToString(", ") { it.substringAfterLast('/') }
                    RecordingRow(r, who, onClick = if (r.present) ({ picked = r }) else null)
                }
            }
        }
    }
    picked?.let { r ->
        val s = vm.session
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(r.deviceName ?: r.fileName) },
            text = { Text(stringResource(if (m.canDownload) R.string.recordings_open_hint else R.string.recordings_browser_hint)) },
            confirmButton = {
                Row {
                    if (s != null && m.canDownload && (r.kind == RecordedKind.Desktop || r.kind == RecordedKind.Terminal || r.fileName.endsWith(".mcrec")))
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, s.recordingPlayerUrl(r).toUri())) }
                            picked = null
                        }) { Text(stringResource(R.string.recordings_play)) }
                    TextButton(onClick = {
                        if (m.canDownload) { saving = r; pickWhereToSave.launch(r.fileName) }
                        else if (s != null) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, s.recordingsPageUrl().toUri())) }
                        picked = null
                    }) { Text(stringResource(if (m.canDownload) R.string.recordings_download else R.string.recordings_open_web)) }
                }
            },
            dismissButton = { TextButton(onClick = { picked = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun kindName(k: RecordedKind): String = stringResource(when (k) {
    RecordedKind.Desktop -> R.string.act_desktop
    RecordedKind.Terminal, RecordedKind.UserShell -> R.string.act_terminal
    RecordedKind.AdminPowerShell, RecordedKind.UserPowerShell -> R.string.recordings_powershell
    RecordedKind.Files -> R.string.act_files
    RecordedKind.Chat -> R.string.act_chat
    RecordedKind.AmtWsman, RecordedKind.AmtRedirection -> R.string.recordings_amt
    RecordedKind.Other -> R.string.recordings_other
})

@Composable
private fun RecordingRow(r: Recording, who: String, onClick: (() -> Unit)?) {
    val context = LocalContext.current
    val icon = when (r.kind) {
        RecordedKind.Desktop -> R.drawable.ic_monitor
        RecordedKind.Files -> R.drawable.ic_folder
        RecordedKind.Chat -> R.drawable.ic_forum
        else -> R.drawable.ic_terminal
    }
    val minutes = r.seconds / 60; val secs = r.seconds % 60
    val length = if (minutes > 0) "$minutes min $secs s" else "$secs s"
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
        .padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = if (r.present) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull(r.deviceName, kindName(r.kind)).joinToString(" · "), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(
                r.startMs.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) },
                length, who.ifEmpty { null },
                if (r.present) android.text.format.Formatter.formatShortFileSize(context, r.bytes) else stringResource(R.string.recordings_gone),
            ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}
