package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes taken from MeshCentral 1.3.1's own code and the Windows app's measurements. / Formas del código de MeshCentral. */
class DeviceUnitTest {
    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun `security comes from the Security Center, the antivirus list, Defender and a pending restart`() {
        val d = Device.from(json("""{"_id":"node//a","name":"PC","wsc":{"antiVirus":"OK","firewall":"BAD","autoUpdate":"OK"},
            "av":[{"product":"Microsoft Defender","enabled":true,"updated":false}],"defender":{"RealTimeProtection":true},
            "pr":["Windows Update"],"users":["EMPRESA\\ana"],"lusers":["EMPRESA\\ana"],"lastbootuptime":1700000000000,
            "agent":{"id":4,"caps":15,"core":"Oct 2 2026"},"intelamt":{"state":2},"links":{"user//x":{"rights":8}}}"""))
        val sec = d.security
        assertEquals(true, sec.antivirusOk); assertEquals(false, sec.firewallOk); assertEquals(true, sec.updatesOk)
        assertEquals(listOf(Antivirus("Microsoft Defender", enabled = true, updated = false)), sec.antivirus)
        assertEquals(true, sec.realTimeProtection)
        assertEquals(listOf("Windows Update"), sec.pendingRestart)
        assertEquals(listOf("EMPRESA\\ana"), d.users); assertEquals(listOf("EMPRESA\\ana"), d.lockedUsers)
        assertEquals(1700000000000, d.lastBoot); assertEquals("Oct 2 2026", d.agentCore)
        assertTrue(d.hasIntelAmt); assertTrue(d.isWindows); assertEquals(mapOf("user//x" to 8L), d.links)
    }

    @Test fun `a device that says nothing about security says nothing, Linux uses its own centre`() {
        assertFalse(Device.from(json("""{"_id":"node//b"}""")).security.isKnown)
        val linux = Device.from(json("""{"_id":"node//c","lsc":{"antiVirus":"BAD","firewall":"OK"}}""")).security
        assertEquals(false, linux.antivirusOk); assertEquals(true, linux.firewallOk); assertNull(linux.updatesOk)
    }

    @Test fun `Windows inventory - processor, memory, disks, volumes with BitLocker, model and serial`() {
        val hw = HardwareSummary.from(json("""{
            "identifiers":{"cpu_name":"Intel(R) Core(TM) i5-12400","product_name":"OptiPlex 3000","bios_serial":"7GXK2Q3",
              "board_vendor":"Dell Inc.","board_name":"0X1Y2Z","bios_vendor":"Dell Inc.","bios_version":"1.21.0",
              "gpu_name":["Intel(R) UHD Graphics 730"],"storage_devices":[{"Model":"KIOXIA 512GB","Size":512110190592},{"Caption":"Lector","Size":null}]},
            "windows":{"memory":[{"Capacity":"8589934592","SMBIOSMemoryType":26,"ConfiguredClockSpeed":3200,"Manufacturer":"Samsung"},
                                  {"Capacity":"8589934592","SMBIOSMemoryType":26,"ConfiguredClockSpeed":3200,"Manufacturer":"Samsung"}],
              "volumes":{"C":{"name":"","type":"NTFS","size":511000000000,"sizeremaining":311000000000,"protectionStatus":1,"volumeStatus":1},
                         "D":{"name":"Datos","type":"NTFS","size":1000000000000,"sizeremaining":20000000000}},
              "osinfo":{"Caption":"Microsoft Windows 11 Pro","Version":"10.0.26100","OSArchitecture":"64-bit"}}}"""))
        assertEquals("Intel(R) Core(TM) i5-12400", hw.cpu)
        assertEquals(16L shl 30, hw.totalMemory)
        assertEquals("DDR4", hw.memory[0].type)
        assertEquals(listOf(DiskInfo("KIOXIA 512GB", 512110190592, null)), hw.disks)
        assertEquals(Encryption.Encrypted, hw.volumes.first { it.name == "C:" }.encryption)
        assertNull(hw.volumes.first { it.name == "D:" }.encryption)
        assertEquals(0.98f, hw.volumes.first { it.name == "D:" }.usedFraction, 0.001f)
        assertEquals("OptiPlex 3000", hw.model); assertEquals("7GXK2Q3", hw.serial)
        assertEquals("Dell Inc. · 1.21.0", hw.bios)
        assertEquals("Windows 11 Pro (10.0.26100) 64-bit", Os.describe(json("""{"windows":{"osinfo":{"Caption":"Microsoft Windows 11 Pro","Version":"10.0.26100","OSArchitecture":"64-bit"}}}""")))
    }

