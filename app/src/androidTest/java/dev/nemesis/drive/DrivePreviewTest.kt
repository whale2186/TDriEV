package dev.nemesis.drive

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.nemesis.drive.model.EntryStep
import dev.nemesis.drive.model.MainTab
import dev.nemesis.drive.model.OperationPhase
import dev.nemesis.drive.model.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DrivePreviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val model: DriveViewModel
        get() = ViewModelProvider(compose.activity)[DriveViewModel::class.java]

    private fun openDemoVehicle() {
        compose.onNodeWithTag("welcome_continue").performScrollTo().performClick()
        compose.onNodeWithTag("phone_continue").performScrollTo().performClick()
        compose.onNodeWithTag("otp_input").performTextInput("123456")
        compose.onNodeWithTag("otp_verify").performScrollTo().performClick()
        compose.onNodeWithTag("vehicle_demo-tiago").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.state.value.step == EntryStep.DASHBOARD }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200, 3_000)
        android.os.SystemClock.sleep(350) // Allow the software compositor to commit the latest native frame.
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "previews")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun captureNativeScreenFlowForVisualReview() {
        capture("01-welcome")
        compose.onNodeWithTag("welcome_continue").performScrollTo().performClick()
        capture("02-phone")
        compose.onNodeWithTag("phone_continue").performScrollTo().performClick()
        capture("03-demo-code")
        compose.onNodeWithTag("otp_input").performTextInput("123456")
        compose.onNodeWithTag("otp_verify").performScrollTo().performClick()
        capture("04-garage")
        compose.onNodeWithTag("vehicle_demo-tiago").performScrollTo().performClick()
        compose.waitUntil(3_000) { model.state.value.step == EntryStep.DASHBOARD }
        capture("05-home")
        compose.onNodeWithTag("tab_vehicle").performClick()
        capture("06-vehicle")
        compose.onNodeWithTag("tab_activity").performClick()
        capture("07-activity")
        compose.onNodeWithTag("tab_account").performClick()
        capture("08-account")
    }

    @Test fun nativeAppearanceSelectionAndSignOutWork() {
        openDemoVehicle()
        compose.onNodeWithTag("tab_account").performClick()
        compose.onNodeWithTag("theme_light").performScrollTo().performClick()
        compose.waitUntil(3_000) { model.state.value.theme == ThemeChoice.LIGHT }
        capture("09-account-light")
        compose.onNodeWithTag("sign_out").performScrollTo().performClick()
        compose.waitUntil(3_000) { model.state.value.step == EntryStep.WELCOME }
        assertEquals(null, model.state.value.selectedVehicleId)
        assertEquals(0, model.state.value.history.size)
    }

    @Test fun previewHasNoNetworkPermission() {
        val permissions = compose.activity.packageManager.getPackageInfo(
            compose.activity.packageName, PackageManager.GET_PERMISSIONS
        ).requestedPermissions?.toList().orEmpty()
        assertFalse(permissions.contains(Manifest.permission.INTERNET))
        assertFalse(permissions.contains(Manifest.permission.SEND_SMS))
        compose.onNodeWithTag("preview_banner").assertIsDisplayed()
    }

    @Test fun onboardingVehicleSelectionAndNativeTabsWork() {
        openDemoVehicle()
        assertEquals("demo-tiago", model.state.value.selectedVehicleId)
        for ((tag, tab) in listOf("tab_vehicle" to MainTab.VEHICLE,
                "tab_activity" to MainTab.ACTIVITY, "tab_account" to MainTab.ACCOUNT,
                "tab_home" to MainTab.HOME)) {
            compose.onNodeWithTag(tag).performClick()
            compose.waitUntil(3_000) { model.state.value.tab == tab }
        }
        compose.onNodeWithTag("vehicle_switcher").performClick()
        compose.onNodeWithTag("vehicle_demo-nexon").performScrollTo().performClick()
        compose.waitUntil(3_000) { model.state.value.selectedVehicleId == "demo-nexon" }
        assertEquals(0, model.state.value.demoSubmissionCount)
    }

    @Test fun oneTapControlAutomaticallyReconnectsWithoutConfirmation() {
        openDemoVehicle()
        assertEquals(0, model.state.value.demoWakeCount)
        compose.onNodeWithTag("action_lock").performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.waitUntil(8_000) { model.state.value.operation.phase == OperationPhase.SUCCESS }
        assertEquals(1, model.state.value.demoWakeCount)
        assertEquals(1, model.state.value.demoSubmissionCount)
        assertEquals(1, model.state.value.history.size)
        compose.onNodeWithTag("operation_status").assertIsDisplayed()
        compose.onNodeWithTag("tab_activity").performClick()
        compose.onNodeWithText("Confirm", substring = false).assertDoesNotExist()
    }
}
