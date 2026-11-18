@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.nemesis.drive.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nemesis.drive.DriveViewModel
import dev.nemesis.drive.model.*

@Composable
fun DriveApp(
    viewModel: DriveViewModel,
    onTriggerBiometric: () -> Unit = {},
    onMinimize: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DriveTheme(
        choice = state.theme,
        dynamicColor = state.dynamicColor,
        amoledMode = state.amoledMode,
        accentColor = state.accentColor,
        customAccentColor = state.customAccentColor
    ) {
        if (state.isAppLocked) {
            AppLockScreen(
                state = state,
                onUnlockWithPin = { pin -> viewModel.verifyPin(pin) },
                onTriggerBiometric = onTriggerBiometric,
                onMinimize = onMinimize
            )
        } else {
            BackHandler(enabled = state.step != EntryStep.WELCOME) { viewModel.back() }
            Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    if (state.isDemo) DemoBanner()
                    state.authNotice?.let { notice ->
                        Text(notice, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)
                                .testTag("auth_notice").semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (state.step == EntryStep.DASHBOARD) VehicleHeader(state, viewModel::openGarage)
                }
            },
            bottomBar = {
                if (state.step == EntryStep.DASHBOARD) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                        MainTab.entries.forEach { tab ->
                            val icon = when (tab) {
                                MainTab.HOME -> Icons.Outlined.Home
                                MainTab.MAP -> Icons.Outlined.LocationOn
                                MainTab.ACTIVITY -> Icons.Outlined.History
                                MainTab.ACCOUNT -> Icons.Outlined.PersonOutline
                            }
                            NavigationBarItem(
                                selected = state.tab == tab,
                                onClick = { viewModel.setTab(tab) },
                                icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                                label = { Text(tab.title) },
                                modifier = Modifier.testTag(if (tab == MainTab.MAP) "tab_vehicle" else "tab_${tab.name.lowercase()}"),
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.onBackground,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            val modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding()
            when (state.step) {
                EntryStep.WELCOME -> WelcomeScreen(state, modifier, viewModel::continueFromWelcome)
                EntryStep.PHONE -> PhoneScreen(state, modifier, viewModel::setPhone, viewModel::requestCode, viewModel::back,
                    viewModel::setTermsAccepted, viewModel::setPrivacyAccepted)
                EntryStep.OTP -> OtpScreen(state, modifier, viewModel::setOtp, viewModel::verifyCode, viewModel::back)
                EntryStep.GARAGE -> GarageScreen(state, modifier, viewModel::selectVehicle, viewModel::back, viewModel::refreshGarage, viewModel::signOut, viewModel::setAutoOpenVehicle)
                EntryStep.DASHBOARD -> Column(modifier) {
                    if (state.isDemo && state.operation.phase != OperationPhase.IDLE) {
                        OperationStrip(state, viewModel::cancelPendingAction)
                    }
                    when (state.tab) {
                        MainTab.HOME -> HomeScreen(state, Modifier.weight(1f), viewModel::runAction, viewModel::setAcDuration,
                            viewModel::refreshTelemetry, viewModel::resolvePreviousRequest, openMap = { viewModel.setTab(MainTab.MAP) })
                        MainTab.MAP -> MapScreen(state, Modifier.weight(1f))
                        MainTab.ACTIVITY -> ActivityScreen(state, Modifier.weight(1f))
                        MainTab.ACCOUNT -> AccountScreen(state, Modifier.weight(1f), viewModel::setTheme,
                            viewModel::openGarage, viewModel::clearHistory, viewModel::signOut,
                            viewModel::setDemoCableConnected, viewModel::setSimulateUnavailable,
                            viewModel::setAutoOpenVehicle, viewModel::setDefaultVehicle,
                            viewModel::setDynamicColor, viewModel::setShowMyLocation,
                            viewModel::setAppLockEnabled, viewModel::setBiometricsEnabled,
                            viewModel::setPin, viewModel::lockApp,
                            viewModel::setAmoledMode, viewModel::setAccentColor, viewModel::setCustomAccentColor)
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun DemoBanner() {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().testTag("preview_banner")) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Science, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("Demo mode · not connected to your car", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun VehicleHeader(state: DriveUiState, openGarage: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (state.isDemo) "TDRIEV / SIMULATOR" else "TDRIEV", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.4.sp)
            Text(state.selectedVehicle?.nickname ?: "Your garage", style = MaterialTheme.typography.titleLarge)
            state.selectedVehicle?.let { vehicle ->
                Text("${vehicle.registration} · ${vehicle.model}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("selected_vehicle"))
            }
        }
        if (state.vehicles.size > 1) {
            FilledTonalIconButton(onClick = openGarage, modifier = Modifier.size(48.dp).testTag("vehicle_switcher")) {
                Icon(Icons.Outlined.SwapHoriz, contentDescription = if (state.isDemo) "Switch demo vehicle" else "Switch vehicle")
            }
        }
    }
}

@Composable
internal fun WelcomeScreen(state: DriveUiState, modifier: Modifier, proceed: () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Drive", style = MaterialTheme.typography.headlineLarge, fontSize = 40.sp, lineHeight = 46.sp)
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.DirectionsCar, if (state.isDemo) "Vehicle preview" else "Your vehicle", Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Your car, at a glance.", style = MaterialTheme.typography.headlineMedium)
                Text(if (state.isDemo) "Explore Drive with two sample vehicles."
                    else "Battery, range and remote controls.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.isDemo) {
            Text("SIMULATOR PREVIEW", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp,
                color = MaterialTheme.colorScheme.primary)
            Text("No real sign-in. No SMS. No vehicle connection.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = proceed, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("welcome_continue"),
            shape = RoundedCornerShape(16.dp)) {
            Text(if (state.isDemo) "Explore demo" else "Sign in")
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Outlined.ArrowForward, null, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun EntryHeading(title: String, subtitle: String, back: () -> Unit) {
    IconButton(onClick = back, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ArrowBack, "Back") }
    Text(title, style = MaterialTheme.typography.headlineLarge)
    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun PhoneScreen(state: DriveUiState, modifier: Modifier, setPhone: (String) -> Unit, proceed: () -> Unit, back: () -> Unit,
    setTerms: (Boolean) -> Unit, setPrivacy: (Boolean) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        EntryHeading(if (state.isDemo) "Make yourself\nat home." else "Sign in", if (state.isDemo) "Use a sample number to explore Drive."
            else "Use your registered mobile number.", back)
        OutlinedTextField(value = state.phone, onValueChange = setPhone,
            modifier = Modifier.fillMaxWidth().testTag("phone_input"),
            enabled = !state.authBusy,
            label = { Text(if (state.isDemo) "Demo mobile number" else "Mobile number") }, prefix = { Text("+91  ") },
            singleLine = true, shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); if (state.isDemo && !state.authBusy) proceed() }),
            supportingText = { Text(if (state.isDemo) "Sample number: 0000000000. Nothing is sent."
                else "We'll send you a verification code.") }, isError = state.error != null)
        if (!state.isDemo) {
            Text("Review the terms and privacy notice in the official Tata app.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ConsentRow("I accept the official app terms", state.termsAccepted, !state.authBusy, "terms_accepted", setTerms)
            ConsentRow("I accept the official privacy notice", state.privacyAccepted, !state.authBusy, "privacy_accepted", setPrivacy)
        }
        InlineError(state.error)
        Button(enabled = !state.authBusy && (state.isDemo || (state.termsAccepted && state.privacyAccepted)),
            onClick = { keyboard?.hide(); proceed() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("phone_continue"),
            shape = RoundedCornerShape(16.dp)) {
            if (state.authBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (state.isDemo) "Continue in demo" else "Send code")
        }
        if (state.isDemo) Text("This is not a Tata account login. Real account access is not available in this preview.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun OtpScreen(state: DriveUiState, modifier: Modifier, setOtp: (String) -> Unit, verify: () -> Unit, back: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        EntryHeading(if (state.isDemo) "A demo code.\nNothing more." else "Verify code",
            if (state.isDemo) "Enter the six-digit sample code to open your demo garage."
            else "Sent to +91 ••••••${state.phone.takeLast(4)}", back)
        if (state.isDemo) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
            Text("No SMS sent. Demo code 123456", modifier = Modifier.fillMaxWidth().padding(18.dp),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        OutlinedTextField(value = state.otp, onValueChange = setOtp,
            modifier = Modifier.fillMaxWidth().testTag("otp_input"), enabled = !state.authBusy, label = { Text(if (state.isDemo) "Demo verification code" else "Verification code") },
            singleLine = true, shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); if (!state.authBusy) verify() }),
            isError = state.error != null,
            textStyle = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 6.sp))
        InlineError(state.error)
        if (state.isDemo) TextButton(onClick = { setOtp("123456"); keyboard?.hide() }, modifier = Modifier.heightIn(min = 48.dp).testTag("otp_use_demo")) {
            Text("Use demo code")
        }
        Button(enabled = !state.authBusy, onClick = { keyboard?.hide(); verify() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("otp_verify"),
            shape = RoundedCornerShape(16.dp)) {
            if (state.authBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (state.isDemo) "Open demo garage" else "Verify code")
        }
        TextButton(onClick = back, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit mobile number") }
    }
}

@Composable
private fun ConsentRow(label: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.testTag(tag))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

internal fun operationTitle(phase: OperationPhase, isDemo: Boolean): String = if (isDemo) {
    when (phase) {
        OperationPhase.IDLE -> "Ready"
        OperationPhase.CONTACTING -> "Contacting demo vehicle"
        OperationPhase.WAKING -> "Waking demo vehicle"
        OperationPhase.WAITING_READY -> "Waiting for demo readiness"
        OperationPhase.SENDING -> "Sending simulated action"
        OperationPhase.SUCCESS -> "Simulated success"
        OperationPhase.FAILED -> "Simulation failed"
        OperationPhase.UNKNOWN -> "Simulated outcome unknown"
        OperationPhase.CANCELLED -> "Simulation cancelled"
    }
} else {
    when (phase) {
        OperationPhase.IDLE -> "Ready"
        OperationPhase.CONTACTING -> "Contacting vehicle service"
        OperationPhase.WAKING -> "Requesting vehicle wake"
        OperationPhase.WAITING_READY -> "Waiting for vehicle readiness"
        OperationPhase.SENDING -> "Sending command"
        OperationPhase.SUCCESS -> "Request completed"
        OperationPhase.FAILED -> "Command failed"
        OperationPhase.UNKNOWN -> "Outcome unknown · check your vehicle before retrying"
        OperationPhase.CANCELLED -> "Pending request cancelled"
    }
}

@Composable
internal fun InlineError(error: String?) {
    if (!error.isNullOrBlank()) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
internal fun GarageScreen(state: DriveUiState, modifier: Modifier, select: (String) -> Unit, back: () -> Unit, refresh: () -> Unit,
    signOut: () -> Unit = {}, setAutoOpen: (Boolean) -> Unit = {}) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        EntryHeading(if (state.isDemo) "Your demo\ngarage." else "Garage",
            if (state.isDemo) "Choose a sample vehicle. Every control stays inside the simulator."
            else "Choose a vehicle.", back)
        InlineError(state.error)
        val loading = state.authBusy || state.refreshing
        if (state.vehicles.isEmpty() && loading) {
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag("garage_loading"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("Loading vehicles…", style = MaterialTheme.typography.bodyLarge)
            }
        } else if (state.vehicles.isEmpty() && state.error == null) {
            Text(if (state.isDemo) "No demo vehicles available." else "No vehicles found.", style = MaterialTheme.typography.titleMedium)
        }
        if (!state.isDemo) {
            OutlinedButton(onClick = refresh, enabled = !state.refreshing && !state.authBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("refresh_garage")) {
                if (state.refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("Refresh")
            }
            TextButton(onClick = signOut, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("garage_sign_out")) {
                Text("Sign out")
            }
        }
        if (state.vehicles.size > 1) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto-select vehicle on startup", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = state.autoOpenVehicle, onCheckedChange = setAutoOpen)
                }
            }
        }
        state.vehicles.forEach { vehicle ->
            Card(onClick = { select(vehicle.id) }, modifier = Modifier.fillMaxWidth().testTag("vehicle_${vehicle.id}"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.DirectionsCar, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        Icon(Icons.Outlined.ArrowForward, null)
                    }
                    Text(vehicle.nickname, style = MaterialTheme.typography.titleLarge)
                    Text("${vehicle.model} · ${vehicle.registration}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (state.isDemo) "SAMPLE VEHICLE" else "SELECT VEHICLE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
private fun OperationStrip(state: DriveUiState, cancel: () -> Unit) {
    val operation = state.operation
    val failed = operation.phase == OperationPhase.FAILED || operation.phase == OperationPhase.UNKNOWN
    val title = operationTitle(operation.phase, state.isDemo)

    Surface(color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
            .testTag("operation_status").semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(if (failed) Icons.Outlined.Info else Icons.Outlined.CheckCircle, null, Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                if (!state.isDemo && operation.phase == OperationPhase.SUCCESS) Text(operation.message.ifBlank { "Command completed" }, style = MaterialTheme.typography.bodySmall)
                else if (operation.message.isNotBlank()) Text(operation.message, style = MaterialTheme.typography.bodySmall,
                    maxLines = 3)
            }
            if (state.busy && operation.phase != OperationPhase.SENDING) {
                TextButton(onClick = cancel, modifier = Modifier.heightIn(min = 48.dp).testTag("cancel_pending")) { Text("Cancel") }
            }
        }
    }
}
