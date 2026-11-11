package io.github.nebur692.meshclient.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull

/** A memory module. / Un módulo de memoria. */
data class MemoryModule(val bytes: Long, val type: String?, val speed: String?, val maker: String?)

/** A physical disk; Linux agents may send its size as text. / Un disco físico; los de Linux pueden mandar el tamaño como texto. */
data class DiskInfo(val name: String, val bytes: Long?, val sizeText: String?)

/**
 * A mounted volume or drive letter, with BitLocker where Windows reports it: [encryption] null when the agent said
 * nothing, otherwise MeshCentral's own words ("Fully Encrypted", "Locked"…), as its website shows them.
 * Un volumen o letra de unidad, con BitLocker donde Windows lo cuenta: [encryption] null si el agente no dijo nada.
 */
data class VolumeInfo(val name: String, val label: String?, val fileSystem: String?, val bytes: Long, val freeBytes: Long, val encryption: Encryption? = null) {
    val usedFraction: Float get() = if (bytes <= 0) 0f else ((bytes - freeBytes).coerceAtLeast(0).toFloat() / bytes)
}

enum class Encryption { Encrypted, Encrypting, Decrypting, Paused, Locked, NotEncrypted }

/**
 * What a person looks for in a device's sheet, out of the agent's inventory ("getsysinfo"), whose shape changes with the
 * system. The shapes are the ones the Windows app measured on real agents (MeshCentral 1.3.1): Windows,
 * windows.memory[].Capacity in bytes as text and windows.volumes by letter in bytes; Linux, linux.memory.Memory_Device[].Size
 * such as "16 GiB" and linux.volumes[] in KiB; both, identifiers.storage_devices. Any of them may be missing.
 * Lo que una persona busca en la ficha, sacado del inventario del agente, cuya forma cambia con el sistema. Las formas
 * son las que midió la app de Windows en agentes reales. Cualquiera puede faltar.
 */
