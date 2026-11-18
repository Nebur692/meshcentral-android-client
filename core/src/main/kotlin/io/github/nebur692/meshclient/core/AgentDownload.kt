package io.github.nebur692.meshclient.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The Windows agents the website offers (meshagents id). / Los agentes de Windows que ofrece la web. */
enum class AgentPlatform(val id: Int) { Windows64(4), Windows32(3), WindowsArm64(43) }

/*
 * The other ways to add a device to a group, as the website's "Add Agent" and the Windows app: the Windows agent with
 * the group built in, and the install command for Linux, BSD and macOS (meshinstall.sh).
 * Las otras formas de añadir un equipo a un grupo, como «Add Agent» de la web y la app de Windows: el agente de Windows
 * con el grupo dentro y la orden de instalación para Linux, BSD y macOS.
 */

/** The group id the agent links use: the last part of "mesh/domain/id". / El id que usan los enlaces del agente. */
fun shortGroupId(groupId: String): String = groupId.substringAfterLast('/')

fun MeshSession.agentDownloadUrl(group: DeviceGroup, platform: AgentPlatform, mode: AgentInstallMode = AgentInstallMode.BackgroundAndInteractive): String =
    server.resolve("meshagents").newBuilder().addQueryParameter("id", platform.id.toString())
        .addQueryParameter("meshid", shortGroupId(group.id)).addQueryParameter("installflags", mode.flags.toString()).build().toString()

/** The website's install command for Linux, BSD and macOS. / La orden de instalación de la web para Linux, BSD y macOS. */
fun MeshSession.linuxInstallCommand(group: DeviceGroup): String {
    val base = server.resolve("").toString().trimEnd('/')
    val script = server.resolve("meshagents?script=1").toString()
    // Feature 0x80000000: the server's certificate is not a public one. / El certificado del servidor no es público.
    val noCheck = if (serverInfo.features and 0x80000000L != 0L) " --no-check-certificate" else ""
    val id = shortGroupId(group.id)
    return "(wget \"$script\"$noCheck -O ./meshinstall.sh || wget \"$script\" --no-proxy$noCheck -O ./meshinstall.sh) && chmod 755 ./meshinstall.sh && sudo -E ./meshinstall.sh $base '$id' || ./meshinstall.sh $base '$id'"
}

/** Downloads the Windows agent of the group into [out]; returns the bytes written. / Baja el agente de Windows del grupo. */
suspend fun MeshSession.downloadAgent(group: DeviceGroup, platform: AgentPlatform, mode: AgentInstallMode, out: java.io.OutputStream): Long =
    withContext(Dispatchers.IO) {
        if (!groupAbilitiesOn(group).addDevices) throw MeshClientException(MeshFailure.NotAllowed, "grupos/descargar/no-autorizado", "This account has no rights to add devices to this device group.")
        val call = http.newCall(okhttp3.Request.Builder().url(agentDownloadUrl(group, platform, mode)).build())
        val response = try { call.execute() } catch (e: java.io.IOException) {
            throw MeshClientException(MeshFailure.ServerUnreachable, "grupos/descargar/sin-conexion", "The agent could not be downloaded from the server.", e)
        }
        response.use { resp ->
            // 401: the server only lets signed-in browsers download agents (lockagentdownload). / Solo a navegadores con sesión.
            if (resp.code == 401) throw MeshClientException(MeshFailure.HttpError, "grupos/descargar/no-autorizado", "The server only gives agents to signed-in browsers.").apply { httpStatus = 401 }
            if (!resp.isSuccessful) throw MeshClientException(MeshFailure.HttpError, "grupos/descargar/error-remoto", "The server answered HTTP ${resp.code}.").apply { httpStatus = resp.code }
            var total = 0L
            try {
                resp.body!!.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    // A Windows program starts with "MZ"; anything else is an error page. / Un programa de Windows empieza por «MZ».
                    // Byte by byte: InputStream.readNBytes only exists from Android 13. / readNBytes solo existe desde Android 13.
                    val head = byteArrayOf(input.read().toByte(), input.read().toByte())
                    if (head[0] != 'M'.code.toByte() || head[1] != 'Z'.code.toByte())
                        throw MeshClientException(MeshFailure.UnexpectedResponse, "grupos/descargar/respuesta-ilegible", "The server did not send a Windows program.")
                    out.write(head); total = 2
                    while (true) {
                        ensureActive()
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); total += n
                    }
                }
            } catch (e: java.io.IOException) {
                throw MeshClientException(MeshFailure.ConnectionLost, "grupos/descargar/sin-conexion", "The download was cut.", e)
            }
            total
        }
    }
