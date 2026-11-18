package dev.nemesis.drive.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.system.Os
import android.system.OsConstants
import com.google.gson.JsonObject
import dev.nemesis.drive.model.DriveAction
import dev.nemesis.drive.nativeapi.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private, no-backup encrypted state. Session deletion never resets uncertainty. */
class AndroidOwnerStore(private val context: Context, aliasPrefix: String = context.packageName) : NativeSessionStore, NativeSafetyLedger {
    private val diagnostics = context.getSharedPreferences("connection_status", Context.MODE_PRIVATE)
    private val snapshotPrefs = context.getSharedPreferences("vehicle_snapshots", Context.MODE_PRIVATE)

    override fun saveSnapshot(vehicleId: String, json: String) {
        snapshotPrefs.edit()
            .putString(vehicleId, json)
            .putString("latest_snapshot", json)
            .apply()
        dev.nemesis.drive.widget.TDriEVWidgetManager.updateAllWidgets(context)
    }

    override fun loadSnapshot(vehicleId: String): String? {
        return snapshotPrefs.getString(vehicleId, null)
    }

    override fun recordAuthFailure(stage: String, statusCode: Int?, reason: String) {
        require(stage in setOf("check-phone", "verify-phone-otp", "access-token", "refresh-token", "redirect-client-auth-code", "ecdh-key-exchange"))
        require(reason in setOf("HTTP", "RESPONSE", "PREPARATION", "TRANSPORT") ||
            (reason.startsWith("HTTP_") && safeAuthFailureCode(reason.removePrefix("HTTP_")) != null))
        require(statusCode == null || statusCode in 100..599)
        check(diagnostics.edit().putString("stage", stage).putInt("http_status", statusCode ?: 0)
            .putString("reason", reason).putLong("at", System.currentTimeMillis()).commit())
    }
    private val alias = "$aliasPrefix.owner.aes.v1"
    private val directory = File(context.noBackupFilesDir, "owner-" + MessageDigest.getInstance("SHA-256")
        .digest(aliasPrefix.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) })
    private val lock = locks.getOrPut(directory.absolutePath) { Any() }
    private val sessionFile = File(directory, "session.bin")
    private val safetyFile = File(directory, "safety.bin")

    override fun load(): NativeSession? = locked {
        if (!exists(sessionFile)) null else {
            val json = read(sessionFile)
            require(json.keySet() == setOf("version", "phone", "keys", "primary", "legacy", "account"))
            requireVersion(json)
            val phone = string(json, "phone")
            require(phone.matches(Regex("[0-9]{10}")))
            val keys = json.getAsJsonObject("keys").entrySet().associate { (name, value) ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
                name to value.asString
            }.toMutableMap()
            NativeSession(phone, keys, json.getAsJsonObject("primary"), json.getAsJsonObject("legacy"), json.getAsJsonObject("account"))
        }
    }

    override fun save(session: NativeSession) = locked {
        require(session.phone.matches(Regex("[0-9]{10}")))
        val json = nativeJson("version" to 1, "phone" to session.phone, "keys" to session.keys)
        json.add("primary", session.primary.deepCopy())
        json.add("legacy", session.legacy.deepCopy())
        json.add("account", session.account.deepCopy())
        write(sessionFile, json)
    }

    override fun clear() = locked {
        AtomicFile(sessionFile).delete()
        check(!exists(sessionFile))
        syncDirectory()
    }

    override fun isBlocked(vehicleId: String): Boolean = false

    override fun markPending(vehicleId: String, action: DriveAction, requestId: String) {
        // No-op: never block vehicle control requests
    }

    override fun clearPending(vehicleId: String) {
        // No-op
    }

    private fun ledger(): JsonObject {
        if (!exists(safetyFile)) return JsonObject()
        val json = read(safetyFile)
        require(json.keySet() == setOf("version", "pending"))
        requireVersion(json)
        val pending = json.getAsJsonObject("pending")
        pending.entrySet().forEach { (id, element) ->
            requireIdentity(id)
            val entry = element.asJsonObject
            require(entry.keySet() == setOf("action", "requestId"))
            DriveAction.valueOf(string(entry, "action"))
            requireIdentity(string(entry, "requestId"))
        }
        return pending
    }

    private fun read(file: File): JsonObject {
        val raw = AtomicFile(file).openRead().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                check(output.size() + count <= LIMIT + 36)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(raw.size >= MAGIC.size + IV_SIZE + 16 && raw.copyOfRange(0, MAGIC.size).contentEquals(MAGIC))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, raw.copyOfRange(8, 20)))
        cipher.updateAAD(aad(file))
        val plain = cipher.doFinal(raw, 20, raw.size - 20)
        try {
            return try { OwnerJson.decode(plain) }
            catch (_: Exception) { throw IllegalStateException() }
        } finally { plain.fill(0) }
    }

    private fun write(file: File, value: JsonObject) {
        val plaintext = value.toString().toByteArray(Charsets.UTF_8)
        val encrypted = try {
            require(plaintext.size <= LIMIT)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true)) // Keystore generates a fresh random IV.
            check(cipher.iv.size == IV_SIZE)
            cipher.updateAAD(aad(file))
            MAGIC + cipher.iv + cipher.doFinal(plaintext)
        } finally { plaintext.fill(0) }
        val atomic = AtomicFile(file)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(encrypted)
            stream.flush()
            stream.fd.sync() // AtomicFile's internal sync/rename failures must not be silently accepted.
            atomic.finishWrite(stream)
            stream = null
            check(atomic.openRead().use { it.readBytes() }.contentEquals(encrypted))
            syncDirectory()
        } catch (error: Exception) {
            stream?.let { atomic.failWrite(it) }
            throw error
        }
    }

    private fun syncDirectory() {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        check(create && !exists(sessionFile) && !exists(safetyFile))
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun aad(file: File) = MAGIC + (alias + ":" + file.name).toByteArray(Charsets.UTF_8)
    private fun exists(file: File) = file.exists() || File(file.path + ".bak").exists() || File(file.path + ".new").exists()
    private fun requireVersion(json: JsonObject) {
        val version = json.get("version")
        require(version != null && version.isJsonPrimitive && version.asJsonPrimitive.isNumber && version.asString == "1")
    }
    private fun string(json: JsonObject, name: String): String {
        val value = json.get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }
    private fun requireIdentity(value: String) { require(value.length in 1..256 && value.none { Character.isISOControl(it) }) }

    private fun <T> locked(block: () -> T): T = synchronized(lock) {
        try {
            check(directory.isDirectory || directory.mkdirs())
            RandomAccessFile(File(directory, "storage.lock"), "rw").use { handle ->
                handle.channel.lock().use { block() }
            }
        } catch (error: NativeApiException) { throw error }
        catch (_: Exception) { throw NativeApiException("Owner storage is unavailable") }
    }

    private companion object {
        const val LIMIT = 2 * 1024 * 1024
        const val IV_SIZE = 12
        val MAGIC = "NDRV0001".toByteArray(Charsets.US_ASCII)
        val locks = ConcurrentHashMap<String, Any>()
    }
}
