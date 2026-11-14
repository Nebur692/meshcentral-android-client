package io.github.nebur692.meshclient

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.nebur692.meshclient.core.MeshClientException
import io.github.nebur692.meshclient.core.MeshFailure
import io.github.nebur692.meshclient.core.MeshSession
import io.github.nebur692.meshclient.core.Recording
import io.github.nebur692.meshclient.core.downloadRecording
import io.github.nebur692.meshclient.core.recordings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The server's recorded sessions, asked when the screen opens and when the person refreshes. A recording is downloaded
 * where the person chooses, which the server only allows to a web session (single sign-on); otherwise it opens in the
 * browser. / Las sesiones grabadas del servidor, pedidas al abrir y al actualizar. Una grabación se baja donde elige la
 * persona, cosa que el servidor solo deja a una sesión web (inicio de sesión único); si no, se abre en el navegador.
 */
class RecordingsModel(private val session: () -> MeshSession?, private val resolver: ContentResolver, private val scope: CoroutineScope) {
    var list by mutableStateOf<List<Recording>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<UiError?>(null)
    var notice by mutableStateOf<Notice?>(null)
    var filter by mutableStateOf("")
    /** The download going on: file name and bytes so far. / La descarga en curso. */
    var downloading by mutableStateOf<Pair<String, Long>?>(null)
        private set

    private var job: Job? = null
    private var copy: Job? = null

    val canDownload: Boolean get() = session()?.hasWebSession == true

    private fun current() = session() ?: throw MeshClientException(MeshFailure.ConnectionLost, "grabaciones/ver-grabaciones/conexion-cortada", "no session")

    fun load() {
        job?.cancel()
        loading = true
        error = null
        job = scope.launch {
            try { list = current().recordings() }
            catch (e: MeshClientException) { error = UiError(e.failure, e.key, e.httpStatus) }
            finally { loading = false }
        }
    }

    fun download(r: Recording, to: Uri) {
        if (copy?.isActive == true) return
        copy = scope.launch {
            downloading = r.fileName to 0L
            var ok = false
            try {
                val written = withContext(Dispatchers.IO) {
                    resolver.openOutputStream(to, "wt")?.use { out -> current().downloadRecording(r, out) { n -> downloading = r.fileName to n } } != null
                }
                if (!written) throw MeshClientException(MeshFailure.Rejected, "grabaciones/descargar-grabacion/permisos-fichero", "The file could not be written on this phone.")
                ok = true
                notice = Notice(R.string.files_downloaded)
            } catch (e: MeshClientException) {
                notice = Notice(R.string.action_failed, UiError(e.failure, e.key, e.httpStatus))
            } finally {
                downloading = null
                // Half a file is worse than none. / Medio fichero es peor que ninguno.
                if (!ok) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { android.provider.DocumentsContract.deleteDocument(resolver, to) } }
            }
        }
    }

    fun close() { job?.cancel(); copy?.cancel() }
}
