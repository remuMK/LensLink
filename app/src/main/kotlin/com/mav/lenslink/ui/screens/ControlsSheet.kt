/*
 * ControlsSheet.kt — M3 bottom sheet with the exposure-triangle controls:
 * shutter speed (incl. Bulb), aperture, ISO, white balance and picture style
 * — driven generically off the camlib config widgets. Any property the
 * camera exposes appears; unsupported ones are hidden automatically.
 */
package com.mav.lenslink.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mav.cameranativebridge.CameraConfig
import com.mav.lenslink.appContainer
import com.mav.lenslink.ui.theme.badgeShape

/** Property → display title. Canonical names come from the ptp2 camlib. */
private val PROPERTY_TITLES = mapOf(
    "iso" to "ISO",
    "aperture" to "Aperture",
    "shutterspeed" to "Shutter Speed",
    "exposurecompensation" to "Exposure Comp.",
    "whitebalance" to "White Balance",
    "picturestyle" to "Picture Style",
    "meteringmode" to "Metering Mode",
    "autoexposuremode" to "Shooting Mode",
    "drivemode" to "Drive Mode",
    "capturemode" to "Capture Mode",
    "imageformat" to "Image Format",
    "eosmoviesize" to "Movie Size",
    "flashmode" to "Flash",
    "focusmode" to "Focus",
)

const val BULB_CONFIG = "bulb"

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
)
@Composable
fun ControlsSheet(onDismiss: () -> Unit) {
    val repo = appContainer().repository
    val properties by repo.properties.collectAsStateWithLifecycle()

    // Refresh once when the sheet opens.
    LaunchedEffect(Unit) { repo.refreshControls() }

    var expandedName by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Camera Controls",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Read live from the PTP configuration tree — what the camera " +
                    "reports is what you can set.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(0.dp))

            BulbRow(properties[BULB_CONFIG])

            properties.entries
                .filter { it.key != BULB_CONFIG }
                .forEach { (name, config) ->
                    PropertyRow(
                        name = name,
                        config = config,
                        expanded = expandedName == name,
                        onToggle = {
                            expandedName = if (expandedName == name) null else name
                        },
                        onSelect = { repo.setProperty(name, it) },
                    )
                }

            if (properties.isEmpty()) {
                Text(
                    "No properties reported yet — is the camera busy?",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BulbRow(bulbConfig: CameraConfig?) {
    val repo = appContainer().repository
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Lightbulb,
                contentDescription = null,
                tint = if (bulbConfig?.current == "1") MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Bulb (time exposure)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Set Shutter Speed to “bulb”, then flip this switch to open " +
                        "and close the shutter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (bulbConfig != null && !bulbConfig.readonly) {
            Switch(
                checked = bulbConfig.current == "1",
                onCheckedChange = { on -> repo.setBulb(on) },
            )
        }
    }
}

@Composable
private fun PropertyRow(
    name: String,
    config: CameraConfig,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val title = PROPERTY_TITLES[name] ?: name
    Surface(
        shape = propertyShape(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (config.readonly) {
                        Text(
                            "read-only on the camera right now",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    config.current.ifBlank { "—" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (expanded && config.isChoiceType && config.choices.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    config.choices.forEach { choice ->
                        FilterChip(
                            selected = choice == config.current,
                            onClick = { onSelect(choice) },
                            label = { Text(choice) },
                        )
                    }
                }
            }
            if (expanded && config.isToggle) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    FilterChip(
                        selected = config.current.trim() != "0",
                        onClick = { onSelect("1") },
                        label = { Text("On") },
                    )
                    FilterChip(
                        selected = config.current.trim() == "0",
                        onClick = { onSelect("0") },
                        label = { Text("Off") },
                    )
                }
            }
            if (expanded && config.isText && !config.readonly) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { onSelect(config.current) },
                        label = { Text("Re-apply “${config.current}”") },
                    )
                }
            }
        }
    }
}

@Composable
private fun propertyShape() = badgeShape()
