package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** A group's members keep the name the server sends with each (not only the id). / Cada miembro guarda su nombre. */
class GroupMemberNamesTest {
    @Test fun `each member's name comes with its rights, and one without a name has none`() {
        val g = DeviceGroup.from(Json.parseToJsonElement("""
            {"_id":"mesh//abc","name":"0SkillS","mtype":2,"links":{
              "user//~oidc:383761494229738557":{"name":"Julián Pérez","rights":4294967295},
              "user//nebur692":{"name":"nebur692","rights":4294967295},
              "user//old":{"rights":8}}}
        """.trimIndent()) as JsonObject)
        assertEquals("Julián Pérez", g.memberNames["user//~oidc:383761494229738557"])
        assertEquals(null, g.memberNames["user//old"])
        assertEquals(3, g.links.size)
    }
}
