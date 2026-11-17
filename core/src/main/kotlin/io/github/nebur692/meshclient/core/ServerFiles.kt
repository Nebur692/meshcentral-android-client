package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** A file or folder kept on the server; [modifiedMs] null for folders. / Un fichero o carpeta guardado en el servidor. */
data class ServerFileEntry(val name: String, val isFolder: Boolean, val size: Long, val modifiedMs: Long?, val children: List<ServerFileEntry>) {
    /** Bytes in this entry and below. / Bytes de esta entrada y lo que tiene dentro. */
    val totalBytes: Long get() = if (isFolder) children.sumOf { it.totalBytes } else size

    internal companion object {
        fun listOf(f: Any?): List<ServerFileEntry> = (f as? JsonObject).orEmpty().mapNotNull { (name, v) ->
            val e = v as? JsonObject ?: return@mapNotNull null
            val folder = e.long("t") == 2L
            ServerFileEntry(name, folder, if (folder) 0 else e.long("s") ?: 0, if (folder) null else e.long("d")?.takeIf { it > 1000 },
                if (folder) listOf(e["f"]) else emptyList())
        }.sortedWith(compareBy<ServerFileEntry> { !it.isFolder }.thenBy { it.name.lowercase() })
    }
}

/**
 * One top folder of the server's files: the account's own ("My Files") or a device group's, when the account has the
 * group's server files right. [id] is the path's first part; [maxBytes] the quota, null when there is none.
 * Una carpeta de arriba: la de la cuenta («Mis ficheros») o la de un grupo de equipos.
 */
data class ServerFileRoot(val id: String, val name: String, val isGroup: Boolean, val maxBytes: Long?, val entries: List<ServerFileEntry>) {
    val usedBytes: Long get() = entries.sumOf { it.totalBytes }

    /** The folder at [folder], or null when it is not there. / La carpeta en esa ruta, o null. */
    fun find(folder: List<String>): List<ServerFileEntry>? {
        var here = entries
        for (part in folder) here = here.firstOrNull { it.isFolder && it.name == part }?.children ?: return null
        return here
    }
}

/*
 * The server's own files, as the website's "My Files" (meshuser.js files and fileoperation), the same requests the
 * Windows app measured on 1.3.1. The server sends the whole tree when asked and again after each change, sometimes twice
 * (a new file first empty, then written), so each change waits for the tree that shows it done. Downloads go through the
 * same channel below 200 KB (bigger ones need a website session); uploads use "set". It needs site right 8.
 * Los ficheros del propio servidor, como «Mis ficheros» de la web. El servidor manda el árbol entero al pedirlo y otra vez
 * tras cada cambio, a veces dos (un fichero nuevo primero vacío y luego escrito), así que cada cambio espera al árbol que
 * ya lo muestra hecho. Bajar va por el mismo canal por debajo de 200 KB; subir usa «set». Pide el permiso de sitio 8.
 */

/** The server sends a file back only below this size. / El servidor solo devuelve ficheros por debajo de esto. */
const val SERVER_FILE_DOWNLOAD_LIMIT = 204_800L

/** The app sends at most this much in one message. / La app manda como mucho esto en un mensaje. */
const val SERVER_FILE_UPLOAD_LIMIT = 20L * 1024 * 1024

val MeshSession.canUseServerFiles: Boolean get() = user.siteRights and SiteRights.SERVER_FILES != 0L

// As the server's IsFilenameValid. / Como IsFilenameValid del servidor.
private val badName = Regex("""[\\/:*?"<>|]|^\.|^(nul|prn|con|lpt[0-9]|com[0-9])(\.|$)""", RegexOption.IGNORE_CASE)

fun isValidServerFileName(name: String): Boolean = name.length in 1..4096 && !badName.containsMatchIn(name)

private fun serverFilesDenied() = MeshClientException(MeshFailure.NotAllowed, "ficheros-servidor/ver-pantalla/no-autorizado", "This account has no rights to the server's files.")

private fun isFileTree(o: JsonObject) = o.str("action") == "files" && o["filetree"] is JsonObject

private fun roots(files: JsonObject): List<ServerFileRoot> =
    ((files["filetree"] as? JsonObject)?.get("f") as? JsonObject).orEmpty().mapNotNull { (id, v) ->
        val t = v as? JsonObject ?: return@mapNotNull null
        ServerFileRoot(id, t.str("n") ?: id, t.long("t") == 4L, t.long("maxbytes")?.takeIf { it > 0 }, ServerFileEntry.listOf(t["f"]))
    }.sortedWith(compareBy<ServerFileRoot> { it.isGroup }.thenBy { it.name.lowercase() })

private fun pathOf(root: ServerFileRoot, folder: List<String>) = JsonArray((listOf(root.id) + folder).map(::JsonPrimitive))

suspend fun MeshSession.serverFiles(): List<ServerFileRoot> {
    if (!canUseServerFiles) throw serverFilesDenied()
    return roots(request(buildJsonObject { put("action", "files") }, ::isFileTree,
        "ficheros-servidor/ver-pantalla/tiempo-agotado", "ficheros-servidor/ver-pantalla/conexion-cortada"))
}

