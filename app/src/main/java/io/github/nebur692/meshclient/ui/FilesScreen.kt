package io.github.nebur692.meshclient.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
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
    data class DeleteSelected(val count: Int) : FileAsk
    data class Replace(val names: List<String>) : FileAsk
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: AppViewModel) {
    val f = vm.files ?: run { LaunchedEffect(Unit) { vm.leaveTool() }; return }
    val snack = remember { SnackbarHostState() }
    var ask by remember { mutableStateOf<FileAsk?>(null) }
    var downloading by remember { mutableStateOf<RemoteEntry?>(null) }
    // Back first stops choosing, then goes up, then leaves. / Atrás primero deja de elegir, luego sube, luego sale.
    // Typing a path (tap on it): its field, below the bar. / Escribir una ruta (tocándola): su campo, bajo la barra.
    var typing by remember { mutableStateOf<TextFieldValue?>(null) }
    BackHandler { if (typing != null) { typing = null; f.stopSuggesting() } else if (f.selecting) f.clearSelection() else if (!f.up()) vm.leaveTool() }

    val pickToUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> f.uploadAll(uris) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree -> tree?.let(f::downloadSelected) }
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
                if (f.selecting) SelectionBar(f.selected.size,
                    onStop = f::clearSelection, onAll = f::selectAll,
                    onDownload = { pickFolder.launch(null) }, onCopy = { f.copySelection(cut = false) }, onCut = { f.copySelection(cut = true) },
                    onDelete = { ask = FileAsk.DeleteSelected(f.selected.size) })
                else TopAppBar(expandedHeight = BarHeight,
                    title = {
                        Column {
                            Text(f.device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(f.path.ifEmpty { stringResource(R.string.files_drives) }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.StartEllipsis,
                                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.files_type_path)) {
                                    typing = TextFieldValue(f.path, TextRange(f.path.length)); f.suggest(f.path)
                                })
                        }
                    },
                    navigationIcon = { IconButton(onClick = vm::leaveTool) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
                    actions = {
                        if (!RemotePaths.isTop(f.path)) IconButton(onClick = { f.up() }) { Icon(painterResource(R.drawable.ic_arrow_up), stringResource(R.string.files_up)) }
                        IconButton(onClick = { f.open() }) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.files_refresh)) }
                        if (f.path.isNotEmpty()) IconButton(onClick = { ask = FileAsk.NewFolder }) { Icon(painterResource(R.drawable.ic_folder_add), stringResource(R.string.files_new_folder)) }
                    },
                )
                typing?.let { v ->
                    PathField(f, v, onValue = { typing = it }, onClose = { typing = null; f.stopSuggesting() })
                }
                if (f.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
            }
        },
        floatingActionButton = {
            // Nothing can be written in the list of drives. / En la lista de unidades no se puede escribir.
            // Its text, said out loud: the animated label of an extended button reaches accessibility with no size (seen with
            // uiautomator: a button with no text). / Su texto, dicho en voz alta: la etiqueta animada llegaba sin tamaño.
            val upload = stringResource(R.string.files_upload)
            if (f.path.isNotEmpty() && f.transfer == null && !f.selecting && f.clip == null) ExtendedFloatingActionButton(
                onClick = { pickToUpload.launch(arrayOf("*/*")) },
                modifier = Modifier.semantics { contentDescription = upload },
                icon = { Icon(painterResource(R.drawable.ic_upload), null) },
                text = { Text(stringResource(R.string.files_upload)) },
            )
        },
        bottomBar = {
            val t = f.transfer
            val c = f.clip
            if (t != null) TransferBar(t, f::cancelTransfer)
            else if (c != null && !f.selecting) PasteBar(c, f.canPasteHere(), onPaste = { if (f.pasteConflicts().isEmpty()) f.paste() else ask = FileAsk.Replace(f.pasteConflicts()) }, onForget = f::forgetClip)
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val entries = f.entries
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (entries != null && entries.isEmpty() && !f.loading)
                Text(stringResource(R.string.files_empty), Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 88.dp)) {
                items(entries.orEmpty(), key = { it.kind.name + "/" + it.name }) { e ->
                    // A long press starts choosing several; while choosing, a tap chooses. / Una pulsación larga empieza a elegir varios.
                    Entry(e, chosen = e.name in f.selected, choosing = f.selecting,
                        onClick = { if (f.selecting) f.toggle(e) else if (e.kind == RemoteEntryKind.File) ask = FileAsk.Actions(e) else f.enter(e) },
                        onLongClick = { f.toggle(e) })
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
        is FileAsk.DeleteSelected -> AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(stringResource(R.string.files_delete)) },
            text = { Text(pluralStringResource(R.plurals.files_delete_several_ask, a.count, a.count)) },
            confirmButton = { TextButton(onClick = { ask = null; f.deleteSelected() }) { Text(stringResource(R.string.files_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(R.string.cancel)) } },
        )
        is FileAsk.Replace -> AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(stringResource(R.string.files_paste_here)) },
            text = { Text(stringResource(R.string.files_replace_ask, a.names.joinToString(", "))) },
            confirmButton = { TextButton(onClick = { ask = null; f.paste() }) { Text(stringResource(R.string.files_replace), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(R.string.cancel)) } },
        )
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
private fun Entry(e: RemoteEntry, chosen: Boolean, choosing: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
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
        leadingContent = {
            // While choosing, a box says which are in; drives are places and cannot be chosen. / Mientras se elige, una casilla dice cuáles.
            if (choosing && e.kind != RemoteEntryKind.Drive) androidx.compose.material3.Checkbox(chosen, null)
            else Icon(painterResource(icon), null, tint = if (e.kind == RemoteEntryKind.File) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.files_choose))
            .semantics { selected = chosen },
    )
}

