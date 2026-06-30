package online.fujinet.go.msx

import android.content.Context
import android.content.res.AssetManager
import java.io.File

/**
 * Stages the bundled runtime trees from APK assets into a writable runtime
 * directory the native layer can read/mutate:
 *
 *   assets/fujinet/{fnconfig.ini, data/, SD/}    -> <files>/fujinet  (build-fujinet.sh)
 *   assets/openmsx/{machines/, extensions/, ...} -> <files>/openmsx  (build-openmsx-core.sh)
 *
 * The openMSX share tree carries the C-BIOS system ROMs + machine configs and
 * the FujiNet extension (FujiNet.xml + fujinet-config.rom). The selected machine
 * id is written to <files>/fujinet/openmsx/machine.id (under the FujiNet runtime
 * root that native receives), which session_runtime reads before booting the core
 * -- the Settings layer rewrites it when the user changes the system type /
 * profile. In Phase 1 the openmsx share assets are absent, so only the default
 * machine.id is written.
 */
class RuntimeInstaller(private val context: Context) {

    data class Paths(
        val runtimeRoot: String,
        val configPath: String,
        val sdPath: String,
        val dataPath: String,
        val openMsxRoot: String,
    )

    fun install(force: Boolean = false): Paths {
        val fujinetRoot = File(context.filesDir, "fujinet")
        if (force || !File(fujinetRoot, "fnconfig.ini").exists()) {
            copyAssetDir("fujinet", fujinetRoot)
        }

        val openMsxRoot = File(context.filesDir, "openmsx")
        // Re-stage the openMSX tree (C-BIOS ROMs, machine configs, the FujiNet
        // extension + fujinet-config.rom) whenever the app was (re)installed -- not
        // only when init.tcl is missing -- so an updated bundled asset (e.g. a new
        // fujinet-config.rom) actually takes effect on `install -r`, which preserves
        // <files>. We key off the package's install/update time: it changes on every
        // (re)install but not on a plain app restart, so normal launches stay fast.
        // The fujinet tree above stays gated on fnconfig.ini, so the user's hosts /
        // config / imported SD media survive an app update.
        val stampFile = File(openMsxRoot, ".staged-install")
        val installStamp = installStamp()
        val openMsxStale = force ||
            !File(openMsxRoot, "init.tcl").exists() ||
            runCatching { stampFile.readText() }.getOrNull() != installStamp
        if (openMsxStale) {
            copyAssetDir("openmsx", openMsxRoot)
            runCatching { stampFile.writeText(installStamp) }
        }
        // The session reads the chosen machine from <fujinet>/openmsx/machine.id;
        // default until the Settings layer writes the selected system type.
        if (!machineIdFile(fujinetRoot).exists()) writeMachineId(DEFAULT_MACHINE)

        return Paths(
            runtimeRoot = fujinetRoot.absolutePath,
            configPath = File(fujinetRoot, "fnconfig.ini").absolutePath,
            sdPath = File(fujinetRoot, "SD").absolutePath,
            dataPath = File(fujinetRoot, "data").absolutePath,
            openMsxRoot = openMsxRoot.absolutePath,
        )
    }

    /**
     * Persist the openMSX machine id session_runtime boots, under the FujiNet
     * runtime root (<files>/fujinet/openmsx/machine.id) that native receives.
     */
    fun writeMachineId(machineId: String) {
        machineIdFile(File(context.filesDir, "fujinet")).apply {
            parentFile?.mkdirs()
            writeText(machineId.trim())
        }
    }

    private fun machineIdFile(fujinetRoot: File) = File(File(fujinetRoot, "openmsx"), "machine.id")

    /** Identifies the current install: changes on every (re)install, not on relaunch. */
    private fun installStamp(): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrDefault("0")

    private fun copyAssetDir(assetPath: String, dest: File) {
        val assets: AssetManager = context.assets
        val entries = try {
            assets.list(assetPath) ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }
        if (entries.isEmpty()) {
            // Either a file, or an absent asset dir (Phase 1 openmsx). Try as a file.
            try {
                dest.parentFile?.mkdirs()
                assets.open(assetPath).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                // Absent asset (e.g. openmsx not yet staged); skip silently.
            }
            return
        }
        dest.mkdirs()
        for (entry in entries) {
            copyAssetDir("$assetPath/$entry", File(dest, entry))
        }
    }

    private companion object {
        const val DEFAULT_MACHINE = "C-BIOS_MSX2+"
    }
}
