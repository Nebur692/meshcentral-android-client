package io.github.nebur692.meshclient.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.FilesModel
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.core.RemoteEntry
import io.github.nebur692.meshclient.core.RemoteEntryKind
import io.github.nebur692.meshclient.core.RemotePaths

/** What is being asked about one item. / Lo que se pregunta sobre un elemento. */
private sealed interface FileAsk {
    data class Actions(val e: RemoteEntry) : FileAsk
    data class Rename(val e: RemoteEntry) : FileAsk
    data class Delete(val e: RemoteEntry) : FileAsk
    data object NewFolder : FileAsk
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: AppViewModel) {
    val f = vm.files ?: run { LaunchedEffect(Unit) { vm.leaveTool() }; return }
    val snack = remember { SnackbarHostState() }
    var ask by remember { mutableStateOf<FileAsk?>(null) }
    var downloading by remember { mutableStateOf<RemoteEntry?>(null) }
    BackHandler { if (!f.up()) vm.leaveTool() }

    val pickToUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(f::upload) }
    val pickWhereToSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val e = downloading
        downloading = null
        if (uri != null && e != null) f.download(e, uri)
    }

    f.done?.let { d -> val text = stringResource(d); LaunchedEffect(d, f) { snack.showSnackbar(text); f.done = null } }
    f.error?.let { e ->
        val text = stringResource(R.string.files_failed) + "\n" + errorText(e) + " · " + e.key
        LaunchedEffect(e) { snack.showSnackbar(text); f.error = null }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Column {
                TopAppBar(expandedHeight = BarHeight,
                    title = {
                        Column {
                            Text(f.device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(f.path.ifEmpty { stringResource(R.string.files_drives) }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                        }
                    },
                    navigationIcon = { IconButton(onClick = vm::leaveTool) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
                    actions = {
                        if (!RemotePaths.isTop(f.path)) IconButton(onClick = { f.up() }) { Icon(painterResource(R.drawable.ic_arrow_up), stringResource(R.string.files_up)) }
                        IconButton(onClick = { f.open() }) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.files_refresh)) }
                        if (f.path.isNotEmpty()) IconButton(onClick = { ask = FileAsk.NewFolder }) { Icon(painterResource(R.drawable.ic_folder_add), stringResource(R.string.files_new_folder)) }
                    },
                )
                if (f.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
            }
        },
        floatingActionButton = {
            // Nothing can be written in the list of drives. / En la lista de unidades no se puede escribir.
            // Its text, said out loud: the animated label of an extended button reaches accessibility with no size (seen with
            // uiautomator: a button with no text). / Su texto, dicho en voz alta: la etiqueta animada llegaba sin tamaño.
            val upload = stringResource(R.string.files_upload)
            if (f.path.isNotEmpty() && f.transfer == null) ExtendedFloatingActionButton(
                onClick = { pickToUpload.launch(arrayOf("*/*")) },
                modifier = Modifier.semantics { contentDescription = upload },
                icon = { Icon(painterResource(R.drawable.ic_upload), null) },
                text = { Text(stringResource(R.string.files_upload)) },
            )
        },
        bottomBar = { f.transfer?.let { TransferBar(it, f::cancelTransfer) } },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val entries = f.entries
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (entries != null && entries.isEmpty() && !f.loading)
                Text(stringResource(R.string.files_empty), Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 88.dp)) {
                items(entries.orEmpty(), key = { it.kind.name + "/" + it.name }) { e ->
                    Entry(e, onClick = { if (e.kind == RemoteEntryKind.File) ask = FileAsk.Actions(e) else f.enter(e) },
                        onLongClick = { if (e.kind != RemoteEntryKind.Drive) ask = FileAsk.Actions(e) })
                }
            }
        }
    }

    when (val a = ask) {
        is FileAsk.Actions -> EntrySheet(a.e, onDismiss = { ask = null },
            onDownload = { ask = null; downloading = a.e; pickWhereToSave.launch(a.e.name) },
            onRename = { ask = FileAsk.Rename(a.e) }, onDelete = { ask = FileAsk.Delete(a.e) })
        is FileAsk.Rename -> NameDialog(stringResource(R.string.files_rename), stringResource(R.string.files_new_name), a.e.name,
            onDismiss = { ask = null }) { name -> ask = null; if (name != a.e.name) f.rename(a.e, name) }
        FileAsk.NewFolder -> NameDialog(stringResource(R.string.files_new_folder), stringResource(R.string.files_folder_name), "",
            onDismiss = { ask = null }) { name -> ask = null; f.createFolder(name) }
        is FileAsk.Delete -> AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(stringResource(R.string.files_delete)) },
            text = { Text(stringResource(if (a.e.kind == RemoteEntryKind.File) R.string.files_delete_file_ask else R.string.files_delete_folder_ask, a.e.name)) },
            confirmButton = { TextButton(onClick = { ask = null; f.delete(a.e) }) { Text(stringResource(R.string.files_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(R.string.cancel)) } },
        )
        null -> Unit
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Entry(e: RemoteEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val icon = when (e.kind) { RemoteEntryKind.Drive -> R.drawable.ic_drive; RemoteEntryKind.Folder -> R.drawable.ic_folder; RemoteEntryKind.File -> R.drawable.ic_doc }
    val details = buildList {
        if (e.kind == RemoteEntryKind.File) add(Formatter.formatShortFileSize(context, e.size))
        if (e.kind == RemoteEntryKind.Drive) e.free?.let { add(Formatter.formatShortFileSize(context, it)) }
        e.modified?.let { add(DateUtils.formatDateTime(context, it.toEpochMilli(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)) }
    }.joinToString(" · ")
    ListItem(
        headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (details.isNotEmpty()) ({ Text(details) }) else null,
        leadingContent = { Icon(painterResource(icon), null, tint = if (e.kind == RemoteEntryKind.File) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary) },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(e: RemoteEntry, onDismiss: () -> Unit, onDownload: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Text(e.name, Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            @Composable fun row(icon: Int, label: Int, danger: Boolean = false, click: () -> Unit) = ListItem(
                headlineContent = { Text(stringResource(label), color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
                leadingContent = { Icon(painterResource(icon), null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.clickable(onClick = click),
            )
            if (e.kind == RemoteEntryKind.File) row(R.drawable.ic_download, R.string.files_download, click = onDownload)
            row(R.drawable.ic_edit, R.string.files_rename, click = onRename)
            row(R.drawable.ic_trash, R.string.files_delete, danger = true, click = onDelete)
        }
    }
}

/** A name for the device: no slashes, not empty. / Un nombre para el equipo: sin barras y no vacío. */
private fun goodName(s: String) = s.isNotBlank() && '/' !in s && '\\' !in s && s != "." && s != ".."

@Composable
private fun NameDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val ok = goodName(name.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, label = { Text(label) }, singleLine = true, isError = name.isNotEmpty() && !ok,
                supportingText = if (name.isNotEmpty() && !ok) ({ Text(stringResource(R.string.files_bad_name)) }) else null)
        },
        confirmButton = { TextButton(onClick = { onOk(name.trim()) }, enabled = ok) { Text(stringResource(R.string.files_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TransferBar(t: io.github.nebur692.meshclient.Transfer, onCancel: () -> Unit) {
    val context = LocalContext.current
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(WindowInsets.navigationBars.asPaddingValues()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(painterResource(if (t.up) R.drawable.ic_upload else R.drawable.ic_download), null, Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (t.up) R.string.files_uploading else R.string.files_downloading, t.name), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    Text(Formatter.formatShortFileSize(context, t.done) + if (t.total > 0) " / " + Formatter.formatShortFileSize(context, t.total) else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
            if (t.total > 0) LinearProgressIndicator({ (t.done.toFloat() / t.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 4.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}
