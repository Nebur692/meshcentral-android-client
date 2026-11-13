package io.github.nebur692.meshclient

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android 17's local network protection: an app's traffic to its own local network (the subnets the phone is on, and
 * link-local) is dropped without a word unless it holds ACCESS_LOCAL_NETWORK. A MeshCentral at home or in the office,
 * reached by its inside address, then timed out with nothing reaching the server (measured on the emulator: 26 s and
 * conexion/iniciar-sesion/tiempo-agotado without it, 3 s with it); through the internet it worked, so it came and went
 * with the address the name resolved to.
 * La protección de red local de Android 17: el tráfico de una app a su propia red local (las subredes en las que está el
 * móvil, y la de enlace local) se tira sin avisar salvo que tenga ACCESS_LOCAL_NETWORK. Un MeshCentral en casa o en la
 * oficina, alcanzado por su dirección de dentro, agotaba el tiempo sin llegar nada al servidor (medido en el emulador: 26 s
 * sin él, 3 s con él); por internet sí entraba, así que iba y venía con la dirección a la que se resolvía el nombre.
 */
object LocalNetwork {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** The support code when it is refused. / El código de soporte cuando se deniega. */
    const val KEY = "conexion/iniciar-sesion/red-local"

    /** Android 17 (API 37) and the permission not given. / Android 17 y el permiso sin dar. */
    fun missing(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED

    /**
     * The host resolves to an address of the phone's local network now: on a subnet it is directly on (a route with no
     * gateway), or link-local. / El nombre se resuelve a una dirección de la red local del móvil ahora.
     */
    suspend fun isLocal(context: Context, host: String): Boolean = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp = cm.activeNetwork?.let(cm::getLinkProperties) ?: return@withContext false
        val addresses = withTimeoutOrNull(5_000) { runCatching { InetAddress.getAllByName(host).toList() }.getOrNull() } ?: return@withContext false
        addresses.any { a -> a.isLinkLocalAddress || lp.routes.any { r -> !r.hasGateway() && r.matches(a) } }
    }
}
