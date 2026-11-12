package io.github.nebur692.meshclient

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.nebur692.meshclient.ui.AccountScreen
import io.github.nebur692.meshclient.ui.BiometricOfferSheet
import io.github.nebur692.meshclient.ui.DesktopScreen
import io.github.nebur692.meshclient.ui.DeviceScreen
import io.github.nebur692.meshclient.ui.FilesScreen
import io.github.nebur692.meshclient.ui.TerminalScreen
import io.github.nebur692.meshclient.ui.DevicesScreen
import io.github.nebur692.meshclient.ui.LoginScreen
import io.github.nebur692.meshclient.ui.MeshTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    // The provider's page runs in its own process and answers here. / La página del proveedor corre en su proceso y contesta aquí.
    private val signOn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        SignOnActivity.cookies(r.resultCode, r.data)?.let(vm::finishSignOn) ?: vm.cancelSignOn()
    }

    // Out of sight for a while, the live connection is closed; back, it opens again (battery, data).
    // Fuera de la vista un rato se cierra la conexión en vivo; al volver se abre otra vez (batería, datos).
    // Without it the connection is kept all the same; only the notification is hidden. / Sin él se mantiene igual; solo no se ve el aviso.
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onStart() { super.onStart(); vm.onForeground() }
    override fun onStop() { super.onStop(); vm.onBackground() }

    /**
     * Android's own fingerprint prompt, with the cipher it must unlock: what it seals or opens is only usable after a
     * strong biometric, never with the screen-lock code. / El aviso de huella del propio Android, con el cifrado que
     * debe desbloquear: lo que sella o abre solo sirve tras una biometría fuerte, nunca con el código de bloqueo.
     */
    private fun ask(request: BiometricRequest) {
        val cipher = vm.cipherFor(request) ?: return
        val cancel = CancellationSignal()
        val builder = BiometricPrompt.Builder(this)
            .setTitle(getString(if (request is BiometricRequest.Seal) R.string.biometric_seal_title else R.string.biometric_open_title))
            .setSubtitle(when (request) {
                is BiometricRequest.Seal -> request.address.removePrefix("https://").trimEnd('/')
                is BiometricRequest.Open -> "${request.server.username} · ${request.server.address.removePrefix("https://").trimEnd('/')}"
            })
            .setNegativeButton(getString(R.string.cancel), mainExecutor) { _, _ -> vm.biometricFailed(request, lockedOut = false) }
            .setConfirmationRequired(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        builder.build().authenticate(BiometricPrompt.CryptoObject(cipher), cancel, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let { vm.biometricDone(request, it) } ?: vm.biometricFailed(request, lockedOut = false)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                vm.biometricFailed(request, lockedOut = errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT ||
                    errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT)
            }
        })
    }

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
                        Screen.Devices -> DevicesScreen(vm)
                        Screen.Account -> AccountScreen(vm)
                        is Screen.DeviceDetail -> DeviceScreen(vm, s.id)
                        is Screen.Terminal -> TerminalScreen(vm)
                        is Screen.Files -> FilesScreen(vm)
                        is Screen.Desktop -> DesktopScreen(vm)
                        is Screen.SignOn -> {
                            LoginScreen(vm)
                            if (!vm.signOnLaunched) SideEffect { vm.signOnLaunched = true; signOn.launch(SignOnActivity.intent(this@MainActivity, s)) }
                        }
                    }
                    vm.biometricRequest?.let { r -> LaunchedEffect(r) { ask(r) } }
                    if (vm.askNotifications) LaunchedEffect(Unit) {
                        vm.askNotifications = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    vm.biometricOffer?.let { BiometricOfferSheet(it, vm::answerBiometricOffer) }
                    vm.biometricProblem?.let { p ->
                        AlertDialog(
                            onDismissRequest = { vm.biometricProblem = null },
                            text = { Text(stringResource(when (p) {
                                BiometricProblem.KeyChanged -> R.string.biometric_key_changed
                                BiometricProblem.Locked -> R.string.biometric_locked
                                BiometricProblem.Stale -> R.string.biometric_stale
                                BiometricProblem.Unavailable -> R.string.biometric_unavailable
                            })) },
                            confirmButton = { TextButton(onClick = { vm.biometricProblem = null }) { Text(stringResource(R.string.accept)) } },
                        )
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
