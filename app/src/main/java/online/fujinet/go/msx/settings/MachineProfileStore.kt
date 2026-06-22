package online.fujinet.go.msx.settings

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Persists the user's machine [MachineProfile]s and which one is active, as a
 * properties file under <files>/openmsx/profiles.properties. The active profile's
 * [MachineProfile.machineId] is what RuntimeInstaller writes to machine.id and
 * session_runtime boots.
 */
class MachineProfileStore(context: Context) {

    private val file = File(File(context.filesDir, "openmsx").apply { mkdirs() }, "profiles.properties")

    fun load(): List<MachineProfile> {
        if (!file.exists()) return listOf(defaultProfile())
        val props = Properties().apply { file.inputStream().use { load(it) } }
        val ids = props.getProperty(KEY_IDS).orEmpty().split(",").filter { it.isNotBlank() }
        val profiles = ids.mapNotNull { id ->
            val name = props.getProperty("profile.$id.name") ?: return@mapNotNull null
            val system = props.getProperty("profile.$id.system")
                ?.let { runCatching { MsxSystemType.valueOf(it) }.getOrNull() } ?: return@mapNotNull null
            val useImported = props.getProperty("profile.$id.useImportedRoms")?.toBoolean() ?: false
            MachineProfile(id, name, system, useImported)
        }
        return profiles.ifEmpty { listOf(defaultProfile()) }
    }

    fun activeProfile(): MachineProfile {
        val profiles = load()
        if (!file.exists()) return profiles.first()
        val props = Properties().apply { file.inputStream().use { load(it) } }
        val activeId = props.getProperty(KEY_ACTIVE)
        return profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    }

    /** The openMSX machine id the active profile boots. */
    fun activeMachineId(): String = activeProfile().machineId

    fun save(profiles: List<MachineProfile>, activeId: String) {
        val props = Properties()
        props.setProperty(KEY_IDS, profiles.joinToString(",") { it.id })
        props.setProperty(KEY_ACTIVE, activeId)
        for (p in profiles) {
            props.setProperty("profile.${p.id}.name", p.name)
            props.setProperty("profile.${p.id}.system", p.systemType.name)
            props.setProperty("profile.${p.id}.useImportedRoms", p.useImportedRoms.toString())
        }
        file.outputStream().use { props.store(it, "FujiNet Go MSX machine profiles") }
    }

    fun setActive(id: String) = save(load(), id)

    fun upsert(profile: MachineProfile, makeActive: Boolean = true) {
        val updated = load().filter { it.id != profile.id } + profile
        val activeId = if (makeActive) profile.id else activeProfile().id
        save(updated, activeId)
    }

    fun delete(id: String) {
        val remaining = load().filter { it.id != id }.ifEmpty { listOf(defaultProfile()) }
        val active = activeProfile().id.takeIf { it != id } ?: remaining.first().id
        save(remaining, active)
    }

    private companion object {
        const val KEY_IDS = "profiles"
        const val KEY_ACTIVE = "active"
    }
}
