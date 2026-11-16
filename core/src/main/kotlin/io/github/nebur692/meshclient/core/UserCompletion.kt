package io.github.nebur692.meshclient.core

/**
 * Completing account names as they are typed in a comma-separated list: the piece after the last comma is looked for in
 * the server's accounts by sign-in name, display name or email, and taking one writes its sign-in name (the last part of
 * its id: an account made by single sign-on is "~oidc:…", which nobody knows by heart). The same as the Windows app's.
 * Completar nombres de cuenta en una lista con comas: lo que va tras la última coma se busca por usuario, nombre o correo,
 * y al tomar una se escribe su usuario de entrar.
 */
object UserCompletion {
    /** What the server takes as the user name. / Lo que el servidor toma como nombre. */
    fun loginName(u: UserInfo): String = u.id.split('/').takeIf { it.size == 3 && it[2].isNotEmpty() }?.get(2) ?: u.name

    /** The text up to the last comma, and the piece being typed. / Lo anterior y el trozo que se escribe. */
    fun split(text: String): Pair<String, String> {
        val i = text.lastIndexOf(',')
        return if (i < 0) "" to text.trim() else text.substring(0, i + 1) to text.substring(i + 1).trim()
    }

    /** The accounts that match the piece (beginning first), without those already in the list. / Las cuentas que encajan. */
    fun matches(users: List<UserInfo>, text: String, max: Int = 20): List<UserInfo> {
        val (before, typed) = split(text)
        if (typed.isEmpty()) return emptyList()
        val already = before.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        fun fields(u: UserInfo) = listOfNotNull(loginName(u), u.name, u.realName, u.email).filter { it.isNotEmpty() }
        return users.filter { loginName(it).lowercase() !in already }
            .map { u -> u to when {
                fields(u).any { it.startsWith(typed, ignoreCase = true) } -> 0
                fields(u).any { it.contains(typed, ignoreCase = true) } -> 1
                else -> 2
            } }
            .filter { it.second < 2 }.sortedWith(compareBy<Pair<UserInfo, Int>> { it.second }.thenBy(String.CASE_INSENSITIVE_ORDER) { label(it.first) })
            .map { it.first }.take(max)
    }

    /** The list with the piece replaced by the account's sign-in name, ready for the next. / La lista con el trozo cambiado. */
    fun take(text: String, u: UserInfo): String {
        val before = split(text).first
        return (if (before.isEmpty()) "" else before.trimEnd() + " ") + loginName(u) + ", "
    }

    /** How an account shows: its name, its email and, if different, its sign-in name. / Cómo sale una cuenta. */
    fun label(u: UserInfo): String {
        val name = u.realName?.takeIf { it.isNotEmpty() } ?: u.name
        val parts = mutableListOf(name)
        u.email?.takeIf { it.isNotEmpty() && !it.equals(name, ignoreCase = true) }?.let(parts::add)
        loginName(u).takeIf { !it.equals(name, ignoreCase = true) }?.let(parts::add)
        return parts.joinToString(" · ")
    }
}
