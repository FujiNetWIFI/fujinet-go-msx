package online.fujinet.go.msx.settings

/**
 * The MSX system generations the app can boot. Each maps to a C-BIOS machine id
 * (the freely-redistributable default BIOS, bundled from openMSX's Contrib/cbios),
 * which covers MSX1/MSX2/MSX2+. MSX turboR is intentionally absent: it has no
 * C-BIOS and can only boot from custom system ROMs, which the app does not ship.
 */
enum class MsxSystemType(
    val displayName: String,
    val machineId: String,
) {
    MSX("MSX (MSX1)", "C-BIOS_MSX1"),
    MSX2("MSX2", "C-BIOS_MSX2"),
    MSX2_PLUS("MSX2+", "C-BIOS_MSX2+"),
}
