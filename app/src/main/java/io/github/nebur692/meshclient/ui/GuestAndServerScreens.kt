package io.github.nebur692.meshclient.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.core.net.toUri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.Screen
import io.github.nebur692.meshclient.UiError
import io.github.nebur692.meshclient.core.Device
import io.github.nebur692.meshclient.core.DeviceNotify
import io.github.nebur692.meshclient.core.DeviceShare
import io.github.nebur692.meshclient.core.InstalledProgram
import io.github.nebur692.meshclient.core.LoginEntry
import io.github.nebur692.meshclient.core.LoginKind
import io.github.nebur692.meshclient.core.MeshClientException
import io.github.nebur692.meshclient.core.MeshFailure
import io.github.nebur692.meshclient.core.SERVER_FILE_DOWNLOAD_LIMIT
import io.github.nebur692.meshclient.core.SERVER_FILE_UPLOAD_LIMIT
import io.github.nebur692.meshclient.core.ServerFileEntry
import io.github.nebur692.meshclient.core.ServerFileRoot
import io.github.nebur692.meshclient.core.ShareKinds
import io.github.nebur692.meshclient.core.createServerFolder
import io.github.nebur692.meshclient.core.deleteServerFiles
import io.github.nebur692.meshclient.core.deviceShares
import io.github.nebur692.meshclient.core.downloadServerFile
import io.github.nebur692.meshclient.core.installedPrograms
import io.github.nebur692.meshclient.core.notifyOn
import io.github.nebur692.meshclient.core.previousLogins
import io.github.nebur692.meshclient.core.removeDeviceShare
import io.github.nebur692.meshclient.core.renameServerFile
import io.github.nebur692.meshclient.core.serverFiles
import io.github.nebur692.meshclient.core.serverFilesPageUrl
import io.github.nebur692.meshclient.core.setDeviceNotify
import io.github.nebur692.meshclient.core.shareDevice
import io.github.nebur692.meshclient.core.uploadServerFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Phase 17, as the Windows app: sharing a device with a guest, its installed programs, notices when it connects, the
 * account's previous sign-ins and the server's own files. / La fase 17, como la app de Windows.
 */

private fun MeshClientException.ui() = UiError(failure, key, httpStatus)

