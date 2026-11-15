package io.github.nebur692.meshclient.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nebur692.meshclient.AppViewModel
import io.github.nebur692.meshclient.R
import io.github.nebur692.meshclient.ReportPeriod
import io.github.nebur692.meshclient.Screen
import io.github.nebur692.meshclient.core.Report
import io.github.nebur692.meshclient.core.ReportColumn
import io.github.nebur692.meshclient.core.ReportGroupBy
import io.github.nebur692.meshclient.core.ReportKind
import io.github.nebur692.meshclient.core.groupings
import io.github.nebur692.meshclient.core.reportKinds
import java.text.DateFormat
import java.util.Date
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The website's reports: remote sessions, traffic, sign-ins and the database, by user, device or day, for a period;
 * each group as a card of rows, and the whole report shared as CSV. The same as the Windows app's window.
 * Los informes de la web: sesiones remotas, tráfico, entradas y la base de datos, por usuario, equipo o día, en un
 * periodo; cada grupo como una tarjeta de filas, y el informe entero compartido como CSV. Igual que en Windows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(vm: AppViewModel) {
    val m = vm.reports ?: run { LaunchedEffect(Unit) { vm.show(Screen.Account) }; return }
    val s = vm.session
    val back = { vm.leaveReports() }
    BackHandler(onBack = back)
    LaunchedEffect(m) { if (m.report == null && !m.loading) m.load() }
    val context = LocalContext.current
    val names = Names(vm)
    Scaffold(topBar = {
        Column {
            TopAppBar(expandedHeight = BarHeight,
                title = { Text(stringResource(R.string.reports)) },
                navigationIcon = { IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back)) } },
                actions = {
                    m.report?.takeIf { it.groups.isNotEmpty() }?.let { r ->
                        IconButton(onClick = { share(context, kindName(context, m.kind), csv(context, r, names)) }) {
                            Icon(painterResource(R.drawable.ic_share), stringResource(R.string.report_share))
                        }
                    }
                    IconButton(onClick = m::load, enabled = !m.loading) { Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.refresh)) }
                },
            )
            @Composable fun <T> Chips(items: List<T>, selected: T, label: (T) -> Int, pick: (T) -> Unit) =
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { i -> FilterChip(selected = i == selected, onClick = { pick(i) }, label = { Text(stringResource(label(i))) }) }
                }
            Chips(s?.reportKinds ?: ReportKind.entries.dropLast(1), m.kind, ::kindLabel) { m.choose(kind = it) }
            m.kind.groupings().takeIf { it.isNotEmpty() }?.let { Chips(it, m.groupBy, ::groupLabel) { g -> m.choose(groupBy = g) } }
            Chips(ReportPeriod.entries, m.period, ::periodLabel) { m.choose(period = it) }
            if (m.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp)) else HorizontalDivider(Modifier.padding(top = 4.dp))
            m.error?.let { e ->
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(errorText(e), Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val r = m.report
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (r != null && r.groups.all { it.rows.isEmpty() }) Text(stringResource(R.string.report_empty), Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r != null) LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottom + 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(r.groups.filter { it.rows.isNotEmpty() }, key = { it.key }) { g ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        groupName(r, g.key, names).takeIf { it.isNotEmpty() }?.let {
                            Text(it, Modifier.padding(start = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        g.rows.forEach { row ->
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    r.columns.forEach { c ->
                                        val v = value(context, c, row[c.id], names)
                                        if (v.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(columnTitle(context, c), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(v, style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Names for the server's ids, from what this screen already knows. / Nombres para los ids del servidor. */
internal class Names(private val vm: AppViewModel) {
    fun user(id: String?) = id?.let { if (it == vm.session?.user?.id) vm.session?.user?.name ?: it.substringAfterLast('/') else it.substringAfterLast('/') }.orEmpty()
    fun device(id: String?) = id?.let { vm.devices[it]?.name ?: it.substringAfterLast('/') }.orEmpty()
    fun group(id: String?) = id?.let { vm.groupNames[it] ?: it.substringAfterLast('/') }.orEmpty()
}

private fun kindLabel(k: ReportKind) = when (k) {
    ReportKind.RemoteSessions -> R.string.report_sessions; ReportKind.Traffic -> R.string.report_traffic
    ReportKind.Logins -> R.string.report_logins; ReportKind.Database -> R.string.report_database
}

private fun kindName(context: Context, k: ReportKind) = context.getString(kindLabel(k))

private fun groupLabel(g: ReportGroupBy) = when (g) { ReportGroupBy.User -> R.string.group_by_user; ReportGroupBy.Device -> R.string.group_by_device; ReportGroupBy.Day -> R.string.group_by_day }

private fun periodLabel(p: ReportPeriod) = when (p) {
    ReportPeriod.Today -> R.string.period_today; ReportPeriod.Week -> R.string.period_week
    ReportPeriod.Month -> R.string.period_month; ReportPeriod.Quarter -> R.string.period_quarter
}

private val titles = mapOf(
    "time" to R.string.rep_time, "userid" to R.string.rep_user, "nodeid" to R.string.rep_device, "meshid" to R.string.rep_group,
    "guestname" to R.string.rep_guest, "protocol" to R.string.rep_protocol, "length" to R.string.rep_length, "bytesin" to R.string.rep_bytes_in,
    "bytesout" to R.string.rep_bytes_out, "ip" to R.string.rep_ip, "browser" to R.string.rep_browser, "os" to R.string.rep_os,
    "twofactor" to R.string.rep_two_factor, "msg" to R.string.rep_msg, "record" to R.string.rep_record, "recordcount" to R.string.rep_count,
)

private fun columnTitle(context: Context, c: ReportColumn) = titles[c.id]?.let(context::getString) ?: c.title

/** One value as text, as the Windows app reads it. / Un valor como texto, como lo lee la app de Windows. */
internal fun value(context: Context, c: ReportColumn, v: JsonElement?, names: Names): String {
    val p = v as? JsonPrimitive ?: return ""
    // A parsed number may come as a decimal: both are read. / Un número puede venir con decimales: se leen los dos.
    val n = p.longOrNull ?: p.doubleOrNull?.toLong()
    val text = p.content
    return when {
        c.format == "datetime" && n != null -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(n))
        c.format == "time" && n != null -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(n))
        c.format == "user" -> names.user(text)
        c.format == "node" -> names.device(text)
        c.format == "mesh" -> names.group(text)
        c.format == "bytes" && n != null -> android.text.format.Formatter.formatShortFileSize(context, n)
        c.format == "seconds" && n != null -> if (n < 3600) "%d:%02d".format(n / 60, n % 60) else "%d:%02d:%02d".format(n / 3600, n / 60 % 60, n % 60)
        c.format == "msg" && n != null -> when (n) {
            107L -> context.getString(R.string.login_ok); 108L -> context.getString(R.string.login_bad_code)
            109L -> context.getString(R.string.login_locked); 110L -> context.getString(R.string.login_bad_password); else -> text
        }
        c.id == "protocol" && n != null -> when (n) {
            1L, 6L, 7L, 8L, 9L -> context.getString(R.string.act_terminal); 2L -> context.getString(R.string.act_desktop)
            5L -> context.getString(R.string.act_files); in 100L..199L -> "Intel® AMT"; 200L -> context.getString(R.string.act_chat)
            else -> context.getString(R.string.proto_other, n)
        }
        else -> text
    }
}

/** A group's header: an account, a device (with its group), or a day. / La cabecera de un grupo. */
internal fun groupName(r: Report, key: String, names: Names): String = when (r.groupFormat) {
    "user" -> names.user(key)
    // "node/…/…" alone, or followed by "/mesh/…/…" (meshuser.js: nodeid + '/' + meshid). / Solo el equipo, o seguido de su grupo.
    "nodemesh" -> key.indexOf("/mesh/").let { i -> if (i > 0) "${names.device(key.substring(0, i))} (${names.group(key.substring(i + 1))})" else names.device(key) }
    else -> if (r.groups.size == 1 && key == "0") "" else key
}

private fun csvField(s: String) = if (s.any { it in ",\"\n\r;" }) "\"" + s.replace("\"", "\"\"") + "\"" else s

/** The whole report as CSV (RFC 4180), the group in the first column. / El informe entero como CSV. */
internal fun csv(context: Context, r: Report, names: Names): String = buildString {
    appendLine((listOf(context.getString(R.string.report_group)) + r.columns.map { columnTitle(context, it) }).joinToString(",") { csvField(it) })
    for (g in r.groups) for (row in g.rows)
        appendLine((listOf(groupName(r, g.key, names)) + r.columns.map { value(context, it, row[it.id], names) }).joinToString(",") { csvField(it) })
}

private fun share(context: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, title)) }
}
