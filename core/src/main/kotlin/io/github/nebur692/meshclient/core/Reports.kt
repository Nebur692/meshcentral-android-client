package io.github.nebur692.meshclient.core

import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The website's reports (meshuser.js "report" type). / Los informes de la web. */
enum class ReportKind(val code: Int) { RemoteSessions(1), Traffic(2), Logins(3), Database(4) }

enum class ReportGroupBy(val code: Int) { User(1), Device(2), Day(3) }

/** A column: [format] says how its raw values read (datetime and time are milliseconds). / Una columna y cómo se leen sus valores. */
data class ReportColumn(val id: String, val title: String, val format: String?)

/** One group of rows: an account, a device ("nodemesh") or a day, as the server groups them. / Un grupo de filas. */
data class ReportGroup(val key: String, val rows: List<Map<String, JsonElement>>)

/** A report as the server builds it for the website. / Un informe tal como lo hace el servidor para la web. */
data class Report(val columns: List<ReportColumn>, val groupFormat: String?, val groups: List<ReportGroup>)

/** Only what the server accepts for each report. / Solo lo que el servidor acepta en cada informe. */
fun ReportKind.groupings(): List<ReportGroupBy> = when (this) {
    ReportKind.RemoteSessions -> ReportGroupBy.entries
    ReportKind.Logins -> listOf(ReportGroupBy.User, ReportGroupBy.Day)
    else -> emptyList()
}

/** Reports this account may ask for (the database one, full administrators only). / Los informes que puede pedir esta cuenta. */
val MeshSession.reportKinds: List<ReportKind> get() = ReportKind.entries.filter { it != ReportKind.Database || user.isFullAdministrator }

/**
 * One of the website's reports, between two moments (milliseconds). The same as the Windows app, measured on 1.3.1:
 * the server drops without answering what it does not like (and the login report grouped by device even closes the
 * whole connection), so that is stopped here first; "report" comes back without tag or responseid, so one at a time.
 * Uno de los informes de la web, entre dos momentos. Igual que la app de Windows: el servidor tira sin contestar lo que
 * no le gusta (y el de entradas por equipo cierra la conexión entera), así que se para aquí antes; «report» vuelve sin
 * tag ni responseid, así que de uno en uno.
 */
suspend fun MeshSession.report(
    kind: ReportKind, groupBy: ReportGroupBy, fromMs: Long, toMs: Long, deviceGroupId: String? = null, showTraffic: Boolean = false,
): Report {
    fun bad(key: String, msg: String) = MeshClientException(MeshFailure.Rejected, key, msg)
    if (fromMs >= toMs) throw bad("servidor/informe/dato-invalido", "The start must be before the end.")
    if (kind == ReportKind.Database && !user.isFullAdministrator) throw bad("servidor/informe/no-autorizado", "Only a full administrator sees the database report.")
    if (kind == ReportKind.Logins && groupBy == ReportGroupBy.Device) throw bad("servidor/informe/dato-invalido", "The login report groups by user or by day.")
    if (deviceGroupId != null && !serverInfo.manageAllDeviceGroups && deviceGroupId !in user.links)
        throw bad("servidor/informe/no-autorizado", "This account has no rights on that device group.")
    val cmd = buildJsonObject {
        put("action", "report"); put("type", kind.code); put("groupBy", groupBy.code)
        put("start", fromMs / 1000); put("end", toMs / 1000); put("showTraffic", showTraffic)
        put("l", Locale.getDefault().toLanguageTag().ifEmpty { "en" }); put("tz", TimeZone.getDefault().id)
        deviceGroupId?.let { put("devGroup", it) }
    }
    return reportTurn.withLock {
        val r = request(cmd, { it.str("action") == "report" }, "servidor/informe/tiempo-agotado", "servidor/informe/conexion-cortada", timeout = 60_000)
        val data = r["data"] as? JsonObject ?: throw MeshClientException(MeshFailure.UnexpectedResponse, "servidor/informe/respuesta-ilegible", "The report came back empty.")
        val columns = (data["columns"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .map { ReportColumn(it.str("id").orEmpty(), it.str("title").orEmpty(), it.str("format")) }
        val groups = (data["groups"] as? JsonObject).orEmpty().map { (key, g) ->
            ReportGroup(key, ((g as? JsonObject)?.get("entries") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { it.toMap() })
        }
        Report(columns, data.str("groupFormat"), groups)
    }
}
