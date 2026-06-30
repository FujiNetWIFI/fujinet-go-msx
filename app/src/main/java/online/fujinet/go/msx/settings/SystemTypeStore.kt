package online.fujinet.go.msx.settings

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * Persists the selected MSX [MsxSystemType] as a properties file under
 * <files>/openmsx/system.properties. The selected type's [MsxSystemType.machineId]
 * is what RuntimeInstaller writes to machine.id and session_runtime boots.
 */
class SystemTypeStore(context: Context) {

    private val file = File(File(context.filesDir, "openmsx").apply { mkdirs() }, "system.properties")

    fun load(): MsxSystemType {
        if (!file.exists()) return DEFAULT
        val props = Properties().apply { file.inputStream().use { load(it) } }
        return props.getProperty(KEY)
            ?.let { runCatching { MsxSystemType.valueOf(it) }.getOrNull() }
            ?: DEFAULT
    }

    fun save(type: MsxSystemType) {
        val props = Properties().apply { setProperty(KEY, type.name) }
        file.outputStream().use { props.store(it, "FujiNet Go MSX system type") }
    }

    /** The openMSX machine id the selected system type boots. */
    fun activeMachineId(): String = load().machineId

    private companion object {
        const val KEY = "systemType"
        val DEFAULT = MsxSystemType.MSX2
    }
}