/** A line with the error, when there is one. / Una línea con el error, si lo hay. */
@Composable
private fun ErrorLine(error: UiError?) {
    error ?: return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp)) {
        Text(errorText(error), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

private val durations = listOf(R.string.share_1h to 3_600_000L, R.string.share_8h to 8 * 3_600_000L, R.string.share_1d to 86_400_000L, R.string.share_1w to 7 * 86_400_000L)

/**
 * A guest link to the device's desktop, terminal or files, for a time, asking or not the person at the device; and the
 * links in force, to share again or end. / Un enlace de invitado y los enlaces vigentes, para volver a enviarlos o acabarlos.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ShareSheet(vm: AppViewModel, d: Device, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var guest by remember { mutableStateOf("") }
    var kinds by remember { mutableStateOf(ShareKinds.DESKTOP) }
    var duration by remember { mutableStateOf(0) }
    var consent by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiError?>(null) }
    var made by remember { mutableStateOf<DeviceShare?>(null) }
    var shares by remember { mutableStateOf<List<DeviceShare>?>(null) }
    fun run(block: suspend () -> Unit) {
        val s = vm.session ?: return
        busy = true; error = null
        scope.launch {
            try { block(); shares = s.deviceShares(d) } catch (e: MeshClientException) { error = e.ui() } finally { busy = false }
        }
    }
    LaunchedEffect(d.id) { run { } }
    fun send(url: String) = runCatching {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
    }
    Sheet(stringResource(R.string.share_title), d.name, onDone) {
        Text(stringResource(R.string.share_help), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(guest, { guest = it.take(64) }, label = { Text(stringResource(R.string.share_guest)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
        FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((bit, label) in listOf(ShareKinds.DESKTOP to R.string.act_desktop, ShareKinds.TERMINAL to R.string.share_terminal, ShareKinds.FILES to R.string.act_files))
                FilterChip(kinds and bit != 0, { kinds = kinds xor bit }, label = { Text(stringResource(label)) })
        }
        FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            durations.forEachIndexed { i, (label, _) -> FilterChip(duration == i, { duration = i }, label = { Text(stringResource(label)) }) }
        }
        Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { consent = !consent }.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.share_consent), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(consent, null)
        }
        Button(onClick = { run { made = vm.session!!.shareDevice(d, guest, kinds, durations[duration].second, consent) } },
            enabled = !busy && guest.isNotBlank() && kinds != 0, modifier = Modifier.padding(horizontal = 24.dp)) { Text(stringResource(R.string.share_create)) }
        made?.url?.let { url ->
            Text(url, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = { send(url) }) { Text(stringResource(R.string.share_send)) }
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(d.name, url))
                }) { Text(stringResource(R.string.copy)) }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
        ErrorLine(error)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.share_current), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleSmall)
        if (shares?.isEmpty() == true) Text(stringResource(R.string.share_none), Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        shares.orEmpty().forEach { sh ->
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(sh.guestName, style = MaterialTheme.typography.bodyLarge)
                    val what = listOfNotNull(
                        stringResource(R.string.act_desktop).takeIf { sh.kinds and ShareKinds.DESKTOP != 0 },
                        stringResource(R.string.share_terminal).takeIf { sh.kinds and ShareKinds.TERMINAL != 0 },
                        stringResource(R.string.act_files).takeIf { sh.kinds and ShareKinds.FILES != 0 }).joinToString(", ")
                    val until = sh.expiresMs?.let { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) }.orEmpty()
                    Text(stringResource(R.string.share_until, what, until), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                sh.url?.let { u -> IconButton(onClick = { send(u) }) { Icon(painterResource(R.drawable.ic_share), stringResource(R.string.share_send)) } }
                IconButton(onClick = { run { vm.session!!.removeDeviceShare(d, sh) } }, enabled = !busy) {
                    Icon(painterResource(R.drawable.ic_trash), stringResource(R.string.share_remove, sh.guestName))
                }
            }
        }
    }
}

/** The programs installed on the device, with a search. / Los programas instalados en el equipo, con buscador. */
@Composable
fun SoftwareSheet(vm: AppViewModel, d: Device, onDone: () -> Unit) {
    var list by remember { mutableStateOf<List<InstalledProgram>?>(null) }
    var error by remember { mutableStateOf<UiError?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(d.id) {
        try { list = vm.session?.installedPrograms(d) } catch (e: MeshClientException) { error = e.ui() }
    }
    Sheet(stringResource(R.string.software_title), d.name, onDone) {
        OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.software_search)) }, singleLine = true,
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), shape = RoundedCornerShape(24.dp))
        val all = list
        if (all == null && error == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
            Text(stringResource(R.string.software_loading), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall)
        }
        ErrorLine(error)
        if (all != null) {
            val q = query.trim().lowercase()
            val shown = all.filter { p -> q.isEmpty() || listOfNotNull(p.name, p.publisher, p.version).any { q in it.lowercase() } }
            Text(stringResource(R.string.software_count, shown.size, all.size), Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // A sheet scrolls as a whole: the first ones, and the search narrows. / La hoja se desplaza entera: los primeros, y el buscador acota.
            shown.take(300).forEach { p ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge)
                    listOfNotNull(p.version, p.publisher, p.date).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (shown.size > 300) Text(stringResource(R.string.software_more), Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Email or messaging notices when a device, or any device of a group, connects or disconnects. The server sends them even
 * with the app closed. / Avisos por correo o mensajería al conectarse o desconectarse; los manda el servidor.
 */
@Composable
fun NoticesDialog(title: String, current: Int, busy: Boolean, error: UiError?, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var flags by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(stringResource(R.string.notices_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for ((bit, label) in listOf(DeviceNotify.EMAIL_CONNECT to R.string.notices_email_on, DeviceNotify.EMAIL_DISCONNECT to R.string.notices_email_off,
                    DeviceNotify.MESSAGE_CONNECT to R.string.notices_msg_on, DeviceNotify.MESSAGE_DISCONNECT to R.string.notices_msg_off)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) { flags = flags xor bit }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(flags and bit != 0, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(flags) }, enabled = !busy) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The notices of this device, saved on the server. / Los avisos de este equipo, guardados en el servidor. */
@Composable
fun DeviceNoticesDialog(vm: AppViewModel, d: Device, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiError?>(null) }
    NoticesDialog(stringResource(R.string.notices_device, d.name), vm.session?.notifyOn(d.id) ?: 0, busy, error, onDone) { wanted ->
        val s = vm.session ?: return@NoticesDialog
        busy = true; error = null
        scope.launch {
            try { s.setDeviceNotify(d, wanted); onDone() } catch (e: MeshClientException) { error = e.ui() } finally { busy = false }
        }
    }
}

/** The account's previous sign-ins through the website, good and failed. / Las entradas anteriores por la web. */
@Composable
fun LoginsDialog(vm: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    var list by remember { mutableStateOf<List<LoginEntry>?>(null) }
    var error by remember { mutableStateOf<UiError?>(null) }
    LaunchedEffect(Unit) { try { list = vm.session?.previousLogins() } catch (e: MeshClientException) { error = e.ui() } }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.logins_title)) },
        text = {
            LazyColumn {
                item {
                    Text(stringResource(R.string.logins_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (list == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    error?.let { Text(errorText(it), color = MaterialTheme.colorScheme.error) }
                    if (list?.isEmpty() == true) Text(stringResource(R.string.logins_none), Modifier.padding(vertical = 8.dp))
                }
                items(list.orEmpty()) { l ->
                    val bad = l.kind == LoginKind.WrongPassword || l.kind == LoginKind.WrongSecondFactor || l.kind == LoginKind.Locked
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(when (l.kind) {
                            LoginKind.Valid -> stringResource(R.string.logins_ok)
                            LoginKind.Token -> stringResource(R.string.logins_token, l.tokenName.orEmpty())
                            LoginKind.WrongSecondFactor -> stringResource(R.string.logins_bad_code)
                            LoginKind.Locked -> stringResource(R.string.logins_locked)
                            LoginKind.WrongPassword -> stringResource(R.string.logins_bad_password)
                            LoginKind.Other -> stringResource(R.string.logins_other)
                        } + " · " + DateUtils.formatDateTime(context, l.timeMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
                            style = MaterialTheme.typography.bodyLarge, color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        if (l.details.isNotEmpty()) Text(l.details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.close)) } },
    )
}

/**
 * The files kept on the server ("My files", and each device group's when the account may): browse, make folders,
 * rename, delete, upload and download. The server only gives an app files below 200 KB; bigger ones open in its website.
 * Los ficheros guardados en el servidor: navegar, crear carpetas, renombrar, borrar, subir y bajar. El servidor solo da
 * a una app ficheros de menos de 200 KB; los mayores se abren en su web.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ServerFilesScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val back = { vm.show(Screen.Account) }
    BackHandler(onBack = back)
    var roots by remember { mutableStateOf<List<ServerFileRoot>?>(null) }
    var rootId by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf(listOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiError?>(null) }
    var picked by remember { mutableStateOf<ServerFileEntry?>(null) }
    var naming by remember { mutableStateOf<Pair<Int, ServerFileEntry?>?>(null) } // (title, entry to rename or null for a new folder)
    var deleting by remember { mutableStateOf<ServerFileEntry?>(null) }
    var saving by remember { mutableStateOf<ServerFileEntry?>(null) }
    val root = roots?.firstOrNull { it.id == rootId } ?: roots?.firstOrNull()
    fun set(r: List<ServerFileRoot>) {
        roots = r
        // The folder may be gone: up to what still is. / La carpeta puede haberse ido: subir a lo que queda.
        val now = r.firstOrNull { it.id == rootId } ?: r.firstOrNull()
        var f = folder
        while (f.isNotEmpty() && now?.find(f) == null) f = f.dropLast(1)
        folder = f
    }
    fun run(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch { try { block() } catch (e: MeshClientException) { error = e.ui() } finally { busy = false } }
    }
    fun webPage() = vm.session?.let { s -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, s.serverFilesPageUrl().toUri())) } }
    LaunchedEffect(Unit) { run { set(vm.session!!.serverFiles()) } }
    val pickToUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val r = root ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        run {
            val (name, bytes) = withContext(Dispatchers.IO) {
                val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null } ?: "fichero"
                val size = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null } ?: 0L
                if (size > SERVER_FILE_UPLOAD_LIMIT) name to null
                else name to (context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw MeshClientException(MeshFailure.Rejected, "ficheros-servidor/subir/dato-invalido", "The file could not be read on this phone."))
            }
            if (bytes == null) { webPage(); return@run }
            set(vm.session!!.uploadServerFile(r, folder, name, bytes))
        }
    }
    val pickWhereToSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = saving; saving = null
        val r = root
        if (uri == null || f == null || r == null) return@rememberLauncherForActivityResult
        run {
            val data = vm.session!!.downloadServerFile(r, folder, f)
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) } }
        }
    }
    Scaffold(topBar = {
        Column {
            TopAppBar(expandedHeight = BarHeight,
                title = { Text(stringResource(R.string.sf_title)) },
                navigationIcon = { IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = { run { set(vm.session!!.serverFiles()) } }, enabled = !busy) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.refresh)) }
                })
            if ((roots?.size ?: 0) > 1) FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                roots.orEmpty().forEach { r ->
                    FilterChip(r.id == root?.id, { rootId = r.id; folder = emptyList() },
                        label = { Text(if (r.isGroup) stringResource(R.string.sf_group, r.name) else stringResource(R.string.sf_mine)) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { folder = folder.dropLast(1) }, enabled = folder.isNotEmpty()) { Icon(painterResource(R.drawable.ic_arrow_up), stringResource(R.string.sf_up)) }
                Text("/" + folder.joinToString("/"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { naming = R.string.sf_new_folder to null }, enabled = root != null && !busy) { Icon(painterResource(R.drawable.ic_folder_add), stringResource(R.string.sf_new_folder)) }
                IconButton(onClick = { pickToUpload.launch(arrayOf("*/*")) }, enabled = root != null && !busy) { Icon(painterResource(R.drawable.ic_upload), stringResource(R.string.sf_upload)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
            ErrorLine(error)
        }
    }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val entries = root?.find(folder).orEmpty()
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 16.dp)) {
            item {
                root?.let { r ->
                    Text(r.maxBytes?.let { stringResource(R.string.sf_quota, Formatter.formatShortFileSize(context, r.usedBytes), Formatter.formatShortFileSize(context, it)) }
                        ?: stringResource(R.string.sf_used, Formatter.formatShortFileSize(context, r.usedBytes)),
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (roots?.isEmpty() == true || (root != null && entries.isEmpty()))
                    Text(stringResource(R.string.sf_empty), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(entries, key = { (if (it.isFolder) "d:" else "f:") + it.name }) { e ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) {
                    if (e.isFolder) folder = folder + e.name else picked = e
                }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(if (e.isFolder) R.drawable.ic_folder else R.drawable.ic_doc), null, Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyLarge)
                        if (!e.isFolder) Text(listOfNotNull(Formatter.formatShortFileSize(context, e.size),
                            e.modifiedMs?.let { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (e.isFolder) IconButton(onClick = { picked = e }) { Icon(painterResource(R.drawable.ic_more), stringResource(R.string.more_options)) }
                }
            }
        }
    }
    picked?.let { e ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(e.name) },
            text = { if (!e.isFolder && e.size >= SERVER_FILE_DOWNLOAD_LIMIT) Text(stringResource(R.string.sf_too_big)) },
            confirmButton = {
                FlowRow {
                    if (!e.isFolder) TextButton(onClick = {
                        picked = null
                        if (e.size >= SERVER_FILE_DOWNLOAD_LIMIT) webPage() else { saving = e; pickWhereToSave.launch(e.name) }
                    }) { Text(stringResource(if (e.size >= SERVER_FILE_DOWNLOAD_LIMIT) R.string.sf_open_web else R.string.sf_download)) }
                    TextButton(onClick = { picked = null; naming = R.string.sf_rename to e }) { Text(stringResource(R.string.sf_rename)) }
                    TextButton(onClick = { picked = null; deleting = e }) { Text(stringResource(R.string.sf_delete), color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(onClick = { picked = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    naming?.let { (title, e) ->
        var name by remember(e) { mutableStateOf(e?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(stringResource(title)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.sf_name)) }) },
            confirmButton = {
                TextButton(onClick = {
                    val r = root ?: return@TextButton
                    naming = null
                    run { set(if (e == null) vm.session!!.createServerFolder(r, folder, name) else vm.session!!.renameServerFile(r, folder, e.name, name)) }
                }, enabled = name.isNotBlank() && name.trim() != e?.name) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.sf_delete)) },
            text = { Text(stringResource(R.string.sf_confirm_delete, e.name)) },
            confirmButton = {
                TextButton(onClick = {
                    val r = root ?: return@TextButton
                    deleting = null
                    run { set(vm.session!!.deleteServerFiles(r, folder, listOf(e.name))) }
                }) { Text(stringResource(R.string.sf_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
