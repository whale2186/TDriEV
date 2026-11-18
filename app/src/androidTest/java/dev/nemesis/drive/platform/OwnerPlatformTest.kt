package dev.nemesis.drive.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.nemesis.drive.model.DriveAction
import dev.nemesis.drive.nativeapi.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Synthetic-only storage fixtures. Never performs network, OTP or vehicle operations. */
@RunWith(AndroidJUnit4::class)
class OwnerPlatformTest {
    private lateinit var context: Context
    private lateinit var prefix: String
    private lateinit var store: AndroidOwnerStore
    private lateinit var directory: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefix = context.packageName + ".owner-test." + UUID.randomUUID()
        store = AndroidOwnerStore(context, prefix)
        directory = File(context.noBackupFilesDir, "owner-" + sha256(prefix))
    }

    @After fun tearDown() {
        directory.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(prefix + ".owner.aes.v1") }
    }

    @Test fun authDiagnosticsContainStageAndStatusButNoCredentials() {
        val prefs = context.getSharedPreferences("connection_status", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store.recordAuthFailure("verify-phone-otp", 403, "HTTP")
        assertEquals("verify-phone-otp", prefs.getString("stage", null))
        assertEquals(403, prefs.getInt("http_status", 0))
        assertEquals(setOf("stage", "http_status", "reason", "at"), prefs.all.keys)
        assertFalse(prefs.all.toString().contains("SYNTHETIC_ONLY_TOKEN"))
        prefs.edit().clear().commit()
    }

    @Test fun encryptedRoundTripSurvivesReconstruction() {
        assertNull(store.load())
        store.save(fixture())
        val restored = AndroidOwnerStore(context, prefix).load()!!
        assertEquals("0000000000", restored.phone)
        assertEquals("SYNTHETIC_ONLY_KEY", restored.keys["x_api_key"])
        assertEquals("SYNTHETIC_ONLY_TOKEN", restored.primary.get("accessToken").asString)
        assertEquals("SYNTHETIC_ONLY_REFRESH", restored.legacy.get("refreshToken").asString)
        assertEquals("SYNTHETIC_ONLY_CRM", restored.account.get("crmId").asString)
        val ciphertext = File(directory, "session.bin").readBytes().toString(Charsets.ISO_8859_1)
        listOf("SYNTHETIC", "accessToken", "0000000000", "x_api_key").forEach { assertFalse(ciphertext.contains(it)) }
    }

    @Test fun everySaveUsesFreshIv() {
        store.save(fixture())
        val first = File(directory, "session.bin").readBytes()
        store.save(fixture())
        val second = File(directory, "session.bin").readBytes()
        assertFalse(first.copyOfRange(8, 20).contentEquals(second.copyOfRange(8, 20)))
        assertFalse(first.contentEquals(second))
    }

    @Test fun corruptCiphertextFailsClosedWithSanitizedError() {
        store.save(fixture())
        val file = File(directory, "session.bin")
        val raw = file.readBytes()
        raw[raw.lastIndex] = (raw.last().toInt() xor 1).toByte()
        file.writeBytes(raw)
        try { AndroidOwnerStore(context, prefix).load(); fail("Corrupt session accepted") }
        catch (error: NativeApiException) {
            assertEquals("Owner storage is unavailable", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun pendingLedgerPersistsAcrossSessionClearAndReconstruction() {
        store.save(fixture())
        assertFalse(store.isBlocked("SYNTHETIC_VEHICLE"))
        store.markPending("SYNTHETIC_VEHICLE", DriveAction.LOCK, "SYNTHETIC_REQUEST")
        store.clear()
        val next = AndroidOwnerStore(context, prefix)
        assertNull(next.load())
        assertTrue(next.isBlocked("SYNTHETIC_VEHICLE"))
        assertFalse(next.isBlocked("SYNTHETIC_OTHER_VEHICLE"))
        try { next.markPending("SYNTHETIC_VEHICLE", DriveAction.UNLOCK, "SYNTHETIC_NEXT"); fail("Duplicate accepted") }
        catch (_: NativeApiException) { }
        next.clearPending("SYNTHETIC_VEHICLE")
        assertFalse(AndroidOwnerStore(context, prefix).isBlocked("SYNTHETIC_VEHICLE"))
    }

    @Test fun corruptLedgerBlocksEveryVehicleAndCannotBeOverwritten() {
        store.markPending("SYNTHETIC_VEHICLE", DriveAction.HORN, "SYNTHETIC_REQUEST")
        File(directory, "safety.bin").writeBytes(byteArrayOf(0, 1, 2))
        val next = AndroidOwnerStore(context, prefix)
        assertTrue(next.isBlocked("SYNTHETIC_OTHER_VEHICLE"))
        try { next.markPending("SYNTHETIC_OTHER_VEHICLE", DriveAction.LOCK, "SYNTHETIC_NEXT"); fail("Corrupt ledger replaced") }
        catch (_: NativeApiException) { }
        try { next.clearPending("SYNTHETIC_VEHICLE"); fail("Corrupt ledger cleared") }
        catch (_: NativeApiException) { }
        next.clear()
        assertTrue(AndroidOwnerStore(context, prefix).isBlocked("SYNTHETIC_OTHER_VEHICLE"))
    }

    @Test fun keyIsNonExportableAndSurvivesSessionClear() {
        store.save(fixture())
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull(keys.getKey(prefix + ".owner.aes.v1", null).encoded)
        store.clear()
        assertTrue(keys.containsAlias(prefix + ".owner.aes.v1"))
        assertNull(keys.getKey(prefix + ".owner.aes.v1", null).encoded)
    }

    @Test fun sessionCiphertextCannotBeSwappedIntoLedger() {
        store.save(fixture())
        File(directory, "safety.bin").writeBytes(File(directory, "session.bin").readBytes())
        assertTrue(AndroidOwnerStore(context, prefix).isBlocked("SYNTHETIC_VEHICLE"))
    }

    @Test fun twoInstancesCannotReserveSameVehicle() {
        val other = AndroidOwnerStore(context, prefix)
        val successes = java.util.concurrent.atomic.AtomicInteger()
        val gate = java.util.concurrent.CountDownLatch(1)
        val threads = listOf(store, other).map { current ->
            Thread {
                gate.await()
                try { current.markPending("SYNTHETIC_VEHICLE", DriveAction.LOCK, "SYNTHETIC_REQUEST"); successes.incrementAndGet() }
                catch (_: NativeApiException) { }
            }.apply { start() }
        }
        gate.countDown()
        threads.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(1, successes.get())
        assertTrue(other.isBlocked("SYNTHETIC_VEHICLE"))
    }

    private fun fixture() = NativeSession("0000000000", mutableMapOf("x_api_key" to "SYNTHETIC_ONLY_KEY"),
        nativeJson("accessToken" to "SYNTHETIC_ONLY_TOKEN"), nativeJson("refreshToken" to "SYNTHETIC_ONLY_REFRESH"),
        nativeJson("crmId" to "SYNTHETIC_ONLY_CRM"))

    private fun sha256(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
