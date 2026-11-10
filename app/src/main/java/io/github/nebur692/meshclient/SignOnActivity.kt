package io.github.nebur692.meshclient

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.nebur692.meshclient.core.ServerAddress
import io.github.nebur692.meshclient.core.SingleSignOn
import io.github.nebur692.meshclient.ui.MeshTheme
import io.github.nebur692.meshclient.ui.SignOnScreen

/**
 * The provider's page, in the ":signon" process (see the manifest): it hands MeshCentral's session cookies back to the
 * app and then its process ends, taking the browser's memory with it.
 * La página del proveedor, en el proceso «:signon»: devuelve a la app las cookies de sesión de MeshCentral y luego su
 * proceso acaba, llevándose la memoria del navegador.
 */
class SignOnActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val address = ServerAddress.parse(intent.getStringExtra(EXTRA_SERVER))
        val strategy = intent.getStringExtra(EXTRA_STRATEGY)
        if (address == null || strategy == null) return finishWith(null)
        val how = Screen.SignOn(SingleSignOn(strategy), address, intent.getStringExtra(EXTRA_PIN))
        setContent {
            MeshTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SignOnScreen(how, onCancel = { finishWith(null) }, onDone = ::finishWith)
                }
            }
        }
    }

    private fun finishWith(cookies: Map<String, String>?) {
        if (cookies == null) setResult(RESULT_CANCELED)
        else setResult(RESULT_OK, Intent().putExtra(EXTRA_NAMES, cookies.keys.toTypedArray()).putExtra(EXTRA_VALUES, cookies.values.toTypedArray()))
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // This process exists only for this page: once it is done, it goes, browser included (a rotation is not done).
        // Este proceso existe solo para esta página: al acabar se va, con el navegador (un giro no es acabar).
        if (isFinishing) Process.killProcess(Process.myPid())
    }

    companion object {
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_STRATEGY = "strategy"
        private const val EXTRA_PIN = "pin"
        private const val EXTRA_NAMES = "cookieNames"
        private const val EXTRA_VALUES = "cookieValues"

        fun intent(context: Context, how: Screen.SignOn): Intent = Intent(context, SignOnActivity::class.java)
            .putExtra(EXTRA_SERVER, how.address.toString()).putExtra(EXTRA_STRATEGY, how.method.strategy).putExtra(EXTRA_PIN, how.pinnedSha256)

        /** The cookies a finished page returned, or null if it was cancelled. / Las cookies devueltas, o null si se canceló. */
        fun cookies(resultCode: Int, data: Intent?): Map<String, String>? {
            if (resultCode != RESULT_OK) return null
            val names = data?.getStringArrayExtra(EXTRA_NAMES) ?: return null
            val values = data.getStringArrayExtra(EXTRA_VALUES) ?: return null
            return if (names.size == values.size) names.zip(values).toMap() else null
        }
    }
}
