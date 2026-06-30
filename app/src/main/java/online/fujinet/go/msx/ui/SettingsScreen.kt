package online.fujinet.go.msx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import online.fujinet.go.msx.settings.MsxSystemType
import online.fujinet.go.msx.settings.SystemTypeStore

/**
 * Settings: pick the MSX system type (MSX / MSX2 / MSX2+), which selects the
 * matching C-BIOS machine. Presented as a centered dialog over the running
 * emulator, like the other FujiNet Go targets (e.g. apple2). "Apply & Restart"
 * persists the selection and reboots the emulator so openMSX comes up on the
 * chosen machine; "Close" discards an unapplied change.
 */
@Composable
fun SettingsScreen(
    onApplyRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { SystemTypeStore(context) }
    var systemType by remember { mutableStateOf(store.load()) }

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton(onClick = { store.save(systemType); onApplyRestart() }) { Text("Apply & Restart") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("FujiNet Go MSX — Settings") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("System type", style = MaterialTheme.typography.titleSmall)
                MsxSystemType.entries.forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = systemType == type, onClick = { systemType = type }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = systemType == type, onClick = { systemType = type })
                        Text(type.displayName)
                    }
                }
                Text(
                    "Boots machine: ${systemType.machineId}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}
