package io.github.nebur692.meshclient.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Completing account names in a comma-separated list (the same cases as the Windows app). / Completar nombres de cuenta. */
class UserCompletionTest {
    private fun u(id: String, name: String, real: String? = null, email: String? = null) = UserInfo(id, name, 0, email = email, realName = real)

    private val users = listOf(
        u("user//~oidc:383761494229738557", "julian@example.com", "Julián Pérez", "julian@example.com"),
        u("user//juana", "juana", null, "juana@example.com"),
        u("user//admin", "admin", "Administrador"),
        u("user//pablo", "pablo", "Pablo Julio"),
    )

    @Test fun `a single sign-on account is found by name or email and its sign-in name goes in`() {
        val m = UserCompletion.matches(users, "jul")
        assertEquals("~oidc:383761494229738557", UserCompletion.loginName(m[0]))
        assertTrue(m.any { it.name == "pablo" })
        assertEquals("~oidc:383761494229738557, ", UserCompletion.take("jul", m[0]))
        assertEquals("Julián Pérez · julian@example.com · ~oidc:383761494229738557", UserCompletion.label(m[0]))
    }

    @Test fun `only the piece after the last comma, and not those already listed`() {
        assertEquals(listOf("juana"), UserCompletion.matches(users, "admin, jua").map(UserCompletion::loginName))
        assertEquals("admin, juana, ", UserCompletion.take("admin, jua", users[1]))
        assertTrue(UserCompletion.matches(users, "juana, ju").none { it.name == "juana" })
        assertTrue(UserCompletion.matches(users, "admin, ").isEmpty())
        assertTrue(UserCompletion.matches(users, "zzz").isEmpty())
    }
}
