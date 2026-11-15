package io.github.nebur692.meshclient.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** What each report accepts, as the server takes it (measured on Windows). / Lo que acepta cada informe. */
class ReportsUnitTest {
    @Test fun `sessions group by user, device or day, sign-ins never by device, and the rest not at all`() {
        assertEquals(listOf(ReportGroupBy.User, ReportGroupBy.Device, ReportGroupBy.Day), ReportKind.RemoteSessions.groupings())
        // By device the server breaks and closes the connection. / Por equipo el servidor se rompe y cierra la conexión.
        assertEquals(listOf(ReportGroupBy.User, ReportGroupBy.Day), ReportKind.Logins.groupings())
        assertEquals(emptyList<ReportGroupBy>(), ReportKind.Traffic.groupings())
        assertEquals(emptyList<ReportGroupBy>(), ReportKind.Database.groupings())
    }
}
