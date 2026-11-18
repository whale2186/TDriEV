package dev.nemesis.drive

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nemesis.drive.model.DriveUiState
import dev.nemesis.drive.model.ThemeChoice
import dev.nemesis.drive.ui.DriveTheme
import dev.nemesis.drive.ui.OtpScreen
import dev.nemesis.drive.ui.PhoneScreen
import dev.nemesis.drive.ui.WelcomeScreen
import dev.nemesis.drive.ui.GarageScreen
import dev.nemesis.drive.ui.AccountScreen
import dev.nemesis.drive.ui.operationTitle
import dev.nemesis.drive.model.OperationPhase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure injected UI: no MainActivity, ViewModel, repository, network, SMS or vehicle. */
@RunWith(AndroidJUnit4::class)
class OwnerEntryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ownerWelcomeShowsConciseSignInWithoutScrolling() {
        var continues = 0
        compose.setContent {
            OwnerTestFrame(ThemeChoice.DARK) {
                WelcomeScreen(DriveUiState(isDemo = false, phone = ""), Modifier) { continues++ }
            }
        }
        compose.onNodeWithText("Sign in").assertIsDisplayed()
        compose.onNodeWithTag("welcome_continue").assertIsDisplayed()
        compose.onNodeWithText("INDEPENDENT OWNER ACCESS").assertDoesNotExist()
        compose.onNodeWithText("assistant", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("developer", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("SIMULATOR PREVIEW").assertDoesNotExist()
        compose.onNodeWithText("Explore demo").assertDoesNotExist()
        compose.onNodeWithTag("welcome_continue").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, continues) }
    }

    @Test fun ownerPhoneRequiresConsentAndIntentionalSendTap() {
        var requests = 0
        compose.setContent {
            var state by remember { mutableStateOf(DriveUiState(isDemo = false, phone = "")) }
            OwnerTestFrame(ThemeChoice.LIGHT) {
                PhoneScreen(state, Modifier, { state = state.copy(phone = it) }, { requests++ }, {},
                    { state = state.copy(termsAccepted = it) }, { state = state.copy(privacyAccepted = it) })
            }
        }
        compose.onNodeWithTag("phone_input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Mobile number").assertIsDisplayed()
        compose.onNodeWithText("Send code").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("terms_accepted").assertIsOff()
        compose.onNodeWithTag("privacy_accepted").assertIsOff()
        compose.onNodeWithTag("phone_continue").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Sample number:", substring = true).assertDoesNotExist()
        // A synthetic UI-only value is never passed to any authentication implementation.
        compose.onNodeWithTag("phone_input").performScrollTo().performTextInput("0000000000")
        compose.onNodeWithTag("phone_input").performImeAction()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithTag("terms_accepted").performScrollTo().performClick()
        compose.onNodeWithTag("privacy_accepted").performScrollTo().performClick()
        compose.onNodeWithTag("phone_continue").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun ownerOtpNeverOffersSampleCodeAndBusyVerificationIsDisabled() {
        compose.setContent {
            OwnerTestFrame(ThemeChoice.DARK) {
                OtpScreen(DriveUiState(isDemo = false, phone = "", authBusy = true), Modifier, {}, {}, {})
            }
        }
        compose.onNodeWithTag("otp_use_demo").assertDoesNotExist()
        compose.onNodeWithText("123456", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("otp_input").assertIsNotEnabled()
        compose.onNodeWithTag("otp_verify").performScrollTo().assertIsNotEnabled()
    }

    @Test fun vehicleDataLoadingDisablesCommandsInsteadOfIgnoringTaps() {
        val snapshot = dev.nemesis.drive.model.VehicleSnapshot(null, null, null, null, null, null, null, null, null, null, null)
        val vehicle = dev.nemesis.drive.model.DriveVehicle("fixture-car", "Fixture EV", "Fixture EV", "TEST", snapshot)
        compose.setContent {
            OwnerTestFrame(ThemeChoice.DARK) {
                dev.nemesis.drive.ui.HomeScreen(DriveUiState(isDemo = false, vehicles = listOf(vehicle),
                    selectedVehicleId = vehicle.id, refreshing = true), Modifier, {}, {}, {})
            }
        }
        compose.onNodeWithTag("snapshot_loading").assertIsDisplayed()
        captureUiEvidence("vehicle-loading")
        compose.onNodeWithTag("action_lock").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("action_unlock").performScrollTo().assertIsNotEnabled()
    }

    @Test fun unresolvedRequestReviewRequiresAnExplicitUncheckedAcknowledgement() {
        var resolved = 0
        var resolvedVehicle = ""
        compose.setContent {
            OwnerTestFrame(ThemeChoice.DARK) {
                dev.nemesis.drive.ui.PendingRequestReview("fixture-car", false) { id, accepted ->
                    if (accepted) { resolved++; resolvedVehicle = id }
                }
            }
        }
        compose.onNodeWithTag("previous_request_review").assertIsDisplayed().performClick()
        compose.onNodeWithTag("previous_request_ack").assertIsOff()
        compose.onNodeWithTag("resolve_previous_request").assertIsNotEnabled()
        captureUiEvidence("pending-review")
        compose.onNodeWithTag("dismiss_previous_request").performClick()
        compose.runOnIdle { assertEquals(0, resolved) }
        compose.onNodeWithTag("previous_request_review").assertIsDisplayed().performClick()
        compose.onNodeWithTag("previous_request_ack").assertIsOff().performClick()
        compose.onNodeWithTag("resolve_previous_request").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, resolved); assertEquals("fixture-car", resolvedVehicle) }
    }

    @Test fun garageShowsLoadingInsteadOfAnEmptyResultUntilDiscoveryFinishes() {
        var loading by mutableStateOf(true)
        compose.setContent {
            OwnerTestFrame(ThemeChoice.DARK) {
                GarageScreen(DriveUiState(isDemo = false, authBusy = loading), Modifier, {}, {}, {})
            }
        }
        compose.onNodeWithTag("garage_loading").assertIsDisplayed()
        captureUiEvidence("garage-loading")
        compose.onNodeWithText("No vehicles found.").assertDoesNotExist()
        compose.runOnIdle { loading = false }
        compose.onNodeWithTag("garage_loading").assertDoesNotExist()
        compose.onNodeWithText("No vehicles found.").assertIsDisplayed()
    }

    @Test fun failedGarageLookupDoesNotPretendTheAccountHasNoVehicles() {
        compose.setContent {
            OwnerTestFrame(ThemeChoice.DARK) {
                GarageScreen(DriveUiState(isDemo = false, error = "Couldn't load vehicles."), Modifier, {}, {}, {})
            }
        }
        compose.onNodeWithText("Couldn't load vehicles.").assertIsDisplayed()
        compose.onNodeWithText("No vehicles found.").assertDoesNotExist()
    }

    @Test fun ownerGarageAllowsRefreshAndSignOutAfterFailure() {
        var refreshes = 0
        var signOuts = 0
        compose.setContent {
            OwnerTestFrame(ThemeChoice.DARK) {
                GarageScreen(DriveUiState(isDemo = false, vehicles = emptyList(), error = "Couldn't load vehicles."),
                    Modifier, {}, {}, { refreshes++ }, { signOuts++ })
            }
        }
        compose.onNodeWithText("Couldn't load vehicles.").assertIsDisplayed()
        compose.onNodeWithTag("refresh_garage").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("garage_sign_out").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals(1, signOuts)
        }
    }

    @Test fun ownerGarageSignOutRemainsAvailableWhileRefreshing() {
        var signOuts = 0
        compose.setContent {
            OwnerTestFrame(ThemeChoice.DARK) {
                GarageScreen(DriveUiState(isDemo = false, vehicles = emptyList(), refreshing = true),
                    Modifier, {}, {}, {}, { signOuts++ })
            }
        }
        compose.onNodeWithTag("refresh_garage").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("garage_sign_out").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, signOuts) }
    }

    @Test fun ownerAccountKeepsDisclosureInCollapsedAbout() {
        compose.setContent {
            OwnerTestFrame(ThemeChoice.DARK) {
                AccountScreen(DriveUiState(isDemo = false, phone = "0000000000"), Modifier, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("Independent app.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Demo diagnostics").assertDoesNotExist()
        compose.onNodeWithText("assistant", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("developer", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithTag("account_about").performScrollTo().performClick()
        compose.onNodeWithText("Independent app. Not affiliated with Tata Motors.").performScrollTo().assertIsDisplayed()
    }

    @Test fun ownerCompletedRequestDoesNotClaimPhysicalVerification() {
        assertEquals("Request completed", operationTitle(OperationPhase.SUCCESS, false))
        assertEquals("Simulated success", operationTitle(OperationPhase.SUCCESS, true))
    }
    @Composable
    private fun OwnerTestFrame(theme: ThemeChoice, content: @Composable () -> Unit) {
        DriveTheme(theme) {
            androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                color = androidx.compose.material3.MaterialTheme.colorScheme.background) { content() }
        }
    }
    private fun captureUiEvidence(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200, 3000)
        android.os.SystemClock.sleep(350)
        val directory = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "qa025").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        java.io.File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
