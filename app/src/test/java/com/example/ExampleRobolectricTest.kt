package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.example.connectivity.TransferProtocol
import com.example.data.local.AppActivityEventEntity
import com.example.data.local.AppDatabase
import com.example.data.local.AppInventorySnapshotEntity
import com.example.data.local.CallLogEntity
import com.example.data.preferences.InternetConnectivityConfig
import com.example.data.preferences.LoginOutcome
import com.example.data.preferences.PasswordChangeOutcome
import com.example.data.preferences.SettingsStore
import com.example.security.CryptoManager
import com.example.ui.auth.googleWebClientId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Device Recorder", appName)
  }

  @Test
  fun `password hashing passphrase encryption and field encryption roundtrip`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val verifier = CryptoManager.hashPassword("SecretPass#2026")
    assertTrue(CryptoManager.verifyPassword("SecretPass#2026", verifier))
    assertFalse(CryptoManager.verifyPassword("WrongPass", verifier))

    val plain = "encrypted-device-records-payload".toByteArray(Charsets.UTF_8)
    val cipher = CryptoManager.encryptWithPassphrase(plain, "BackupPass123")
    val decrypted = CryptoManager.decryptWithPassphrase(cipher, "BackupPass123")
    assertEquals(String(plain, Charsets.UTF_8), String(decrypted, Charsets.UTF_8))

    val encPhone = CryptoManager.encryptField(context, "+1-555-0199")
    assertTrue(encPhone.startsWith("enc:"))
    assertEquals("+1-555-0199", CryptoManager.decryptField(context, encPhone))
  }

  @Test
  fun `manual admin login and current-password change work offline with zero recovery codes`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = SettingsStore(context)
    store.ensureInitialProvisioning()

    assertFalse(store.isAdminLoggedInFlow.first())

    val wrongId = store.verifyAdminLogin("WRONG_ID", BuildConfig.INITIAL_ADMIN_PASSWORD)
    assertTrue(wrongId is LoginOutcome.Invalid)

    val badPassword = store.verifyAdminLogin(BuildConfig.ADMIN_ID, "incorrect")
    assertTrue(badPassword is LoginOutcome.Invalid)

    val goodLogin = store.verifyAdminLogin(BuildConfig.ADMIN_ID, BuildConfig.INITIAL_ADMIN_PASSWORD)
    assertEquals(LoginOutcome.Success, goodLogin)
    assertTrue(store.isAdminLoggedInFlow.first())

    val invalidAdminId = store.changePassword("WRONG_ID", BuildConfig.INITIAL_ADMIN_PASSWORD, "NewAdminPass99!")
    assertTrue(invalidAdminId is PasswordChangeOutcome.InvalidCredentials)

    val invalidCurrent = store.changePassword(BuildConfig.ADMIN_ID, "WrongCurrent", "NewAdminPass99!")
    assertTrue(invalidCurrent is PasswordChangeOutcome.InvalidCredentials)

    val changeResult = store.changePassword(BuildConfig.ADMIN_ID, BuildConfig.INITIAL_ADMIN_PASSWORD, "NewAdminPass99!")
    assertEquals(PasswordChangeOutcome.Success, changeResult)

    assertEquals(
      LoginOutcome.Success,
      store.verifyAdminLogin(BuildConfig.ADMIN_ID, "NewAdminPass99!"),
    )
    assertEquals(
      PasswordChangeOutcome.Success,
      store.changePassword(BuildConfig.ADMIN_ID, "NewAdminPass99!", BuildConfig.INITIAL_ADMIN_PASSWORD),
    )

    store.setAdminLoggedIn(false)
    assertFalse(store.isAdminLoggedInFlow.first())
  }

  @Test
  fun `default theme mode is bright mode and persistence supports dark light and system`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = SettingsStore(context)
    assertEquals("light", store.themeModeFlow.first())
    assertTrue(store.getRecordingSettingsOnce().callLogEnabled)

    store.setThemeMode("dark")
    assertEquals("dark", store.themeModeFlow.first())
    store.setThemeMode("system")
    assertEquals("system", store.themeModeFlow.first())
    store.setThemeMode("light")
    assertEquals("light", store.themeModeFlow.first())
  }

  @Test
  fun `room database configuration stores and retrieves activity records and encrypted call logs`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    try {
      // 1. Verify AppActivityEventEntity persistence (Install / Uninstall / Update)
      val event = AppActivityEventEntity(
        deviceId = "DEV-TEST-01",
        appName = "Test App",
        packageName = "com.example.testapp",
        versionName = "1.0.0",
        eventType = "INSTALLED",
        installerPackage = "com.android.vending",
        installerLabel = "Google Play Store",
        eventDate = "2026-11-25",
        eventTime = "12:00:00",
        timestampMillis = 1764072000000L,
      )
      db.appActivityDao().insertEvent(event)

      val retrievedEvents = db.appActivityDao().getEventsForDeviceOnce("DEV-TEST-01")
      assertEquals(1, retrievedEvents.size)
      assertEquals("INSTALLED", retrievedEvents[0].eventType)
      assertEquals("com.example.testapp", retrievedEvents[0].packageName)

      // 2. Verify CallLogEntity with encrypted phone number storage
      val plainPhone = "+1-555-0199"
      val encryptedPhone = CryptoManager.encryptField(context, plainPhone)
      assertTrue(encryptedPhone.startsWith("enc:"))

      val callLog = CallLogEntity(
        id = "DEV-TEST-01:1:1764072000000",
        deviceId = "DEV-TEST-01",
        callLogId = 1L,
        phoneNumber = encryptedPhone,
        callType = "INCOMING",
        callDate = "2026-11-25",
        callTime = "12:01:00",
        durationSeconds = 45L,
        timestampMillis = 1764072000000L,
      )
      db.callLogDao().upsertCallLogs(listOf(callLog))

      val retrievedLogs = db.callLogDao().getCallLogsForDeviceOnce("DEV-TEST-01")
      assertEquals(1, retrievedLogs.size)
      assertTrue(retrievedLogs[0].phoneNumber.startsWith("enc:"))
      assertEquals(plainPhone, CryptoManager.decryptField(context, retrievedLogs[0].phoneNumber))
      assertEquals("INCOMING", retrievedLogs[0].callType)

      // 3. Verify AppInventorySnapshotEntity
      val invItem = AppInventorySnapshotEntity(
        id = "DEV-TEST-01:com.example.testapp",
        deviceId = "DEV-TEST-01",
        appName = "Test App",
        packageName = "com.example.testapp",
        versionName = "1.0.0",
        versionCode = 100L,
        isInstalled = true,
        firstInstallTimeMillis = 1764072000000L,
        lastUpdateTimeMillis = 1764072000000L,
        installDate = "2026-11-25",
        installTime = "12:00:00",
        installerPackage = "com.android.vending",
        installerLabel = "Google Play Store",
      )
      db.appInventoryDao().upsertItem(invItem)

      val retrievedInv = db.appInventoryDao().getInventoryForDeviceOnce("DEV-TEST-01")
      assertEquals(1, retrievedInv.size)
      assertEquals(true, retrievedInv[0].isInstalled)
      assertEquals("Test App", retrievedInv[0].appName)
    } finally {
      db.close()
    }
  }

  @Test
  fun `internet configuration is pre-configured out of the box with zero user setup needed`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = SettingsStore(context)
    val config = store.getInternetConfigOnce()
    assertTrue(config.isConfigured)
    assertEquals(InternetConnectivityConfig.DEFAULT_SIGNALING_URL, config.signalingUrl)
    assertEquals(InternetConnectivityConfig.DEFAULT_STUN_URI, config.stunUri)
    assertEquals(InternetConnectivityConfig.DEFAULT_UPDATE_MANIFEST_URL, config.updateManifestUrl)
    assertTrue(config.isAutoConfigured)
  }

  @Test
  fun `default admin credentials and firebase database config are properly provisioned`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertTrue(BuildConfig.ADMIN_ID.isNotBlank())
    assertTrue(BuildConfig.INITIAL_ADMIN_PASSWORD.isNotBlank())
    assertEquals(
      "ai-studio-android-deviceac-885f4179-468d-490b-b294-b962ea389986",
      context.getString(R.string.firestore_database_id),
    )

    val store = SettingsStore(context)
    store.ensureInitialProvisioning()
    assertEquals(
      LoginOutcome.Success,
      store.verifyAdminLogin(BuildConfig.ADMIN_ID, BuildConfig.INITIAL_ADMIN_PASSWORD),
    )
    store.setAdminLoggedIn(false)
  }

  @Test
  fun `transfer protocol verifies same-app signature rejects foreign payloads and roundtrips gzip`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    try {
      val store = SettingsStore(context)
      store.ensureInitialProvisioning()
      val secret = CryptoManager.generatePairingSecret()

      val envelope = TransferProtocol.buildEncryptedRecordSnapshot(context, db, store, secret)
      val compressed = TransferProtocol.compressTextBase64(envelope)
      val decompressed = TransferProtocol.decompressTextBase64(compressed)
      assertEquals(envelope, decompressed)

      val imported =
        TransferProtocol.verifyAndImportSnapshot(
          context = context,
          db = db,
          envelopeJson = decompressed,
          sharedSecret = secret,
          transportMethod = TransferProtocol.METHOD_INTERNET,
          internetEndpointId = "123456",
        )
      assertEquals(store.getLocalDeviceId(), imported.deviceId)

      val foreignEnvelope =
        JSONObject(envelope).put("appProtocolId", "com.unknown.other.app").toString()
      var rejected = false
      try {
        TransferProtocol.verifyAndImportSnapshot(
          context = context,
          db = db,
          envelopeJson = foreignEnvelope,
          sharedSecret = secret,
          transportMethod = TransferProtocol.METHOD_BLUETOOTH,
        )
      } catch (_: IllegalArgumentException) {
        rejected = true
      }
      assertTrue(rejected)
    } finally {
      db.close()
    }
  }

  @Test
  fun `github release parser detects newer versionCode versionName and apk asset`() {
    val releaseJson =
      """
      {
        "tag_name": "v1.0.5",
        "name": "Device Recorder v1.0.5",
        "body": "Automated Android APK Release\nversionName: 1.0.5\nversionCode: 6",
        "assets": [
          {
            "name": "DeviceRecorder-v1.0.5-b6.apk",
            "browser_download_url": "https://github.com/ankitkbc12345/Device-Recorder/releases/download/v1.0.5/DeviceRecorder-v1.0.5-b6.apk",
            "size": 12345678
          }
        ]
      }
      """.trimIndent()

    val parsed = com.example.updates.GitHubReleaseParser.parseGitHubReleaseJson(releaseJson)
    assertTrue(parsed != null)
    assertEquals("1.0.5", parsed!!.versionName)
    assertEquals(6, parsed.versionCode)
    assertEquals("DeviceRecorder-v1.0.5-b6.apk", parsed.apkAssetName)
    assertEquals(12345678L, parsed.apkSizeBytes)
    assertTrue(parsed.apkDownloadUrl.endsWith(".apk"))

    assertTrue(
      com.example.updates.GitHubReleaseParser.isNewerVersion(
        remoteVersionCode = parsed.versionCode,
        remoteVersionName = parsed.versionName,
        currentVersionCode = 1,
        currentVersionName = "1.0",
      )
    )
    assertFalse(
      com.example.updates.GitHubReleaseParser.isNewerVersion(
        remoteVersionCode = 1,
        remoteVersionName = "1.0",
        currentVersionCode = 1,
        currentVersionName = "1.0",
      )
    )
    assertEquals(
      "https://api.github.com/repos/owner/repo/releases/latest",
      com.example.updates.GitHubReleaseParser.resolveEndpointUrl("https://github.com/owner/repo"),
    )
  }

  @Test
  fun `selective single multi and bulk deletion removes chosen records across all recorders`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    try {
      // Seed 3 call logs
      val c1 = CallLogEntity(
        id = "DEV:1:1000", deviceId = "DEV", callLogId = 1L,
        phoneNumber = "111", callType = "INCOMING", callDate = "2026-11-26", callTime = "10:00:00",
        durationSeconds = 10L, timestampMillis = 1000L,
      )
      val c2 = c1.copy(id = "DEV:2:2000", callLogId = 2L, timestampMillis = 2000L)
      val c3 = c1.copy(id = "DEV:3:3000", callLogId = 3L, timestampMillis = 3000L)
      db.callLogDao().upsertCallLogs(listOf(c1, c2, c3))

      // Single delete
      assertEquals(1, db.callLogDao().deleteCallLogsByIds(listOf("DEV:1:1000")))
      assertEquals(2, db.callLogDao().getCallLogsForDeviceOnce("DEV").size)

      // Multi delete (remaining 2)
      assertEquals(2, db.callLogDao().deleteCallLogsByIds(listOf("DEV:2:2000", "DEV:3:3000")))
      assertEquals(0, db.callLogDao().getCallLogsForDeviceOnce("DEV").size)

      // Seed 2 unlock events and test multi delete
      val u1 = com.example.data.local.DeviceUnlockEventEntity(
        id = "DEV:1000:LIVE", deviceId = "DEV", unlockMethod = "Biometric",
        unlockCategory = "BIOMETRIC_ENROLLED", unlockDate = "2026-11-26",
        unlockTime = "10:00:00", timestampMillis = 1000L, source = "BROADCAST_USER_PRESENT",
      )
      val u2 = u1.copy(id = "DEV:2000:LIVE", timestampMillis = 2000L)
      db.unlockEventDao().upsertUnlockEvents(listOf(u1, u2))
      assertEquals(2, db.unlockEventDao().deleteUnlockEventsByIds(listOf(u1.id, u2.id)))
      assertEquals(0, db.unlockEventDao().getUnlockEventsForDeviceOnce("DEV").size)

      // Seed 2 app activity events and delete 1
      db.appActivityDao().insertEvents(
        listOf(
          AppActivityEventEntity(
            id = 10L, deviceId = "DEV", appName = "A", packageName = "com.a",
            versionName = "1.0", eventType = "INSTALLED", installerPackage = null,
            installerLabel = "Unknown", eventDate = "2026-11-26", eventTime = "10:00:00",
            timestampMillis = 1000L,
          ),
          AppActivityEventEntity(
            id = 20L, deviceId = "DEV", appName = "B", packageName = "com.b",
            versionName = "1.0", eventType = "UNINSTALLED", installerPackage = null,
            installerLabel = "Unknown", eventDate = "2026-11-26", eventTime = "10:01:00",
            timestampMillis = 2000L,
          ),
        )
      )
      assertEquals(1, db.appActivityDao().deleteEventsByIds(listOf(10L)))
      assertEquals(1, db.appActivityDao().getEventsForDeviceOnce("DEV").size)
    } finally {
      db.close()
    }
  }

  @Test
  fun `googleWebClientId resolves at runtime without compile-time R dependency`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val clientId = context.googleWebClientId()
    assertTrue(clientId == null || clientId.contains(".apps.googleusercontent.com"))
  }

  @Test
  fun `auth helpers and auto sign-in fail safely when firebase or google config is missing`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val hadApps = com.google.firebase.FirebaseApp.getApps(context).isNotEmpty()
    try {
      com.google.firebase.FirebaseApp.getApps(context).forEach { app -> runCatching { app.delete() } }
      val credentialManager = androidx.credentials.CredentialManager.create(context)
      var unauthCalled = false
      com.example.ui.auth.attemptAutoSignIn(
        context = context,
        credentialManager = credentialManager,
        onAuthSuccess = {},
        onUnauthenticated = { unauthCalled = true },
        scope = backgroundScope,
      )
      val user = com.example.ui.auth.safeCurrentFirebaseUser(context)
      assertTrue(user == null)
      assertTrue(unauthCalled || com.example.ui.auth.isFirebaseInitialized(context))

      val missingClientContext =
        object : android.content.ContextWrapper(context) {
          override fun getPackageName(): String = "com.example.nonexistent"
        }
      var authError: String? = null
      com.example.ui.auth.onGoogleSignInClicked(
        context = missingClientContext,
        credentialManager = credentialManager,
        onAuthSuccess = {},
        onAuthError = { authError = it },
        scope = backgroundScope,
      )
      assertTrue(!authError.isNullOrBlank())
    } finally {
      if (hadApps && com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
        runCatching { com.google.firebase.FirebaseApp.initializeApp(context) }
      }
    }
  }

  @Test
  fun `initial provisioning is idempotent across repeated calls and preserves guest mode`() = runTest {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = SettingsStore(context)

    store.ensureInitialProvisioning()
    val firstId = store.getLocalDeviceId()
    val firstName = store.getLocalDeviceName()
    assertTrue(firstId.startsWith("DEV-"))
    assertTrue(firstName.isNotBlank())

    store.ensureInitialProvisioning()
    assertEquals(firstId, store.getLocalDeviceId())

    store.setGuestMode(true)
    assertTrue(store.guestModeFlow.first())
    store.setGuestMode(false)
    assertFalse(store.guestModeFlow.first())
  }
}
