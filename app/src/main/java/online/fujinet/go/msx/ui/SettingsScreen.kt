package online.fujinet.go.msx.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import online.fujinet.go.msx.settings.MachineProfile
import online.fujinet.go.msx.settings.MachineProfileStore
import online.fujinet.go.msx.settings.MsxSystemType
import online.fujinet.go.msx.settings.SystemRomKind
import online.fujinet.go.msx.settings.SystemRomStore

/**
 * Settings: pick the MSX system type (MSX / MSX2 / MSX2+ / turboR), switch
 * between named machine profiles, toggle booting imported system ROMs, and import
 * those ROMs from device storage. "Apply & restart" writes the active profile's
 * machine id and reboots the emulator. Mirrors the configuration model of the
 * other FujiNet Go targets (fujinet-go-800's system-ROM + profile screens).
 */
@Composable
fun SettingsScreen(
    onApplyRestart: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val profileStore = remember { MachineProfileStore(context) }
    val romStore = remember { SystemRomStore(context) }

    var profiles by remember { mutableStateOf(profileStore.load()) }
    var activeId by remember { mutableStateOf(profileStore.activeProfile().id) }
    var roms by remember { mutableStateOf(romStore.load()) }
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.first()

    fun persist(updated: List<MachineProfile>, newActive: String) {
        profiles = updated
        activeId = newActive
        profileStore.save(updated, newActive)
    }

    fun updateActive(transform: (MachineProfile) -> MachineProfile) {
        val updated = profiles.map { if (it.id == activeId) transform(it) else it }
        persist(updated, activeId)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("FujiNet Go MSX — Settings", style = MaterialTheme.typography.titleLarge)

        SectionHeader("Machine profile")
        profiles.forEach { profile ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = profile.id == activeId, onClick = { persist(profiles, profile.id) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = profile.id == activeId, onClick = { persist(profiles, profile.id) })
                Text("${profile.name}  (${profile.systemType.displayName})")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val id = "profile-" + System.currentTimeMillis()
                val created = MachineProfile(id, "New profile", active.systemType, active.useImportedRoms)
                persist(profiles + created, id)
            }) { Text("Add") }
            OutlinedButton(
                enabled = profiles.size > 1,
                onClick = {
                    val remaining = profiles.filter { it.id != activeId }
                    persist(remaining, remaining.first().id)
                },
            ) { Text("Delete") }
        }

        HorizontalDivider()
        SectionHeader("System type")
        MsxSystemType.entries.forEach { type ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = active.systemType == type, onClick = { updateActive { it.copy(systemType = type) } }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = active.systemType == type, onClick = { updateActive { it.copy(systemType = type) } })
                Column {
                    Text(type.displayName)
                    Text(
                        if (type.cbiosMachineId != null) "C-BIOS available" else "requires imported ROMs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = active.useImportedRoms, onCheckedChange = { on -> updateActive { it.copy(useImportedRoms = on) } })
            Text("  Boot imported system ROMs (instead of C-BIOS)")
        }
        Text(
            "Boots machine: ${active.machineId}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        HorizontalDivider()
        SectionHeader("System ROMs (imported)")
        SystemRomKind.entries.forEach { kind ->
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri != null) {
                    val name = queryName(context, uri) ?: "${kind.name.lowercase()}.rom"
                    romStore.importRom(kind, uri, name)
                    roms = romStore.load()
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(kind.displayName)
                    Text(
                        roms[kind]?.displayName ?: "not set",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import") }
                if (roms[kind] != null) {
                    OutlinedButton(onClick = { romStore.clear(kind); roms = romStore.load() }) { Text("Clear") }
                }
            }
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onApplyRestart, modifier = Modifier.weight(1f)) { Text("Apply & Restart") }
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

private fun queryName(context: android.content.Context, uri: Uri): String? =
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
    }
