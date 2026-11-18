package dev.nemesis.drive.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nemesis.drive.model.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(state: DriveUiState, modifier: Modifier, runAction: (DriveAction) -> Unit, setDuration: (Int) -> Unit,
    refresh: () -> Unit, resolvePreviousRequest: (String, Boolean) -> Unit = { _, _ -> }, openMap: () -> Unit = {}) {
    var more by remember(state.selectedVehicleId) { mutableStateOf(false) }
    val vehicle = state.selectedVehicle
    val snapshot = vehicle?.snapshot
    val controlsEnabled = !state.busy && !state.previousRequestUnresolved && (!state.refreshing || snapshot?.updatedAtMillis != null)
    val isActionQueued: (DriveAction) -> Boolean = { act ->
        act in state.queuedActions || (state.busy && state.operation.action == act)
    }
    val haptics = LocalHapticFeedback.current
    val act: (DriveAction) -> Unit = { action ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        runAction(action)
    }
    Column(modifier.fillMaxWidth().testTag("home_scroll").verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        .padding(top = 10.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (snapshot == null) {
            Text(if (state.isDemo) "Choose a demo vehicle to begin." else "Choose an owned vehicle to begin.", style = MaterialTheme.typography.titleMedium)
            return@Column
        }
        if (!state.isDemo && state.refreshing && snapshot.updatedAtMillis == null && snapshot.batteryPercent == null) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().testTag("snapshot_loading")) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Loading vehicle data…")
                }
            }
        } else SnapshotInstrument(snapshot, state.isDemo)
        InlineError(state.error)
        if (!state.isDemo && state.previousRequestUnresolved)
            PendingRequestReview(requireNotNull(vehicle).id, state.busy || state.refreshing, resolvePreviousRequest)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(DriveAction.LOCK, Icons.Outlined.Lock, controlsEnabled && !isActionQueued(DriveAction.LOCK), Modifier.weight(1f), true, act, isActionQueued(DriveAction.LOCK))
            ActionButton(DriveAction.UNLOCK, Icons.Outlined.LockOpen, controlsEnabled && !isActionQueued(DriveAction.UNLOCK), Modifier.weight(1f), false, act, isActionQueued(DriveAction.UNLOCK))
        }
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.AcUnit, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Climate", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text((if (state.isDemo) "Demo · " else "") + booleanLabel(snapshot.acOn, "On", "Off"), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Duration", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { setDuration((state.acDurationMinutes - 1).coerceAtLeast(5)) },
                        enabled = controlsEnabled && state.acDurationMinutes > 5,
                        modifier = Modifier.size(48.dp).testTag("ac_duration_decrease")) {
                        Icon(Icons.Outlined.Remove, "Decrease climate duration")
                    }
                    Text("${state.acDurationMinutes} min", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.widthIn(min = 50.dp).semantics { contentDescription = "Climate duration ${state.acDurationMinutes} minutes" })
                    IconButton(onClick = { setDuration((state.acDurationMinutes + 1).coerceAtMost(20)) },
                        enabled = controlsEnabled && state.acDurationMinutes < 20,
                        modifier = Modifier.size(48.dp).testTag("ac_duration_increase")) {
                        Icon(Icons.Outlined.Add, "Increase climate duration")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(DriveAction.AC_ON, Icons.Outlined.PlayArrow, controlsEnabled && !isActionQueued(DriveAction.AC_ON), Modifier.weight(1f), snapshot.acOn != true, act, isActionQueued(DriveAction.AC_ON))
                    ActionButton(DriveAction.AC_OFF, Icons.Outlined.Stop, controlsEnabled && !isActionQueued(DriveAction.AC_OFF), Modifier.weight(1f), snapshot.acOn == true, act, isActionQueued(DriveAction.AC_OFF))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CompactAction(DriveAction.HORN, Icons.Outlined.VolumeUp, controlsEnabled && !isActionQueued(DriveAction.HORN), Modifier.weight(1f), act, isActionQueued(DriveAction.HORN))
            val approachActive = snapshot.approachOn == true
            val approachAction = if (approachActive) DriveAction.APPROACH_OFF else DriveAction.APPROACH_ON
            CompactAction(approachAction, Icons.Outlined.Lightbulb, controlsEnabled && !isActionQueued(approachAction), Modifier.weight(1f), act, isActionQueued(approachAction))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                FilledTonalIconButton(onClick = { more = true }, modifier = Modifier.size(48.dp).testTag("more_controls")) {
                    Icon(Icons.Outlined.MoreHoriz, "More vehicle controls")
                }
                Text("More", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 5.dp))
            }
        }

        // 2x2 Clean Quick Metrics Grid
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                icon = Icons.Outlined.Speed,
                label = "Odometer",
                value = snapshot.odometerKm?.let { String.format(Locale.getDefault(), "%,.1f km", it) } ?: "—",
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                icon = Icons.Outlined.EvStation,
                label = "Charging",
                value = when (snapshot.charging) {
                    true -> "Charging"
                    false -> if (snapshot.cableConnected == true) "Plugged in" else "Disconnected"
                    null -> "Standby"
                },
                modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                icon = Icons.Outlined.MeetingRoom,
                label = "Doors",
                value = booleanLabel(snapshot.doorsClosed, "Closed", "Open"),
                modifier = Modifier.weight(1f)
            )
            MetricTile(
                icon = Icons.Outlined.PowerSettingsNew,
                label = "Ignition",
                value = when (snapshot.ignitionOn) {
                    true -> "Running"
                    false -> "Parked"
                    null -> "Unknown"
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Location Preview Card
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().clickable { openMap() }
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                FilledTonalIconButton(onClick = openMap, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.LocationOn, "Open Map", tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Vehicle Location", style = MaterialTheme.typography.titleSmall)
                    val lat = snapshot.latitude
                    val lon = snapshot.longitude
                    Text(
                        if (lat != null && lon != null) String.format(Locale.getDefault(), "%.4f° N, %.4f° E · View on Map", lat, lon)
                        else "Tap to view map & location",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Outlined.ChevronRight, "Navigate", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        DetailGroup("Access & safety", listOf(
            "Locks" to booleanLabel(snapshot.locked, "Locked", "Unlocked"),
            "Doors" to booleanLabel(snapshot.doorsClosed, "Closed", "Open"),
            "Ignition" to booleanLabel(snapshot.ignitionOn, "On", "Off")
        ))
        DetailGroup(if (state.isDemo) "Simulated controls" else "Reported control states", listOf(
            "Climate" to booleanLabel(snapshot.acOn, "On", "Off"),
            "Approach" to booleanLabel(snapshot.approachOn, "On", "Off"),
            "Hazard lights" to booleanLabel(snapshot.hazardOn, "On", "Off"),
            "Position lamps" to booleanLabel(snapshot.positionOn, "On", "Off")
        ))
    }
    if (more) {
        ModalBottomSheet(onDismissRequest = { more = false }, containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Lights & visibility", style = MaterialTheme.typography.headlineMedium)
                Text((if (state.isDemo) "Demo controls for " else "Commands act on ") + (vehicle?.nickname ?: "this vehicle"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Hazard lights · ${booleanLabel(snapshot?.hazardOn, "On", "Off")}", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton(DriveAction.HAZARD_ON, Icons.Outlined.WarningAmber, controlsEnabled, Modifier.weight(1f), true, act)
                    ActionButton(DriveAction.HAZARD_OFF, Icons.Outlined.WarningAmber, controlsEnabled, Modifier.weight(1f), false, act)
                }
                Text("Position lamps · ${booleanLabel(snapshot?.positionOn, "On", "Off")}", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton(DriveAction.POSITION_ON, Icons.Outlined.Lightbulb, controlsEnabled, Modifier.weight(1f), true, act)
                    ActionButton(DriveAction.POSITION_OFF, Icons.Outlined.Lightbulb, controlsEnabled, Modifier.weight(1f), false, act)
                }
                if (state.isDemo) {
                    Text("Demo mode · not connected to your car", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                // The underlying inline result is obscured by the sheet, so expose its state here too.
                if (state.operation.phase != OperationPhase.IDLE) {
                    Text(operationTitle(state.operation.phase, state.isDemo),
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (!state.isDemo && state.operation.phase == OperationPhase.SUCCESS) "Reported by vehicle service" else state.operation.message, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PendingRequestReview(vehicleId: String, busy: Boolean, resolve: (String, Boolean) -> Unit) {
    var review by remember(vehicleId) { mutableStateOf(false) }
    var acknowledged by remember(vehicleId) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Previous request needs review", style = MaterialTheme.typography.titleSmall)
            Text("Check the vehicle before sending another command.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { acknowledged = false; review = true }, enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("previous_request_review")) { Text("Review") }
        }
    }
    if (review) ModalBottomSheet(onDismissRequest = { review = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Resolve previous request", style = MaterialTheme.typography.headlineSmall)
            Text("A result wasn't received. Check the vehicle and make sure the earlier request is no longer pending. Marking it resolved will not send or retry a command.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it }, enabled = !busy,
                    modifier = Modifier.testTag("previous_request_ack"))
                Text("I checked the vehicle and this request is resolved.", modifier = Modifier.weight(1f))
            }
            Button(enabled = acknowledged && !busy,
                onClick = { resolve(vehicleId, true); review = false; acknowledged = false },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("resolve_previous_request")) { Text("Mark resolved") }
            TextButton(onClick = { review = false; acknowledged = false },
                modifier = Modifier.heightIn(min = 48.dp).testTag("dismiss_previous_request")) { Text("Not now") }
        }
    }
}

@Composable
private fun SnapshotInstrument(snapshot: VehicleSnapshot, isDemo: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (isDemo) "DEMO SNAPSHOT" else "LAST REPORTED", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(booleanLabel(snapshot.locked, "Locked", "Unlocked"), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(snapshot.batteryPercent?.toString() ?: "—", fontSize = 44.sp, lineHeight = 48.sp,
                            fontWeight = FontWeight.Medium, letterSpacing = (-1.5).sp)
                        Text(" %", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp))
                    }
                    Text(snapshot.rangeKm?.let { "$it km estimated range" } ?: "Range unknown",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.DirectionsCar, if (isDemo) "Sample vehicle illustration" else "Vehicle illustration", Modifier.size(82.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
            snapshot.batteryPercent?.let { percent ->
                LinearProgressIndicator(progress = percent.coerceIn(0, 100) / 100f,
                    modifier = Modifier.fillMaxWidth().height(4.dp), color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant)
            }
            val updated = snapshot.updatedAtMillis
            if (!isDemo) {
                if (updated != null) {
                    Text("Vehicle update · ${formatDateTime(updated)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Connecting to vehicle…",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val chargingLabel = when (snapshot.charging) {
                    true -> "Charging"
                    false -> "Not charging"
                    null -> if (updated != null) "Disconnected" else "Connecting…"
                }
                Text(chargingLabel, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isDemo && updated != null) {
                    Text("Cached · ${formatTime(updated)}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ActionButton(action: DriveAction, icon: ImageVector, enabled: Boolean, modifier: Modifier,
    prominent: Boolean, onAction: (DriveAction) -> Unit, isQueued: Boolean = false) {
    val colors = if (prominent) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
    Button(onClick = { onAction(action) }, enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp).testTag("action_${action.name.lowercase()}"),
        shape = RoundedCornerShape(14.dp), colors = colors,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) {
        if (isQueued) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Queued…")
        } else {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(action.title)
        }
    }
}

@Composable
private fun CompactAction(action: DriveAction, icon: ImageVector, enabled: Boolean, modifier: Modifier, onAction: (DriveAction) -> Unit, isQueued: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = { onAction(action) }, enabled = enabled,
            modifier = Modifier.size(48.dp).testTag("action_${action.name.lowercase()}")) {
            if (isQueued) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, action.title, Modifier.size(22.dp))
            }
        }
        Text(if (isQueued) "Queued" else action.title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun MetricTile(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

internal fun booleanLabel(value: Boolean?, yes: String, no: String): String = when (value) {
    true -> yes
    false -> no
    null -> "Unknown"
}

internal fun formatTime(millis: Long): String = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

internal fun formatDateTime(millis: Long): String = DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.getDefault())
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