    @Test fun `Linux inventory - sizes as text, volumes in KiB without the virtual ones, placeholders left out`() {
        val hw = HardwareSummary.from(json("""{
            "identifiers":{"product_name":"To Be Filled By O.E.M.","bios_serial":"Default string"},
            "linux":{"memory":{"Memory_Device":[{"Size":"16 GB","Type":"DDR4"},{"Size":"No Module Installed"}]},
              "volumes":[{"mount_point":"/","type":"ext4","size":1000,"available":250},{"mount_point":"/run/user/0","type":"tmpfs","size":10,"available":10},
                         {"mount_point":"/var/lib/docker/overlay2/x","type":"overlay","size":5,"available":1}]}}"""))
        assertEquals(16L shl 30, hw.totalMemory)
        assertEquals(listOf("/"), hw.volumes.map { it.name })
        assertEquals(1000L * 1024, hw.volumes[0].bytes)
        assertNull(hw.model); assertNull(hw.serial)
    }

    @Test fun `BitLocker as MeshCentral's website reads it, old and new records`() {
        fun enc(v: String) = HardwareSummary.from(json("""{"windows":{"volumes":{"C":{"size":10,"sizeremaining":1,$v}}}}""")).volumes[0].encryption
        assertEquals(Encryption.Locked, enc(""""protectionStatus":2,"volumeStatus":1"""))
        assertEquals(Encryption.Encrypting, enc(""""protectionStatus":0,"volumeStatus":2"""))
        assertEquals(Encryption.NotEncrypted, enc(""""protectionStatus":0,"volumeStatus":0"""))
        assertEquals(Encryption.Encrypted, enc(""""volumeStatus":"Fully Encrypted""""))
        assertEquals(Encryption.NotEncrypted, enc(""""volumeStatus":"Fully Decrypted""""))
    }

    private val server = ServerInfo("s", "", null)
    private val group = DeviceGroup("mesh//g", "G", null, DeviceGroupKind.Agents, mapOf("user//ana" to MeshRights.REMOTE_COMMAND, "ugrp//tec" to MeshRights.RESET_OFF))
    private val device = Device("node//d", "mesh//g", "D", null, null, 1, Connectivity.AGENT, 1, 7, emptyList())

    @Test fun `rights - the account's own, its user groups', the device's, and what the server removes`() {
        val ana = UserInfo("user//ana", "ana", 0, links = mapOf("ugrp//tec" to 0L))
        val r = MeshRights.ofDevice(ana, server, group, device)
        assertTrue(MeshRights.has(r, MeshRights.REMOTE_COMMAND) && MeshRights.has(r, MeshRights.RESET_OFF))
        assertFalse(MeshRights.has(r, MeshRights.UNINSTALL))
        val own = device.copy(links = mapOf("user//ana" to MeshRights.UNINSTALL))
        assertTrue(MeshRights.has(MeshRights.ofDevice(ana, server, group, own), MeshRights.UNINSTALL))
        // removeRights 0x20000 takes away running commands, even from a full administrator of the group.
        val limited = ana.copy(removeRights = 0x20000L)
        assertFalse(MeshRights.has(MeshRights.ofDevice(limited, server, group, device), MeshRights.REMOTE_COMMAND))
        val fullGroup = group.copy(links = mapOf("user//ana" to MeshRights.FULL))
        assertFalse(MeshRights.has(MeshRights.ofDevice(limited, server, fullGroup, device), MeshRights.REMOTE_COMMAND))
        assertTrue(MeshRights.has(MeshRights.ofDevice(limited, server, fullGroup, device), MeshRights.RESET_OFF))
        // A server where this account manages every group. / Un servidor donde esta cuenta gestiona todos los grupos.
        val everyGroup = UserInfo("user//otro", "otro", 0)
        assertEquals(0L, MeshRights.ofDevice(everyGroup, server, group, device))
        assertEquals(MeshRights.FULL, MeshRights.ofDevice(everyGroup, server.copy(manageAllDeviceGroups = true), group, device))
    }

    @Test fun `full rights carry the no-desktop bits, and they count only below full, as on the website`() {
        assertTrue(MeshRights.tool(MeshRights.FULL, MeshRights.NO_DESKTOP))
        assertTrue(MeshRights.tool(MeshRights.FULL, MeshRights.NO_FILES))
        val limited = MeshRights.REMOTE_CONTROL or MeshRights.NO_DESKTOP
        assertFalse(MeshRights.tool(limited, MeshRights.NO_DESKTOP))
        assertTrue(MeshRights.tool(limited, MeshRights.NO_TERMINAL))
        assertFalse(MeshRights.tool(MeshRights.WAKE, MeshRights.NO_TERMINAL))
    }

    @Test fun `abilities follow the agent - offline only wake, online no wake`() {
        val off = device.copy(connectivity = 0)
        val a = DeviceAbilities.of(MeshRights.FULL, off)
        assertTrue(a.wake && a.edit && a.remove)
        assertFalse(a.power || a.runCommands || a.notify || a.messageBox || a.uninstall)
        val on = DeviceAbilities.of(MeshRights.FULL, device)
        assertFalse(on.wake)
        assertTrue(on.power && on.runCommands && on.notify && on.messageBox && on.uninstall)
        assertEquals(DeviceAbilities.NONE.copy(), DeviceAbilities.of(0, off))
    }
}
