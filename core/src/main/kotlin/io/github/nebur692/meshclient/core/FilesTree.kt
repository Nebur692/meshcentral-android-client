package io.github.nebur692.meshclient.core

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Whole selections, folders included: delete, copy and move on the device itself. The agent does not answer rm, copy,
 * move or mkdir, so each step is confirmed with a new listing. The same as the Windows app's FilesSession.Tree.
 * Selecciones enteras, carpetas incluidas: borrar, copiar y mover en el propio equipo. El agente no contesta a rm, copy,
 * move ni mkdir, así que cada paso se confirma volviendo a listar. Igual que FilesSession.Tree de la app de Windows.
 */

/** Copies files (not folders) inside the device: what the website's paste does. / Copia ficheros (no carpetas) dentro del equipo. */
suspend fun FilesSession.copy(fromFolder: String, toFolder: String, names: List<String>) =
    commandOf(buildJsonObject {
        put("action", "copy"); put("reqid", MeshSession.newId().take(12)); put("scpath", fromFolder); put("dspath", toFolder)
        put("names", JsonArray(names.map(::JsonPrimitive)))
    })

/** Lists [folder] until [done] holds, or fails. / Lista hasta que se cumpla, o falla. */
suspend fun FilesSession.waitFor(folder: String, what: String, done: (RemoteFolder) -> Boolean): RemoteFolder {
    repeat(25) {
        val f = list(folder)
        if (done(f)) return f
        delay(200)
    }
    throw MeshClientException(MeshFailure.Rejected, "ficheros/gestionar-ficheros/rechazo-remoto", "The device did not $what.")
}

/** True when [target] is [folder] or inside it. / Si el destino es la carpeta o está dentro. */
fun isInside(target: String, folder: String): Boolean {
    fun n(p: String) = p.replace('\\', '/').trimEnd('/') + "/"
    return n(target).startsWith(n(folder), ignoreCase = true)
}

/** Deletes several entries at once (folders with all inside) and checks they are gone. / Borra varias a la vez y comprueba que no están. */
suspend fun FilesSession.deleteAll(folder: String, entries: List<RemoteEntry>) {
    val names = entries.map { it.name }
    delete(folder, names, recursive = entries.any { it.kind != RemoteEntryKind.File })
    waitFor(folder, "delete everything selected") { f -> f.entries.none { it.name in names } }
}

/** Copies [entries] of [fromFolder] into [toFolder] on the device, folders and all. / Copia entradas a otra carpeta, carpetas incluidas. */
suspend fun FilesSession.copyTree(fromFolder: String, entries: List<RemoteEntry>, toFolder: String) {
    for (e in entries) if (e.kind == RemoteEntryKind.Folder && isInside(toFolder, RemotePaths.join(fromFolder, e.name)))
        throw MeshClientException(MeshFailure.Rejected, "ficheros/gestionar-ficheros/dato-invalido", "\"${e.name}\" cannot be copied inside itself.")
    val files = entries.filter { it.kind == RemoteEntryKind.File }
    if (files.isNotEmpty()) copy(fromFolder, toFolder, files.map { it.name })
    for (dir in entries.filter { it.kind == RemoteEntryKind.Folder }) {
        val target = RemotePaths.join(toFolder, dir.name)
        createFolder(target)
        waitFor(toFolder, "create the folder \"${dir.name}\"") { f -> f.entries.any { it.name == dir.name && it.kind == RemoteEntryKind.Folder } }
        val inside = list(RemotePaths.join(fromFolder, dir.name))
        copyTree(RemotePaths.join(fromFolder, dir.name), inside.entries, target)
    }
    // A copy is done when every file is there with its size. / Una copia acaba cuando cada fichero está con su tamaño.
    waitFor(toFolder, "copy everything") { f -> files.all { s -> f.entries.any { it.name == s.name && it.size == s.size } } }
}

/** Moves entries inside the device: a checked copy, then the originals are deleted. / Copia comprobada y luego se borran los originales. */
suspend fun FilesSession.moveTree(fromFolder: String, entries: List<RemoteEntry>, toFolder: String) {
    if (isInside(fromFolder, toFolder) && isInside(toFolder, fromFolder)) return // the same folder / la misma carpeta
    copyTree(fromFolder, entries, toFolder)
    deleteAll(fromFolder, entries)
}
