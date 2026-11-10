package io.github.nebur692.meshclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.nebur692.meshclient.ui.DeviceScreen
import io.github.nebur692.meshclient.ui.LoginScreen
import io.github.nebur692.meshclient.ui.MeshTheme
import io.github.nebur692.meshclient.ui.SignedInScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    // The provider's page runs in its own process and answers here. / La página del proveedor corre en su proceso y contesta aquí.
    private val signOn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        SignOnActivity.cookies(r.resultCode, r.data)?.let(vm::finishSignOn) ?: vm.cancelSignOn()
    }

    // Out of sight for a while, the live connection is closed; back, it opens again (battery, data).
    // Fuera de la vista un rato se cierra la conexión en vivo; al volver se abre otra vez (batería, datos).
    override fun onStart() { super.onStart(); vm.onForeground() }
    override fun onStop() { super.onStop(); vm.onBackground() }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Edge to edge on every version, as Android 15 requires. / De borde a borde en todas las versiones.
        enableEdgeToEdge()
        setContent {
            MeshTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (val s = vm.screen) {
                        Screen.Login -> LoginScreen(vm)
                        Screen.Devices, Screen.Account -> SignedInScreen(vm)
                        is Screen.DeviceDetail -> DeviceScreen(vm, s.id)
                        is Screen.SignOn -> {
                            LoginScreen(vm)
                            if (!vm.signOnLaunched) SideEffect { vm.signOnLaunched = true; signOn.launch(SignOnActivity.intent(this@MainActivity, s)) }
                        }
                    }
                    vm.rememberPrompt?.let { p ->
                        AlertDialog(
                            onDismissRequest = { vm.answerRemember(false) },
                            title = { Text(stringResource(R.string.remember_title)) },
                            text = { Text(pluralStringResource(R.plurals.remember_text, p.days, p.method, p.days)) },
                            confirmButton = { TextButton(onClick = { vm.answerRemember(true) }) { Text(stringResource(R.string.remember_yes)) } },
                            dismissButton = { TextButton(onClick = { vm.answerRemember(false) }) { Text(stringResource(R.string.remember_no)) } },
                        )
                    }
                    vm.rememberFailed?.let { e ->
                        AlertDialog(
                            onDismissRequest = { vm.rememberFailed = null },
                            text = { Text(stringResource(R.string.remember_failed) + "\n\n" + stringResource(R.string.error_code, e.key)) },
                            confirmButton = { TextButton(onClick = { vm.rememberFailed = null }) { Text(stringResource(R.string.accept)) } },
                        )
                    }
                    // Back inside the app before leaving it (predictive on Android 14+). / Atrás dentro de la app antes de salir.
                    BackHandler(enabled = vm.screen is Screen.DeviceDetail || vm.screen == Screen.Account) { vm.show(Screen.Devices) }
                }
            }
        }
    }
}
