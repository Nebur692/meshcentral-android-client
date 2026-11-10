package io.github.nebur692.meshclient

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
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
                    }
                    // Back inside the app before leaving it (predictive on Android 14+). / Atrás dentro de la app antes de salir.
                    BackHandler(enabled = vm.screen is Screen.DeviceDetail || vm.screen == Screen.Account) { vm.show(Screen.Devices) }
                }
            }
        }
    }
}
