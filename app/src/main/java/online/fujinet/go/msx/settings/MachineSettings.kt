package online.fujinet.go.msx.settings

/**
 * The MSX system generations the app can boot. Each maps to a C-BIOS machine id
 * (the freely-redistributable default BIOS, bundled from openMSX's Contrib/cbios)
 * and to a real-machine openMSX config id used when a profile supplies imported
 * system ROMs. C-BIOS covers MSX1/MSX2/MSX2+; MSX turboR has no C-BIOS, so it
 * always uses a real-machine config and requires imported ROMs to boot.
 *
 * The MSX1/MSX2/MSX2+ "real machine" ids are synthesised at boot by
 * [ImportedMachineConfig] from whatever ROMs the user imported (see that class
 * for why a fixed Boosted_* config can't consume arbitrary imported ROMs).
 * turboR keeps a bundled real-machine config as its fallback.
 *
 * [openMsxType] is the <type> the generated config declares (MSX / MSX2 / MSX2+).
 */
enum class MsxSystemType(
    val displayName: String,
    val cbiosMachineId: String?,
    val realMachineId: String,
    val openMsxType: String,
) {
    MSX("MSX (MSX1)", "C-BIOS_MSX1", "Imported_MSX1", "MSX"),
    MSX2("MSX2", "C-BIOS_MSX2", "Imported_MSX2", "MSX2"),
    MSX2_PLUS("MSX2+", "C-BIOS_MSX2+", "Imported_MSX2P", "MSX2+"),
    TURBO_R("MSX turboR", null, "Boosted_MSXturboR_with_IDE", "MSXturboR"),
}

/** Kinds of system ROM a profile can import (mapped to openMSX machine ROM slots). */
enum class SystemRomKind(val displayName: String) {
    MAIN_BIOS("Main BIOS"),
    SUB_ROM("Sub ROM (MSX2+)"),
    DISK_ROM("Disk ROM"),
    MSX_MUSIC("MSX-Music (FM-PAC)"),
    TURBO_R_DOS("turboR DOS/firmware"),
}

/**
 * A named machine configuration the user can switch between: a system type, and
 * whether to boot real imported ROMs (a real-machine openMSX config) or C-BIOS.
 */
data class MachineProfile(
    val id: String,
    val name: String,
    val systemType: MsxSystemType,
    val useImportedRoms: Boolean = false,
) {
    /** The openMSX machine id this profile boots. */
    val machineId: String
        get() = if (useImportedRoms) systemType.realMachineId
                else (systemType.cbiosMachineId ?: systemType.realMachineId)
}

/** The default profile present on a fresh install: C-BIOS MSX2. */
fun defaultProfile(): MachineProfile = MachineProfile(
    id = "default",
    name = "C-BIOS MSX2",
    systemType = MsxSystemType.MSX2,
    useImportedRoms = false,
)
