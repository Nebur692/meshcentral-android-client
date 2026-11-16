package io.github.nebur692.meshclient.core

/**
 * Completing a remote path as it is typed: the folder already typed is listed and its subfolders that start with the rest
 * are offered. Paths are the app's ("/" separators; on Windows "C:/Users", and "" for the drives). The same as the Windows
 * app's PathCompletion.
 * Completar una ruta remota mientras se escribe: se lista la carpeta ya escrita y se ofrecen sus subcarpetas que empiezan
 * por el resto.
 */
object PathCompletion {
    /** The folder to list and what is typed of the next name. / La carpeta a listar y lo escrito del siguiente nombre. */
    fun split(typed: String, windows: Boolean): Pair<String, String> {
        // Repeated separators count as one ("//us" is "/us"). / Las barras repetidas cuentan como una.
        val t = typed.trim().replace('\\', '/').replace(Regex("/{2,}"), "/")
        val i = t.lastIndexOf('/')
        if (i < 0) return (if (windows) "" else "/") to t
        var folder = t.substring(0, i + 1)
        // "/" and "C:/" stay as they are; "C:/Users/" is the folder "C:/Users". / «/» y «C:/» se quedan.
        if (folder.length > 1 && !(windows && folder.length == 3 && folder[1] == ':')) folder = folder.trimEnd('/')
        return folder to t.substring(i + 1)
    }

    /** The folders (and drives) whose name starts with the prefix, in order. / Las carpetas que empiezan por lo escrito. */
    fun matches(entries: List<RemoteEntry>, prefix: String): List<String> =
        entries.filter { it.kind != RemoteEntryKind.File && it.name.startsWith(prefix, ignoreCase = true) }
            .map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER)

    /** The path once a name is taken, ending in "/" so the next level can be typed. / La ruta al aceptar un nombre. */
    fun accept(folder: String, name: String): String = RemotePaths.join(folder, name).let { if (it.endsWith('/')) it else "$it/" }

    /** A typed path as the app keeps it: "C:/Users/" is "C:/Users"; "/" and "C:/" stay. / Una ruta escrita como la guarda la app. */
    fun normalise(typed: String): String {
        // Repeated separators count as one ("//us" is "/us"). / Las barras repetidas cuentan como una.
        val t = typed.trim().replace('\\', '/').replace(Regex("/{2,}"), "/")
        return if (t.length > 1 && t.endsWith('/') && !(t.length == 3 && t[1] == ':')) t.trimEnd('/') else t
    }
}