/** The bar while choosing: how many, all, and what to do with them. / La barra mientras se elige: cuántos, todos y qué hacer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(count: Int, onStop: () -> Unit, onAll: () -> Unit, onDownload: () -> Unit, onCopy: () -> Unit, onCut: () -> Unit, onDelete: () -> Unit) {
    TopAppBar(expandedHeight = BarHeight,
        title = { Text(pluralStringResource(R.plurals.files_selected, count, count), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onStop) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.files_stop_choosing)) } },
        actions = {
            IconButton(onClick = onAll) { Icon(painterResource(R.drawable.ic_select_all), stringResource(R.string.files_select_all)) }
            IconButton(onClick = onDownload) { Icon(painterResource(R.drawable.ic_download), stringResource(R.string.files_download)) }
            IconButton(onClick = onCopy) { Icon(painterResource(R.drawable.ic_copy), stringResource(R.string.files_copy)) }
            IconButton(onClick = onCut) { Icon(painterResource(R.drawable.ic_cut), stringResource(R.string.files_cut)) }
            IconButton(onClick = onDelete) { Icon(painterResource(R.drawable.ic_trash), stringResource(R.string.files_delete), tint = MaterialTheme.colorScheme.error) }
        },
        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

/** What was copied or cut, and where it can go: here, or not here. / Lo copiado o cortado, y si se puede pegar aquí. */
@Composable
private fun PasteBar(c: io.github.nebur692.meshclient.FilesClip, canHere: Boolean, onPaste: () -> Unit, onForget: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(WindowInsets.navigationBars.asPaddingValues()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(if (c.cut) R.drawable.ic_cut else R.drawable.ic_copy), null, Modifier.size(20.dp))
            Text(pluralStringResource(if (c.cut) R.plurals.files_clip_cut else R.plurals.files_clip_copy, c.entries.size, c.entries.size)
                + if (canHere) "" else " · " + stringResource(R.string.files_not_here),
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onForget) { Text(stringResource(R.string.cancel)) }
            androidx.compose.material3.FilledTonalButton(onClick = onPaste, enabled = canHere) { Text(stringResource(R.string.files_paste_here)) }
        }
    }
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

/**
 * A path typed by hand, completed as it is typed: the subfolders of what is typed show below, and taking one leaves the
 * path ready for the next level; Go opens it. / Una ruta escrita a mano, que se completa mientras se escribe.
 */
@Composable
private fun PathField(f: FilesModel, value: TextFieldValue, onValue: (TextFieldValue) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        OutlinedTextField(value, { onValue(it); f.suggest(it.text) }, singleLine = true,
            label = { Text(stringResource(R.string.files_type_path)) },
            placeholder = { Text(stringResource(if (f.device.isWindows) R.string.files_path_hint_windows else R.string.files_path_hint)) },
            trailingIcon = { IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cancel)) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { f.go(value.text); onClose() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus))
        DropdownMenu(expanded = f.suggestions.isNotEmpty(), onDismissRequest = f::stopSuggesting, properties = PopupProperties(focusable = false)) {
            f.suggestions.take(40).forEach { name ->
                DropdownMenuItem(text = { Text(name) }, leadingIcon = { Icon(painterResource(R.drawable.ic_folder), null) }, onClick = {
                    val p = f.take(value.text, name)
                    onValue(TextFieldValue(p, TextRange(p.length)))
                    f.suggest(p)
                })
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