data class HardwareSummary(
    val cpu: String?,
    val memory: List<MemoryModule>,
    val disks: List<DiskInfo>,
    val volumes: List<VolumeInfo>,
    val graphics: List<String>,
    val model: String?,
    val maker: String?,
    val serial: String?,
    val bios: String?,
    val board: String?,
) {
    val totalMemory: Long get() = memory.sumOf { it.bytes }

    companion object {
        // What a manufacturer leaves when it fills nothing in. / Lo que deja un fabricante cuando no rellena nada.
        private val placeholder = Regex("^(default string|to be filled.*|system (serial number|product name|manufacturer)|none|n/?a|0+|not specified|unknown|o\\.e\\.m\\.?)$", RegexOption.IGNORE_CASE)
        private val notDisks = setOf("overlay", "tmpfs", "devtmpfs", "squashfs", "rootfs", "efivarfs", "ramfs", "autofs", "fuse.snapfuse")
        private val notDiskMounts = listOf("/var/lib/docker/", "/snap/", "/run/", "/dev/", "/sys/", "/proc/", "/boot/efi")

        internal fun clean(v: String?): String? = v?.trim()?.takeIf { it.isNotEmpty() && !placeholder.matches(it) }

        private fun number(e: JsonElement?): Long? = (e as? JsonPrimitive)?.let { p ->
            p.longOrNull ?: p.doubleOrNull?.toLong() ?: if (p.isString) p.content.trim().toLongOrNull() else null
        }

        fun from(hardware: JsonObject): HardwareSummary {
            val ids = hardware["identifiers"] as? JsonObject
            val windows = hardware["windows"] as? JsonObject
            val linux = hardware["linux"] as? JsonObject
            return HardwareSummary(
                cpu = clean(ids?.str("cpu_name")) ?: (windows?.get("cpu") as? JsonArray)?.filterIsInstance<JsonObject>()?.firstNotNullOfOrNull { clean(it.str("Name")) },
                memory = memory(windows, linux),
                disks = (ids?.get("storage_devices") as? JsonArray)?.filterIsInstance<JsonObject>()?.mapNotNull(::disk).orEmpty(),
                volumes = volumes(windows, linux),
                graphics = when (val g = ids?.get("gpu_name")) {
                    is JsonArray -> g.mapNotNull { (it as? JsonPrimitive)?.content }
                    is JsonPrimitive -> listOf(g.content)
                    else -> (windows?.get("gpu") as? JsonArray)?.filterIsInstance<JsonObject>()?.mapNotNull { it.str("Name") }.orEmpty()
                }.mapNotNull(::clean).distinct(),
                model = clean(ids?.str("product_name")),
                maker = clean(ids?.str("board_vendor")) ?: clean(ids?.str("bios_vendor")),
                serial = clean(ids?.str("bios_serial")) ?: clean(linux?.str("product_serial")),
                bios = listOfNotNull(clean(ids?.str("bios_vendor")), clean(ids?.str("bios_version")), clean(ids?.str("bios_date"))?.take(10))
                    .joinToString(" · ").ifEmpty { null },
                board = listOfNotNull(clean(ids?.str("board_vendor")), clean(ids?.str("board_name"))).joinToString(" ").ifEmpty { null },
            )
        }

        private fun memory(windows: JsonObject?, linux: JsonObject?): List<MemoryModule> {
            val list = mutableListOf<MemoryModule>()
            (windows?.get("memory") as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach { m ->
                val bytes = number(m["Capacity"]) ?: return@forEach
                if (bytes <= 0) return@forEach
                val mhz = number(m["ConfiguredClockSpeed"] ?: m["Speed"])
                list += MemoryModule(bytes, smbiosType(number(m["SMBIOSMemoryType"]) ?: 0), mhz?.takeIf { it > 0 }?.let { "$it MHz" }, clean(m.str("Manufacturer")))
            }
            ((linux?.get("memory") as? JsonObject)?.get("Memory_Device") as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach { m ->
                val bytes = linuxSize(m.str("Size")) ?: return@forEach
                list += MemoryModule(bytes, m.str("Type")?.takeIf { it !in setOf("RAM", "Unknown", "Other") }, clean(m.str("Speed")), clean(m.str("Manufacturer")))
            }
            return list
        }

        /** The SMBIOS memory types still in use. / Los tipos de memoria SMBIOS aún en uso. */
        private fun smbiosType(t: Long) = when (t) {
            20L -> "DDR"; 21L -> "DDR2"; 24L -> "DDR3"; 26L -> "DDR4"; 27L -> "LPDDR"; 28L -> "LPDDR2"; 29L -> "LPDDR3"
            30L -> "LPDDR4"; 34L -> "DDR5"; 35L -> "LPDDR5"; else -> null
        }

        /** dmidecode's "8 GB" means GiB, as "16 GiB" does; "No Module Installed" is an empty slot. / El «8 GB» de dmidecode es GiB. */
        internal fun linuxSize(size: String?): Long? {
            val m = Regex("^\\s*(\\d+)\\s*([KMGT])i?B\\s*$", RegexOption.IGNORE_CASE).find(size.orEmpty()) ?: return null
            val unit = when (m.groupValues[2].uppercase()) { "K" -> 1L shl 10; "M" -> 1L shl 20; "G" -> 1L shl 30; else -> 1L shl 40 }
            return m.groupValues[1].toLong() * unit
        }

        private fun disk(d: JsonObject): DiskInfo? {
            val name = clean(d.str("Model")) ?: clean(d.str("Caption")) ?: "?"
            number(d["Size"])?.takeIf { it > 0 }?.let { return DiskInfo(name, it, null) }
            val text = d.str("Size")
            return if (!text.isNullOrEmpty() && !text.all(Char::isDigit)) DiskInfo(name, null, text) else null
        }

        /** MeshCentral's website: protectionStatus 2 is locked, otherwise volumeStatus (conversion status) says. / Como la web. */
        private fun encryption(v: JsonObject): Encryption? {
            if (v["protectionStatus"] == null && v["volumeStatus"] == null) return null
            (v["volumeStatus"] as? JsonPrimitive)?.takeIf { it.isString }?.let { s ->
                val t = s.content.lowercase()
                return when {
                    "locked" in t -> Encryption.Locked
                    "fully decrypted" in t -> Encryption.NotEncrypted
                    "fully encrypted" in t -> Encryption.Encrypted
                    "encryption in progress" in t -> Encryption.Encrypting
                    "decryption in progress" in t -> Encryption.Decrypting
                    "paused" in t -> Encryption.Paused
                    else -> null
                }
            }
            if (number(v["protectionStatus"]) == 2L) return Encryption.Locked
            return when (number(v["volumeStatus"])) {
                1L -> Encryption.Encrypted; 2L -> Encryption.Encrypting; 3L -> Encryption.Decrypting
                4L, 5L -> Encryption.Paused; 0L -> Encryption.NotEncrypted; else -> null
            }
        }

        private fun volumes(windows: JsonObject?, linux: JsonObject?): List<VolumeInfo> {
            val list = mutableListOf<VolumeInfo>()
            val mounts = mutableListOf<VolumeInfo>()
            (windows?.get("volumes") as? JsonObject)?.forEach { (letter, node) ->
                val v = node as? JsonObject ?: return@forEach
                val size = number(v["size"])?.takeIf { it > 0 } ?: return@forEach
                list += VolumeInfo("$letter:", clean(v.str("name")), v.str("type"), size, number(v["sizeremaining"]) ?: 0, encryption(v))
            }
            (linux?.get("volumes") as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach { v ->
                val at = v.str("mount_point").orEmpty()
                if (at.isEmpty() || v.str("type").orEmpty() in notDisks || notDiskMounts.any { at.startsWith(it) || at == it.trimEnd('/') }) return@forEach
                val kib = number(v["size"])?.takeIf { it > 0 } ?: return@forEach
                val free = number(v["available"]) ?: 0
                // A read-only image (a snap, the kernel headers) is always exactly full: never a disk to watch.
                // Una imagen de solo lectura siempre está justo llena: no es un disco que vigilar.
                if (free == 0L && number(v["used"]) == kib) return@forEach
                mounts += VolumeInfo(at, null, v.str("type"), kib * 1024, free * 1024)
            }
            // The same file system mounted twice (a bind mount) shows the same figures: once, by its shortest path.
            // El mismo sistema de ficheros montado dos veces da las mismas cifras: una vez, por su ruta más corta.
            val keep = mounts.groupBy { Triple(it.fileSystem, it.bytes, it.freeBytes) }.values.map { same -> same.minBy { it.name.length } }.toSet()
            list += mounts.filter { it in keep }
            return list
        }
    }
}

/** One network interface of a device (getnetworkinfo "netif2"). / Una interfaz de red de un equipo. */
data class NetworkInterface(val name: String, val mac: String?, val status: String?, val speedBitsPerSecond: Long?, val addresses: List<NetworkAddress>) {
    val isUp: Boolean get() = status.equals("up", ignoreCase = true)

    companion object {
        private val loopback = Regex("^(127\\.|::1$)")

        /** getnetworkinfo's netif2, without the loopback interface. / netif2 de getnetworkinfo, sin la interfaz de loopback. */
        internal fun from(netif2: JsonObject): List<NetworkInterface> = netif2.mapNotNull { (name, entries) ->
            val e = (entries as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
            // Only the loopback interface carries 127.x or ::1 (on WSL it also carries 10.255.255.254).
            // Solo la de loopback lleva 127.x o ::1 (en la WSL lleva además 10.255.255.254).
            if (e.any { it.str("type") == "loopback" || loopback.containsMatchIn(it.str("address").orEmpty()) }) return@mapNotNull null
            NetworkInterface(
                name,
                e.firstNotNullOfOrNull { x -> x.str("mac")?.takeIf { it.isNotEmpty() && it != "00:00:00:00:00:00" } },
                e.firstNotNullOfOrNull { it.str("status") },
                e.firstNotNullOfOrNull { it.long("speed") },
                e.mapNotNull { x -> x.str("address")?.takeIf(String::isNotEmpty)?.let { NetworkAddress(it, x.str("family"), x.str("netmask"), x.str("gateway")) } },
            )
        }
    }
}

data class NetworkAddress(val address: String, val family: String?, val netmask: String?, val gateway: String?)

/** An entry of a device's history. / Una entrada del historial de un equipo. */
data class DeviceEvent(val timeMs: Long?, val user: String?, val action: String?, val message: String)

/** When the agent last connected, and from which address (the public one, as the server saw it). / Cuándo y desde dónde. */
data class LastConnect(val timeMs: Long?, val address: String?)

/** Everything the sheet shows besides the device itself; each part may be missing. / Todo lo demás de la ficha; cada parte puede faltar. */
data class DeviceDetails(
    val hardware: HardwareSummary?,
    val operatingSystem: String?,
    val network: List<NetworkInterface>,
    val lastConnect: LastConnect?,
    val notes: String?,
    val events: List<DeviceEvent>,
)

internal object Os {
    /** The OS name and version the inventory carries, better than the agent's short one. / El sistema que trae el inventario. */
    fun describe(hardware: JsonObject): String? {
        val win = (hardware["windows"] as? JsonObject)?.get("osinfo") as? JsonObject
        if (win != null) {
            val caption = HardwareSummary.clean(win.str("Caption"))?.removePrefix("Microsoft ")
            val build = win.str("Version")
            val arch = HardwareSummary.clean(win.str("OSArchitecture"))
            return listOfNotNull(caption, build?.let { "($it)" }, arch).joinToString(" ").ifEmpty { null }
        }
        val linux = hardware["linux"] as? JsonObject
        return HardwareSummary.clean(linux?.str("os_name") ?: linux?.str("OS"))
    }
}
