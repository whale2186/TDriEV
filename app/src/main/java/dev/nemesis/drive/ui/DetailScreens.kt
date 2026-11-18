@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.nemesis.drive.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nemesis.drive.model.*
import java.util.Locale

@Composable
private fun CarMapView(lat: Double, lon: Double, vehicleName: String, userLat: Double? = null, userLon: Double? = null) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val context = androidx.compose.ui.platform.LocalContext.current
    val leafletCss = remember {
        runCatching { context.assets.open("leaflet.css").bufferedReader().use { it.readText() } }.getOrDefault("")
    }
    val leafletJs = remember {
        runCatching { context.assets.open("leaflet.js").bufferedReader().use { it.readText() } }.getOrDefault("")
    }
    val html = remember(lat, lon, isDark, userLat, userLon, leafletCss, leafletJs) {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <style>
                $leafletCss
                html, body {
                    margin: 0;
                    padding: 0;
                    width: 100%;
                    height: 100%;
                    overflow: hidden;
                    background: ${if (isDark) "#121212" else "#f5f5f5"};
                }
                #map {
                    position: absolute;
                    top: 0;
                    bottom: 0;
                    left: 0;
                    right: 0;
                    width: 100%;
                    height: 100%;
                    min-height: 260px;
                }
                .car-marker-box {
                    width: 38px;
                    height: 38px;
                    background: #1976d2;
                    border: 2.5px solid #ffffff;
                    border-radius: 50%;
                    box-shadow: 0 4px 10px rgba(0,0,0,0.5);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                }
                .user-marker-box {
                    width: 22px;
                    height: 22px;
                    background: #10b981;
                    border: 2.5px solid #ffffff;
                    border-radius: 50%;
                    box-shadow: 0 0 10px rgba(16,185,129,0.8);
                }
            </style>
            <script>
                $leafletJs
            </script>
        </head>
        <body>
            <div id="map"></div>
            <script>
                var map = L.map('map', { zoomControl: true, attributionControl: false }).setView([$lat, $lon], 16);
                ${if (isDark) """
                L.tileLayer('https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}', { maxZoom: 19 }).addTo(map);
                L.tileLayer('https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}', { maxZoom: 19 }).addTo(map);
                """ else """
                L.tileLayer('https://services.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}', { maxZoom: 19 }).addTo(map);
                """}

                var carSvg = '<div class="car-marker-box">' +
                    '<svg viewBox="0 0 24 24" width="22" height="22" fill="#ffffff">' +
                    '<path d="M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h12v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99zM6.5 16c-.83 0-1.5-.67-1.5-1.5S5.67 13 6.5 13s1.5.67 1.5 1.5S7.33 16 6.5 16zm11 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM5 11l1.5-4.5h11L19 11H5z"/>' +
                    '</svg></div>';

                var carIcon = L.divIcon({ html: carSvg, className: '', iconSize: [38, 38], iconAnchor: [19, 19], popupAnchor: [0, -19] });
                var carMarker = L.marker([$lat, $lon], { icon: carIcon }).addTo(map).bindPopup('<b>$vehicleName</b>').openPopup();

                ${if (userLat != null && userLon != null) """
                var userIcon = L.divIcon({ html: '<div class="user-marker-box"></div>', className: '', iconSize: [22, 22], iconAnchor: [11, 11] });
                var userMarker = L.marker([$userLat, $userLon], { icon: userIcon }).addTo(map).bindPopup('<b>You</b>');
                var bounds = L.latLngBounds([[$lat, $lon], [$userLat, $userLon]]);
                map.fitBounds(bounds, { padding: [50, 50] });
                """ else ""}

                setTimeout(function() { map.invalidateSize(); }, 200);
                setTimeout(function() { map.invalidateSize(); }, 600);
            </script>
        </body>
        </html>
        """.trimIndent()
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 DriveApp/0.2.6"
                webViewClient = android.webkit.WebViewClient()
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                        android.util.Log.d("DriveMap", "${message?.message()} (line ${message?.lineNumber()})")
                        return true
                    }
                }
                tag = html
                loadDataWithBaseURL("https://openstreetmap.org", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL("https://openstreetmap.org", html, "text/html", "UTF-8", null)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
internal fun MapScreen(state: DriveUiState, modifier: Modifier) {
    val vehicle = state.selectedVehicle
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        ScreenTitle("Map", if (state.isDemo) "Simulated location, not live telemetry." else "Last reported vehicle location & details")
        if (vehicle == null) {
            Text(if (state.isDemo) "No demo vehicle selected." else "No owned vehicle selected.")
            return@Column
        }
        val snapshot = vehicle.snapshot
        val lat = snapshot.latitude
        val lon = snapshot.longitude
        val locTime = snapshot.locationUpdatedAtMillis ?: snapshot.updatedAtMillis

        // Interactive Map
        if (lat != null && lon != null) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().height(260.dp)) {
                CarMapView(lat, lon, vehicle.model, if (state.showMyLocation) state.userLatitude else null, if (state.showMyLocation) state.userLongitude else null)
            }
        } else {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().height(180.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.GpsFixed, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Acquiring GPS fix…", style = MaterialTheme.typography.titleMedium)
                        Text("Vehicle location updates when GPS fix is acquired", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // Location Card
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    FilledTonalIconButton(onClick = {}, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.LocationOn, "Vehicle location", tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Vehicle Location", style = MaterialTheme.typography.titleMedium)
                        Text(if (lat != null && lon != null) String.format(Locale.getDefault(), "%.4f° N, %.4f° E", lat, lon) else "Acquiring GPS fix…",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (locTime != null) {
                    Text("Last reported · ${formatDateTime(locTime)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (lat != null && lon != null) {
                    Button(
                        onClick = {
                            val uri = android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon(${android.net.Uri.encode(vehicle.model)})")
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Outlined.Navigation, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open in Maps")
                    }
                }
            }
        }

        // Vehicle info card
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Icon(Icons.Outlined.DirectionsCar, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(vehicle.model, style = MaterialTheme.typography.titleLarge)
                    Text(vehicle.registration, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (state.isDemo) "Demo vehicle" else "Owned vehicle", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        DetailGroup("Energy & distance", listOf(
            "Battery" to (snapshot.batteryPercent?.let { "$it%" } ?: "Unknown"),
            "Estimated range" to (snapshot.rangeKm?.let { "$it km" } ?: "Unknown"),
            "Odometer" to (snapshot.odometerKm?.let { String.format(Locale.getDefault(), "%,.1f km", it) } ?: "Unknown"),
            "Charging" to booleanLabel(snapshot.charging, "Charging", "Not charging"),
            "Charge cable" to booleanLabel(snapshot.cableConnected, "Connected", "Disconnected")
        ))
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
        Text(snapshot.updatedAtMillis?.let { "${if (state.isDemo) "Cached demo snapshot" else "Vehicle event time"} · ${formatDateTime(it)}" } ?: "Snapshot time unknown",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.isDemo) Text("Controls change this sample fixture only. Unknown observations stay unknown; this preview never reads your car.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun VehicleScreen(state: DriveUiState, modifier: Modifier) = MapScreen(state, modifier)

@Composable
internal fun DetailGroup(title: String, values: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                values.forEachIndexed { index, (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (index != values.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
internal fun ActivityScreen(state: DriveUiState, modifier: Modifier) {
    LazyColumn(modifier.fillMaxWidth().testTag("activity_list"), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("Activity", if (state.isDemo) "A record of this demo session." else "Requests and reported results") }
        if (state.history.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Outlined.History, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Nothing requested yet.", style = MaterialTheme.typography.titleLarge)
                    Text(if (state.isDemo) "Try a control on Home. Its simulated progress and result will appear here."
                        else "Your requests will appear here.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(state.history, key = { it.id }) { entry -> ActivityCard(entry, state.isDemo) }
    }
}

@Composable
private fun ActivityCard(entry: ActivityEntry, isDemo: Boolean) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    Card(onClick = { expanded = !expanded }, shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.action.title, style = MaterialTheme.typography.titleMedium)
                    Text("${entry.vehicleName} · ${formatDateTime(entry.createdAtMillis)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "Hide action details" else "Show action details")
            }
            Text("${if (isDemo) "Simulated" else "Vehicle service"} · ${entry.outcome}", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (entry.steps.isEmpty()) Text("No additional steps recorded.", style = MaterialTheme.typography.bodyMedium)
                entry.steps.forEachIndexed { index, step ->
                    Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun AccountScreen(state: DriveUiState, modifier: Modifier, setTheme: (ThemeChoice) -> Unit,
    openGarage: () -> Unit, clearHistory: () -> Unit, signOut: () -> Unit,
    setCable: (Boolean) -> Unit, setUnavailable: (Boolean) -> Unit,
    setAutoOpenVehicle: (Boolean) -> Unit = {},
    setDefaultVehicle: (String?) -> Unit = {},
    setDynamicColor: (Boolean) -> Unit = {},
    setShowMyLocation: (Boolean) -> Unit = {},
    setAppLockEnabled: (Boolean) -> Unit = {},
    setBiometricsEnabled: (Boolean) -> Unit = {},
    setPin: (String) -> Unit = {},
    lockApp: () -> Unit = {},
    setAmoledMode: (Boolean) -> Unit = {},
    setAccentColor: (AccentColor) -> Unit = {},
    setCustomAccentColor: (Long) -> Unit = {}) {
    var showSetPinDialog by remember { mutableStateOf(false) }
    var showChangePinDialog by remember { mutableStateOf(false) }
    var showCustomColorDialog by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenTitle("Account", if (state.isDemo) "Your preview, your preferences." else "Sign-in and preferences")
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PersonOutline, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (state.isDemo) "Demo explorer" else "Signed in", style = MaterialTheme.typography.titleMedium)
                    Text("+91 ••••••${state.phone.takeLast(4)}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.isDemo) Text("No real account connected", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Security & App Lock Section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Security & App Lock", style = MaterialTheme.typography.titleMedium)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("App Lock protection", style = MaterialTheme.typography.bodyMedium)
                            Text("Require PIN to open TDriEV", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = state.appLockEnabled,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    if (!state.hasPinSet) {
                                        showSetPinDialog = true
                                    } else {
                                        setAppLockEnabled(true)
                                    }
                                } else {
                                    setAppLockEnabled(false)
                                }
                            },
                            modifier = Modifier.testTag("switch_app_lock")
                        )
                    }

                    if (state.appLockEnabled) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Biometric unlock", style = MaterialTheme.typography.bodyMedium)
                                Text("Unlock using fingerprint or face recognition", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = state.biometricsEnabled,
                                onCheckedChange = setBiometricsEnabled,
                                modifier = Modifier.testTag("switch_biometrics")
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showChangePinDialog = true },
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Outlined.Key, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Change PIN")
                            }
                            Button(
                                onClick = lockApp,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Lock Now")
                            }
                        }
                    }
                }
            }
        }

        if (showSetPinDialog) {
            SetPinDialog(
                title = "Set 4-Digit App PIN",
                onConfirm = { pin ->
                    setPin(pin)
                    setAppLockEnabled(true)
                    showSetPinDialog = false
                },
                onDismiss = { showSetPinDialog = false }
            )
        }

        if (showChangePinDialog) {
            SetPinDialog(
                title = "Change App PIN",
                onConfirm = { pin ->
                    setPin(pin)
                    showChangePinDialog = false
                },
                onDismiss = { showChangePinDialog = false }
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice.entries.forEach { theme ->
                    FilterChip(selected = state.theme == theme, onClick = { setTheme(theme) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("theme_${theme.name.lowercase()}"),
                        label = { Text(theme.title) },
                        leadingIcon = if (state.theme == theme) ({ Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }) else null)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Material You dynamic color", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = state.dynamicColor, onCheckedChange = setDynamicColor, modifier = Modifier.testTag("dynamic_color_switch"))
                    }
                }
            }

            // Accent Color Picker (when Material You is disabled)
            if (!state.dynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("Accent Color", style = MaterialTheme.typography.bodyMedium)
                                Text("Tap a color or create your own custom shade", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(
                                onClick = { showCustomColorDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Outlined.Palette, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Custom", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        val universalPresets = listOf(
                            "Red" to 0xFFF44336L,
                            "Orange" to 0xFFFF9800L,
                            "Green" to 0xFF4CAF50L,
                            "Teal" to 0xFF009688L,
                            "Blue" to 0xFF2196F3L,
                            "Purple" to 0xFF9C27B0L,
                            "Pink" to 0xFFE91E63L
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            universalPresets.forEach { (name, hex) ->
                                val isSelected = state.customAccentColor == hex
                                val colorVal = androidx.compose.ui.graphics.Color(hex)
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(colorVal)
                                        .clickable {
                                            setCustomAccentColor(hex)
                                            setDynamicColor(false)
                                        }
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.onBackground else androidx.compose.ui.graphics.Color.Transparent,
                                            shape = androidx.compose.foundation.shape.CircleShape
                                        )
                                        .testTag("color_preset_${name.lowercase()}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = name,
                                            tint = androidx.compose.ui.graphics.Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showCustomColorDialog) {
                CustomColorPickerDialog(
                    initialColor = state.customAccentColor,
                    onColorSelected = { color ->
                        setCustomAccentColor(color)
                        setDynamicColor(false)
                        showCustomColorDialog = false
                    },
                    onDismiss = { showCustomColorDialog = false }
                )
            }

            // AMOLED Mode Switch
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AMOLED true black", style = MaterialTheme.typography.bodyMedium)
                        Text("Pitch black background (#000000) for OLED battery saving", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.amoledMode, onCheckedChange = setAmoledMode, modifier = Modifier.testTag("amoled_mode_switch"))
                }
            }
        }

        // Location & Navigation Settings
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Location & Navigation", style = MaterialTheme.typography.titleMedium)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Show my live location on map", style = MaterialTheme.typography.bodyMedium)
                            Text("Display real-time user GPS pin alongside your car", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.showMyLocation, onCheckedChange = setShowMyLocation)
                    }
                    if (state.showMyLocation) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                if (state.userLatitude != null && state.userLongitude != null)
                                    String.format(Locale.getDefault(), "Live coordinates: %.4f° N, %.4f° E", state.userLatitude, state.userLongitude)
                                else "Acquiring live user location…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // App Preferences
        if (state.vehicles.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("App Preferences", style = MaterialTheme.typography.titleMedium)
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Auto-open vehicle on startup", style = MaterialTheme.typography.bodyMedium)
                                Text("Bypass garage screen directly to vehicle dashboard", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = state.autoOpenVehicle, onCheckedChange = setAutoOpenVehicle)
                        }
                        if (state.autoOpenVehicle) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Text("Select default vehicle to open:", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.vehicles.forEach { v ->
                                    val isSelected = (state.defaultVehicleId ?: state.selectedVehicleId) == v.id
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { setDefaultVehicle(v.id) },
                                        label = { Text(v.model) },
                                        leadingIcon = if (isSelected) ({ Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }) else null
                                    )
                                }
                            }
                        }
                    }
                }
            }

            OutlinedButton(onClick = openGarage, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("account_vehicle_picker"),
                shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (state.isDemo) "Switch demo vehicle" else "Switch vehicle")
            }
        }
        if (state.isDemo) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Demo diagnostics", style = MaterialTheme.typography.titleMedium)
            Text("Change simulator conditions. These never affect a real vehicle.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    DiagnosticSwitch("Charge cable connected", "Demo AC precondition", state.selectedVehicle?.snapshot?.cableConnected == true,
                        !state.busy, "demo_cable_connected", setCable)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    DiagnosticSwitch("Simulate unavailable", "Exercise the demo failure state", state.simulateUnavailable,
                        !state.busy, "demo_unavailable", setUnavailable)
                }
            }
            Text("Demo wake attempts: ${state.demoWakeCount} · Demo submissions: ${state.demoSubmissionCount}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (state.isDemo) "Local demo data" else "Session & local activity", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = clearHistory, enabled = state.history.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("clear_history")) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (state.isDemo) "Clear demo activity" else "Clear local activity")
            }
            OutlinedButton(onClick = signOut, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("sign_out"),
                shape = RoundedCornerShape(14.dp)) { Text(if (state.isDemo) "Sign out of demo" else "Sign out") }
        }
        if (state.isDemo) Text("Drive · native simulator preview\nNo SMS, real authentication or vehicle network access.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else AboutSection()
    }
}

@Composable
private fun AboutSection() {
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().testTag("account_about"),
        shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("About TDriEV", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "Hide app details" else "Show app details")
            }
            if (expanded) {
                Text("TDriEV ${dev.nemesis.drive.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text("Independent app. Not affiliated with Tata Motors.", style = MaterialTheme.typography.bodySmall)
                Text("Sign-in is encrypted on this device. Sign out to remove it. Activity is stored locally; clearing it does not remove service records.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Status may be out of date. Results are reported by the vehicle service, not independently verified. Refresh does not wake your vehicle.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DiagnosticSwitch(title: String, subtitle: String, checked: Boolean, enabled: Boolean, tag: String,
    onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.sizeIn(minWidth = 52.dp, minHeight = 48.dp).testTag(tag).semantics { contentDescription = title },
            thumbContent = if (checked) ({ Icon(Icons.Outlined.Check, title, Modifier.size(16.dp)) }) else null)
    }
}

@Composable
private fun ScreenTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SetPinDialog(
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter a 4-digit numeric PIN to protect TDriEV.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pin = it },
                    label = { Text("New 4-digit PIN") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_new_pin")
                )
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) confirmPin = it },
                    label = { Text("Confirm PIN") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("input_confirm_pin")
                )
                errorText?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (pin.length != 4) {
                        errorText = "PIN must be exactly 4 digits"
                    } else if (pin != confirmPin) {
                        errorText = "PINs do not match"
                    } else {
                        onConfirm(pin)
                    }
                },
                modifier = Modifier.testTag("btn_save_pin")
            ) {
                Text("Save PIN")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CustomColorPickerDialog(
    initialColor: Long,
    onColorSelected: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val initialHsv = remember(initialColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor.toInt(), hsv)
        hsv
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }

    val currentColorLong = remember(hue, saturation, value) {
        val hsv = floatArrayOf(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
        (0xFFL shl 24) or (android.graphics.Color.HSVToColor(hsv).toLong() and 0xFFFFFFL)
    }
    val currentColor = androidx.compose.ui.graphics.Color(currentColorLong)
    val hexString = String.format("#%06X", currentColorLong and 0xFFFFFFL)

    val rainbowColors = remember {
        listOf(
            androidx.compose.ui.graphics.Color.Red,
            androidx.compose.ui.graphics.Color.Yellow,
            androidx.compose.ui.graphics.Color.Green,
            androidx.compose.ui.graphics.Color.Cyan,
            androidx.compose.ui.graphics.Color.Blue,
            androidx.compose.ui.graphics.Color.Magenta,
            androidx.compose.ui.graphics.Color.Red
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Color Picker", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(currentColor)
                            .border(2.dp, MaterialTheme.colorScheme.outlineVariant, androidx.compose.foundation.shape.CircleShape)
                    )
                    Text(hexString, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 2D Saturation-Value Canvas
                Text("Touch or drag to pick shade", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .pointerInput(hue) {
                            detectTapGestures { offset ->
                                saturation = (offset.x / size.width).coerceIn(0f, 1f)
                                value = (1f - offset.y / size.height).coerceIn(0f, 1f)
                            }
                        }
                        .pointerInput(hue) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                saturation = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val pureHue = androidx.compose.ui.graphics.Color(
                            android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
                        )
                        // Horizontal: White to Pure Hue
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(androidx.compose.ui.graphics.Color.White, pureHue)
                            )
                        )
                        // Vertical: Transparent to Black
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.Black)
                            )
                        )

                        // Selector reticle
                        val selX = saturation * size.width
                        val selY = (1f - value) * size.height
                        drawCircle(
                            color = androidx.compose.ui.graphics.Color.White,
                            radius = 12.dp.toPx(),
                            center = Offset(selX, selY),
                            style = Stroke(width = 3.dp.toPx())
                        )
                        drawCircle(
                            color = androidx.compose.ui.graphics.Color.Black,
                            radius = 14.dp.toPx(),
                            center = Offset(selX, selY),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }

                // Hue Rainbow Spectrum Bar
                Text("Hue", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                hue = (offset.x / size.width * 360f).coerceIn(0f, 360f)
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                hue = (change.position.x / size.width * 360f).coerceIn(0f, 360f)
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(brush = Brush.horizontalGradient(rainbowColors))
                        val thumbX = (hue / 360f) * size.width
                        val pureHue = androidx.compose.ui.graphics.Color(
                            android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
                        )
                        drawCircle(
                            color = androidx.compose.ui.graphics.Color.White,
                            radius = 16.dp.toPx(),
                            center = Offset(thumbX, size.height / 2f),
                            style = Stroke(width = 3.dp.toPx())
                        )
                        drawCircle(
                            color = pureHue,
                            radius = 13.dp.toPx(),
                            center = Offset(thumbX, size.height / 2f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onColorSelected(currentColorLong)
                    onDismiss()
                }
            ) {
                Text("Select Color")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
