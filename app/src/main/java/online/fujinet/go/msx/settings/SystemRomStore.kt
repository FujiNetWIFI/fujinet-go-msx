package online.fujinet.go.msx.settings

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.Properties

data class SystemRomSelection(
    val kind: SystemRomKind,
    val importedPath: String,
    val displayName: String,
    val lastUpdatedEpochMillis: Long,
)

/**
 * Imports user-supplied MSX system ROMs (via the Storage Access Framework) into
 * <files>/openmsx/systemroms and records the per-kind selection in a properties
 * file, mirroring fujinet-go-800's SystemRomDocumentStore. openMSX resolves the
 * imported ROMs from the systemroms dir when a real-machine profile boots.
 */
class SystemRomStore(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val romsDir = File(File(context.filesDir, "openmsx"), "systemroms").apply { mkdirs() }
    private val selectionsFile = File(romsDir, "selections.properties")

    /** Copy the document at [uri] into systemroms and record it for [kind]. */
    fun importRom(kind: SystemRomKind, uri: Uri, displayName: String): SystemRomSelection {
        val dest = File(romsDir, "${kind.name.lowercase()}.rom")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not open $uri")
        val selection = SystemRomSelection(kind, dest.absolutePath, displayName, clock())
        save(load().toMutableMap().apply { put(kind, selection) })
        return selection
    }

    fun selection(kind: SystemRomKind): SystemRomSelection? = load()[kind]

    fun clear(kind: SystemRomKind) {
        File(romsDir, "${kind.name.lowercase()}.rom").delete()
        save(load().toMutableMap().apply { remove(kind) })
    }

    fun load(): Map<SystemRomKind, SystemRomSelection> {
        if (!selectionsFile.exists()) return emptyMap()
        val props = Properties().apply { selectionsFile.inputStream().use { load(it) } }
        return SystemRomKind.entries.mapNotNull { kind ->
            val prefix = "${kind.name.lowercase()}."
            val path = props.getProperty("$prefix$KEY_PATH") ?: return@mapNotNull null
            val name = props.getProperty("$prefix$KEY_NAME") ?: return@mapNotNull null
            val ts = props.getProperty("$prefix$KEY_TS")?.toLongOrNull() ?: return@mapNotNull null
            kind to SystemRomSelection(kind, path, name, ts)
        }.toMap()
    }

    private fun save(selections: Map<SystemRomKind, SystemRomSelection>) {
        val props = Properties()
        for ((kind, sel) in selections) {
            val prefix = "${kind.name.lowercase()}."
            props.setProperty("$prefix$KEY_PATH", sel.importedPath)
            props.setProperty("$prefix$KEY_NAME", sel.displayName)
            props.setProperty("$prefix$KEY_TS", sel.lastUpdatedEpochMillis.toString())
        }
        selectionsFile.outputStream().use { props.store(it, "FujiNet Go MSX imported system ROMs") }
    }

    private companion object {
        const val KEY_PATH = "path"
        const val KEY_NAME = "name"
        const val KEY_TS = "ts"
    }
}
