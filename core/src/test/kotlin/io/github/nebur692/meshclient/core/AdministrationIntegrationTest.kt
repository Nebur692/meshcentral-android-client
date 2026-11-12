package io.github.nebur692.meshclient.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Administration against the bench's real MeshCentral, as its full administrator: what the Windows app measured.
 * La administración contra el MeshCentral de verdad del banco, como su administrador completo.
 */
class AdministrationIntegrationTest {
    private val url = System.getenv("MESHTEST_URL")
    private val cert = System.getenv("MESHTEST_CERT_SHA256")
    private fun need() = assumeTrue("no test server", !url.isNullOrEmpty() && !cert.isNullOrEmpty())
    private suspend fun admin() = MeshSession.connect(ServerAddress.parse(url)!!, PasswordCredentials("admin", "Admin-Pass-1234"), CertificateTrust(cert))
    private val stamp = System.nanoTime().toString().takeLast(6)

    private suspend fun failure(block: suspend () -> Unit): MeshClientException =
        try { block(); fail("it did not fail"); error("") } catch (e: MeshClientException) { e }

    @Test fun `a device group is created, renamed, gives an invite link and is deleted`() = runBlocking {
        need()
        val s = admin()
        try {
            assertTrue(s.canCreateGroups)
            val id = s.createGroup("Grupo $stamp", "de prueba")
            var g = s.deviceGroups().first { it.id == id }
            assertEquals("Grupo $stamp", g.name)
            assertEquals(GroupAbilities(edit = true, manageUsers = true, delete = true, addDevices = true, receiveDevices = true), s.groupAbilitiesOn(g))
            s.editGroup(g, "Grupo $stamp b", null)
            g = s.deviceGroups().first { it.id == id }
            assertEquals("Grupo $stamp b", g.name)
            val link = s.createInviteLink(g, 1)
            assertTrue("odd link $link", link.startsWith("https://") && "agentinvite" in link)
            assertEquals("grupos/gestionar-grupos/dato-invalido", failure { s.createGroup("  ") }.key)
            s.deleteGroup(g)
            assertTrue(s.deviceGroups().none { it.id == id })
        } finally { s.close() }
    }

    @Test fun `an account is created, edited, locked, given a password and rights on a group, and deleted`() = runBlocking {
        need()
        val s = admin()
        val name = "tecnico$stamp"
        var groupId: String? = null
        try {
            assertTrue(s.canManageUsers)
            s.createUser(NewUser(name, "Una-Clave-Larga-1234", realName = "Técnico de prueba"))
            assertEquals("usuarios/gestionar-usuarios/conflicto-estado", failure { s.createUser(NewUser(name, "Otra-Clave-1234")) }.key)
            var u = s.users().first { it.name == name }
            assertEquals("Técnico de prueba", u.realName)
            s.editUser(u, "tecnico$stamp@example.com", null, null)
            u = s.users().first { it.name == name }
            assertEquals("tecnico$stamp@example.com", u.email)
            s.setLocked(u, true)
            u = s.users().first { it.name == name }
            assertTrue(u.isLocked)
            s.setLocked(u, false)
            u = s.users().first { it.name == name }
            assertFalse(u.isLocked)
            // Never answered: confirmed by its event. / Nunca contesta: se confirma con su aviso.
            s.setUserPassword(u, "Nueva-Clave-Larga-5678", mustChange = true, removeTwoFactor = false)
            assertTrue(s.users().first { it.name == name }.mustChangePassword)
            // Rights on a group by user name; an unknown name is named back. / Un nombre desconocido se dice.
            groupId = s.createGroup("Miembros $stamp")
            var g = s.deviceGroups().first { it.id == groupId }
            s.addGroupMembers(g, listOf(name), MeshRights.REMOTE_CONTROL)
            g = s.deviceGroups().first { it.id == groupId }
            assertEquals(MeshRights.REMOTE_CONTROL, g.links[u.id])
            val unknown = failure { s.addGroupMembers(g, listOf("nadie$stamp"), MeshRights.REMOTE_CONTROL) }
            assertEquals(MeshFailure.NotFound, unknown.failure)
            s.changeGroupMemberRights(g, u.id, MeshRights.REMOTE_CONTROL or MeshRights.WAKE)
            g = s.deviceGroups().first { it.id == groupId }
            assertEquals(MeshRights.REMOTE_CONTROL or MeshRights.WAKE, g.links[u.id])
            s.removeGroupMember(g, u.id)
            assertNull(s.deviceGroups().first { it.id == groupId }.links[u.id])
            // What the account did is in its events. / Lo que hizo la cuenta está en sus sucesos.
            assertTrue(s.serverEvents(200).isNotEmpty())
            s.deleteUser(u)
            assertTrue(s.users().none { it.name == name })
        } finally {
            groupId?.let { id -> s.deviceGroups().firstOrNull { it.id == id }?.let { s.deleteGroup(it) } }
            s.users().firstOrNull { it.name == name }?.let { s.deleteUser(it) }
            s.close()
        }
    }

    @Test fun `a user group is created, renamed, gets and loses a member, and is deleted`() = runBlocking {
        need()
        val s = admin()
        val name = "miembro$stamp"
        try {
            s.createUser(NewUser(name, "Una-Clave-Larga-1234"))
            val u = s.users().first { it.name == name }
            val id = s.createUserGroup("Equipo $stamp", "uno")
            var g = s.userGroups().first { it.id == id }
            s.editUserGroup(g, "Equipo $stamp b", "dos")
            g = s.userGroups().first { it.id == id }
            assertEquals("Equipo $stamp b", g.name); assertEquals("dos", g.description)
            s.addUsersToUserGroup(g, listOf(name))
            g = s.userGroups().first { it.id == id }
            assertTrue(u.id in g.members)
            assertEquals(MeshFailure.NotFound, failure { s.addUsersToUserGroup(s.userGroups().first { it.id == id }, listOf("nadie$stamp")) }.failure)
            s.removeUserFromUserGroup(g, u.id)
            assertFalse(u.id in s.userGroups().first { it.id == id }.members)
            s.deleteUserGroup(s.userGroups().first { it.id == id })
            assertTrue(s.userGroups().none { it.id == id })
        } finally {
            s.users().firstOrNull { it.name == name }?.let { s.deleteUser(it) }
            s.close()
        }
    }

    @Test fun `the bench's device moves to another group and back`() = runBlocking {
        need()
        val s = admin()
        var otherId: String? = null
        try {
            s.deviceGroups()
            var d: Device? = null
            repeat(30) { if (d == null) { d = s.devices().firstOrNull { it.hasAgent }; if (d == null) delay(1000) } }
            val device = assertNotNull("no agent", d).let { d!! }
            val home = s.deviceGroups().first { it.id == device.groupId }
            otherId = s.createGroup("Destino $stamp")
            val other = s.deviceGroups().first { it.id == otherId }
            assertTrue(s.canMove(device, other))
            s.moveDevice(device, other)
            var moved: Device? = null
            repeat(20) { if (moved?.groupId != other.id) { moved = s.devices().firstOrNull { it.id == device.id }; if (moved?.groupId != other.id) delay(500) } }
            assertEquals(other.id, moved?.groupId)
            s.moveDevice(moved!!, home)
            repeat(20) { if (moved?.groupId != home.id) { moved = s.devices().firstOrNull { it.id == device.id }; if (moved?.groupId != home.id) delay(500) } }
            assertEquals(home.id, moved?.groupId)
        } finally {
            otherId?.let { id -> s.deviceGroups().firstOrNull { it.id == id }?.let { s.deleteGroup(it) } }
            s.close()
        }
    }
}
