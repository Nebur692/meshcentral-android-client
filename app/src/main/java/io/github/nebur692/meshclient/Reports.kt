package io.github.nebur692.meshclient

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.nebur692.meshclient.core.MeshClientException
import io.github.nebur692.meshclient.core.MeshFailure
import io.github.nebur692.meshclient.core.MeshSession
import io.github.nebur692.meshclient.core.Report
import io.github.nebur692.meshclient.core.ReportGroupBy
import io.github.nebur692.meshclient.core.ReportKind
import io.github.nebur692.meshclient.core.groupings
import io.github.nebur692.meshclient.core.report
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** How far back a report goes: today, or the last days. / Hasta dónde llega un informe: hoy, o los últimos días. */
enum class ReportPeriod(val days: Int) { Today(0), Week(7), Month(30), Quarter(90) }

/**
 * One of the website's reports, asked when the person picks it: nothing is polled. The choices only offer what the
 * server accepts for each report (the core says which). The same as the Windows app's window.
 * Uno de los informes de la web, pedido cuando la persona lo elige: nada se pide solo. Las opciones solo ofrecen lo que
 * el servidor acepta para cada informe. Igual que la ventana de la app de Windows.
 */
class ReportsModel(private val session: () -> MeshSession?, private val scope: CoroutineScope) {
    var kind by mutableStateOf(ReportKind.RemoteSessions)
        private set
    var groupBy by mutableStateOf(ReportGroupBy.User)
        private set
    var period by mutableStateOf(ReportPeriod.Week)
        private set
    var report by mutableStateOf<Report?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<UiError?>(null)

    private var job: Job? = null

    fun choose(kind: ReportKind = this.kind, groupBy: ReportGroupBy = this.groupBy, period: ReportPeriod = this.period) {
        this.kind = kind
        // Only a grouping the server accepts for that report. / Solo una agrupación que el servidor acepta para ese informe.
        val allowed = kind.groupings()
        this.groupBy = if (allowed.isEmpty()) ReportGroupBy.User else if (groupBy in allowed) groupBy else allowed.first()
        this.period = period
        load()
    }

    fun load() {
        job?.cancel()
        loading = true
        error = null
        job = scope.launch {
            try {
                val s = session() ?: throw MeshClientException(MeshFailure.ConnectionLost, "servidor/informe/conexion-cortada", "no session")
                val now = System.currentTimeMillis()
                // From midnight of the first day to the end of today, as the website's date pickers. / De medianoche del primer día al final de hoy.
                val start = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                    add(java.util.Calendar.DAY_OF_YEAR, -period.days)
                }.timeInMillis
                report = s.report(kind, groupBy, start, now + 60_000)
            } catch (e: MeshClientException) {
                report = null
                error = UiError(e.failure, e.key, e.httpStatus)
            } finally {
                loading = false
            }
        }
    }

    fun close() { job?.cancel() }
}