suspend fun MeshSession.createServerFolder(root: ServerFileRoot, folder: List<String>, name: String): List<ServerFileRoot> =
    change(root, folder, listOf(name), buildJsonObject { put("fileop", "createfolder"); put("newfolder", name.trim()) },
        { here -> here.any { it.isFolder && it.name == name.trim() } })

suspend fun MeshSession.renameServerFile(root: ServerFileRoot, folder: List<String>, oldName: String, newName: String): List<ServerFileRoot> =
    change(root, folder, listOf(oldName, newName), buildJsonObject { put("fileop", "rename"); put("oldname", oldName); put("newname", newName.trim()) },
        { here -> here.any { it.name == newName.trim() } && here.none { it.name == oldName } })

/** Deletes files and folders with all they hold. / Borra ficheros y carpetas con todo lo que tienen. */
suspend fun MeshSession.deleteServerFiles(root: ServerFileRoot, folder: List<String>, names: List<String>): List<ServerFileRoot> =
    change(root, folder, names, buildJsonObject { put("fileop", "delete"); put("delfiles", JsonArray(names.map(::JsonPrimitive))); put("rec", true) },
        { here -> here.none { it.name in names } })

/** Puts a file in the folder, replacing one with its name. / Deja un fichero en la carpeta, sustituyendo al del mismo nombre. */
suspend fun MeshSession.uploadServerFile(root: ServerFileRoot, folder: List<String>, name: String, data: ByteArray): List<ServerFileRoot> {
    if (data.size > SERVER_FILE_UPLOAD_LIMIT)
        throw MeshClientException(MeshFailure.Rejected, "ficheros-servidor/subir/no-implementado", "The app sends files of up to ${SERVER_FILE_UPLOAD_LIMIT / 1048576} MB.")
    val replaced = root.find(folder)?.firstOrNull { !it.isFolder && it.name == name }?.size ?: 0
    val max = root.maxBytes
    if (max != null && root.usedBytes - replaced + data.size > max)
        throw MeshClientException(MeshFailure.Rejected, "ficheros-servidor/subir/estado-imposible", "The file does not fit in what is left of the quota.")
    return change(root, folder, listOf(name),
        buildJsonObject { put("fileop", "set"); put("file", name.trim()); put("data", Base64.getEncoder().encodeToString(data)) },
        { here -> here.any { !it.isFolder && it.name == name.trim() && it.size == data.size.toLong() } },
        "ficheros-servidor/subir", maxOf(30_000L, data.size / 200L))
}

suspend fun MeshSession.downloadServerFile(root: ServerFileRoot, folder: List<String>, file: ServerFileEntry): ByteArray {
    if (!canUseServerFiles) throw serverFilesDenied()
    if (file.isFolder || file.size >= SERVER_FILE_DOWNLOAD_LIMIT)
        throw MeshClientException(MeshFailure.Rejected, "ficheros-servidor/descargar/no-implementado", "The server only sends files below 200 KB to an app; bigger ones are downloaded from its website.")
    val id = MeshSession.newId()
    val r = request(buildJsonObject {
        put("action", "fileoperation"); put("fileop", "get"); put("path", pathOf(root, folder)); put("file", file.name); put("responseid", id)
    }, { it.str("action") == "fileoperation" && it.str("responseid") == id && it["data"] is JsonPrimitive },
        "ficheros-servidor/descargar/tiempo-agotado", "ficheros-servidor/descargar/conexion-cortada")
    return try { Base64.getDecoder().decode(r.str("data").orEmpty()) } catch (e: IllegalArgumentException) {
        throw MeshClientException(MeshFailure.UnexpectedResponse, "ficheros-servidor/descargar/respuesta-ilegible", "The server sent the file unreadable.", e)
    }
}

private suspend fun MeshSession.change(
    root: ServerFileRoot, folder: List<String>, names: List<String>, op: JsonObject, done: (List<ServerFileEntry>) -> Boolean,
    process: String = "ficheros-servidor/gestionar-ficheros", timeout: Long = timeoutMs,
): List<ServerFileRoot> {
    if (!canUseServerFiles) throw serverFilesDenied()
    if (names.isEmpty() || names.any { !isValidServerFileName(it.trim()) } || folder.any { !isValidServerFileName(it) })
        throw MeshClientException(MeshFailure.Rejected, "$process/dato-invalido", "A name is empty, starts with a dot or has one of \\ / : * ? \" < > |.")
    val msg = JsonObject(op + mapOf("action" to JsonPrimitive("fileoperation"), "path" to pathOf(root, folder)))
    val id = root.id
    // No answer of its own: it waits for the tree that shows the change done. / Espera al árbol que ya muestra el cambio hecho.
    return roots(request(msg, { o -> isFileTree(o) && roots(o).firstOrNull { it.id == id }?.find(folder)?.let(done) == true },
        "$process/tiempo-agotado", "$process/conexion-cortada", timeout))
}

/** The website's "My Files" (viewmode 5), where the browser signs in if it has to. / «Mis ficheros» de la web. */
fun MeshSession.serverFilesPageUrl(): String = server.resolve("").newBuilder().addQueryParameter("viewmode", "5").build().toString()
