package io.github.nebur692.meshclient.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.ChatLine
import io.github.nebur692.meshclient.ChatState
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.Screen
import io.github.nebur692.meshclient.core.MeshChat

/**
 * The chat with the person at the device: what each side wrote, the person's "writing" sign and a box that only sends
 * while the person is in. / El chat con la persona del equipo: lo que escribe cada lado, su «escribiendo» y una caja
 * que solo manda mientras la persona está dentro.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AppViewModel) {
    val c = vm.chat ?: run { LaunchedEffect(Unit) { vm.show(Screen.Devices) }; return }
    val back = { vm.leaveChat() }
    BackHandler(onBack = back)
    LaunchedEffect(c) { c.start() }
    val list = rememberLazyListState()
    LaunchedEffect(c.lines.size, c.personWriting) { if (c.lines.isNotEmpty()) list.animateScrollToItem(c.lines.size - 1 + if (c.personWriting) 1 else 0) }
    Scaffold(topBar = {
        Column {
            TopAppBar(expandedHeight = BarHeight,
                title = {
                    Column {
                        Text(c.device.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(when (c.state) {
                            ChatState.Opening -> R.string.chat_opening
                            ChatState.Waiting -> R.string.chat_waiting
                            ChatState.Talking -> if (c.personWriting) R.string.chat_writing else R.string.chat_talking
                            ChatState.Ended -> R.string.chat_ended
                        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
            )
            if (c.recorded && c.state == ChatState.Talking) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(stringResource(R.string.chat_recorded), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().navigationBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (c.lines.isEmpty() && c.state != ChatState.Talking) Waiting(c.state, c.error?.let { errorText(it) }, c::start)
                LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(c.lines) { _, l -> Bubble(l) }
                    if (c.personWriting) item { Text(stringResource(R.string.chat_writing), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp)) }
                    if (c.lines.isNotEmpty() && c.state == ChatState.Ended) item {
                        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(c.error?.let { errorText(it) } ?: stringResource(if (c.personLeft) R.string.chat_person_left else R.string.chat_ended), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FilledTonalButton(onClick = c::start) { Text(stringResource(R.string.chat_again)) }
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val talking = c.state == ChatState.Talking
                OutlinedTextField(c.draft, { if (it.length <= MeshChat.MAX_TEXT) c.typed(it) }, Modifier.weight(1f), enabled = talking,
                    placeholder = { Text(stringResource(when (c.state) { ChatState.Talking -> R.string.chat_hint; ChatState.Ended -> R.string.chat_ended; else -> R.string.chat_hint_waiting })) },
                    maxLines = 4, shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { c.send() }))
                FilledIconButton(onClick = c::send, enabled = talking && c.draft.isNotBlank()) {
                    Icon(painterResource(R.drawable.ic_send), stringResource(R.string.chat_send))
                }
            }
        }
    }
}

/** Before the first message: opening, waiting for the person, or why it ended. / Antes del primer mensaje. */
@Composable
private fun Waiting(state: ChatState, error: String?, again: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        if (state != ChatState.Ended) CircularProgressIndicator(Modifier.size(40.dp))
        Text(when (state) {
            ChatState.Ended -> error ?: stringResource(R.string.chat_ended)
            else -> stringResource(R.string.chat_waiting_long)
        }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (state == ChatState.Ended) FilledTonalButton(onClick = again) { Text(stringResource(R.string.chat_again)) }
    }
}

@Composable
private fun Bubble(l: ChatLine) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (l.mine) Arrangement.End else Arrangement.Start) {
        Surface(shape = RoundedCornerShape(18.dp),
            color = if (l.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (l.mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 320.dp)) {
            Text(l.text, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
