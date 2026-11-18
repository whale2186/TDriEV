package dev.nemesis.drive.model

enum class EntryStep { WELCOME, PHONE, OTP, GARAGE, DASHBOARD }
enum class MainTab(val title: String) {
    HOME("Home"),
    MAP("Map"),
    ACTIVITY("Activity"),
    ACCOUNT("Account");

    companion object {
        @JvmField
        val VEHICLE = MAP
    }
}
enum class ThemeChoice(val title: String) { SYSTEM("System"), DARK("Dark"), LIGHT("Light") }
enum class AccentColor(val title: String, val hexDark: Long, val hexLight: Long) {
    TEAL("Teal", 0xFF00E5FF, 0xFF00838F),
    BLUE("Blue", 0xFF83B5FF, 0xFF205DA9),
    GREEN("Green", 0xFF6EE7B7, 0xFF15803D),
    AMBER("Amber", 0xFFFCD34D, 0xFFD97706),
    PURPLE("Purple", 0xFFD8B4FE, 0xFF7E22CE),
    ROSE("Rose", 0xFFFDA4AF, 0xFFBE123C)
}
enum class DriveAction(val title: String) {
    LOCK("Lock"), UNLOCK("Unlock"), AC_ON("Start AC"), AC_OFF("Stop AC"),
    HORN("Horn"), APPROACH_ON("Approach on"), APPROACH_OFF("Approach off"),
    HAZARD_ON("Hazard on"), HAZARD_OFF("Hazard off"),
    POSITION_ON("Position on"), POSITION_OFF("Position off")
}
enum class OperationPhase { IDLE, CONTACTING, WAKING, WAITING_READY, SENDING, SUCCESS, FAILED, UNKNOWN, CANCELLED }

data class VehicleSnapshot(
    val batteryPercent: Int?,
    val rangeKm: Int?,
    val odometerKm: Double?,
    val locked: Boolean?,
    val charging: Boolean?,
    val cableConnected: Boolean?,
    val doorsClosed: Boolean?,
    val bonnetClosed: Boolean?,
    val bootClosed: Boolean?,
    val ignitionOn: Boolean?,
    val updatedAtMillis: Long?,
    val acOn: Boolean? = false,
    val approachOn: Boolean? = false,
    val hazardOn: Boolean? = false,
    val positionOn: Boolean? = false,
    val receivedAtMillis: Long? = null,
    val odometerUpdatedAtMillis: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationUpdatedAtMillis: Long? = null
)

data class DriveVehicle(
    val id: String,
    val model: String,
    val nickname: String,
    val registration: String,
    val snapshot: VehicleSnapshot
)

data class OperationState(
    val action: DriveAction? = null,
    val phase: OperationPhase = OperationPhase.IDLE,
    val message: String = "",
    val vehicleId: String? = null,
    val requestId: String? = null
) {
    val busy: Boolean get() = phase in setOf(OperationPhase.CONTACTING, OperationPhase.WAKING,
        OperationPhase.WAITING_READY, OperationPhase.SENDING)
}

data class ActivityEntry(
    val id: String,
    val vehicleId: String,
    val vehicleName: String,
    val action: DriveAction,
    val createdAtMillis: Long,
    val outcome: String,
    val steps: List<String>
)

data class DriveUiState(
    val step: EntryStep = EntryStep.WELCOME,
    val tab: MainTab = MainTab.HOME,
    val theme: ThemeChoice = ThemeChoice.DARK,
    val phone: String = "0000000000",
    val otp: String = "",
    val error: String? = null,
    val vehicles: List<DriveVehicle> = emptyList(),
    val selectedVehicleId: String? = null,
    val acDurationMinutes: Int = 10,
    val operation: OperationState = OperationState(),
    val history: List<ActivityEntry> = emptyList(),
    val simulateUnavailable: Boolean = false,
    val demoWakeCount: Int = 0,
    val demoSubmissionCount: Int = 0,
    val isDemo: Boolean = true,
    val authBusy: Boolean = false,
    val refreshing: Boolean = false,
    val termsAccepted: Boolean = false,
    val privacyAccepted: Boolean = false,
    val authNotice: String? = null,
    val previousRequestUnresolved: Boolean = false,
    val autoOpenVehicle: Boolean = true,
    val defaultVehicleId: String? = null,
    val dynamicColor: Boolean = true,
    val queuedActions: Set<DriveAction> = emptySet(),
    val showMyLocation: Boolean = false,
    val userLatitude: Double? = null,
    val userLongitude: Double? = null,
    val isAppLocked: Boolean = false,
    val appLockEnabled: Boolean = false,
    val biometricsEnabled: Boolean = false,
    val hasPinSet: Boolean = false,
    val lockError: String? = null,
    val amoledMode: Boolean = true,
    val accentColor: AccentColor = AccentColor.TEAL,
    val customAccentColor: Long = 0xFF00E5FFL
) {
    val selectedVehicle: DriveVehicle? get() = vehicles.firstOrNull { it.id == selectedVehicleId }
    val busy: Boolean get() = operation.busy
}
