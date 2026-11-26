package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import com.example.security.CryptoManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pdr_settings")

data class RecordingSettings(
  val masterEnabled: Boolean = true,
  val appTrackingEnabled: Boolean = true,
  val callLogEnabled: Boolean = true,
  val unlockTrackingEnabled: Boolean = true,
)

enum class CloudSyncSchedule {
  MANUAL,
  ALWAYS,
  EVERY_1_MIN,
  EVERY_1_HOUR,
  DAILY;

  companion object {
    fun fromRaw(raw: String?): CloudSyncSchedule =
      entries.firstOrNull { it.name == raw } ?: ALWAYS
  }
}

data class CloudSyncPreferences(
  val schedule: CloudSyncSchedule = CloudSyncSchedule.ALWAYS,
  val dailyHour: Int = 22,
  val dailyMinute: Int = 0,
  val syncCallLogs: Boolean = true,
  val syncAppActivity: Boolean = true,
  val syncUnlockLogs: Boolean = true,
  val lastCloudSyncMillis: Long? = null,
  val lastCloudSyncStatus: String = "Ready",
)

data class SecuritySettingsState(
  val adminId: String = "",
  val failedAttempts: Int = 0,
  val lockoutUntilMillis: Long = 0L,
  val inactivityTimeoutMinutes: Int = 0,
  val lastLoginTimestampMillis: Long? = null,
)

data class InternetConnectivityConfig(
  val signalingUrl: String = DEFAULT_SIGNALING_URL,
  val stunUri: String = DEFAULT_STUN_URI,
  val turnUri: String = "",
  val turnUsername: String = "",
  val turnCredential: String = "",
  val connectionToken: String = "",
  val updateManifestUrl: String = DEFAULT_UPDATE_MANIFEST_URL,
  val isAutoConfigured: Boolean = true,
) {
  val isConfigured: Boolean
    get() = signalingUrl.isNotBlank() || stunUri.isNotBlank()

  companion object {
    const val DEFAULT_SIGNALING_URL = "wss://echo.websocket.events"
    const val DEFAULT_STUN_URI = "stun:stun.l.google.com:19302"
    val DEFAULT_UPDATE_MANIFEST_URL: String
      get() = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"
  }
}

class SettingsStore(private val context: Context) {

  private object Keys {
    val LOCAL_DEVICE_ID = stringPreferencesKey("local_device_id")
    val LOCAL_DEVICE_NAME = stringPreferencesKey("local_device_name")

    val ADMIN_ID = stringPreferencesKey("admin_id")
    val ADMIN_PASSWORD_HASH = stringPreferencesKey("admin_password_hash")
    val PROVISIONED_FINGERPRINT = stringPreferencesKey("provisioned_fingerprint")
    val PASSWORD_USER_CHANGED = booleanPreferencesKey("password_user_changed")
    val FAILED_ATTEMPTS = intPreferencesKey("failed_attempts")
    val LOCKOUT_UNTIL_MILLIS = longPreferencesKey("lockout_until_millis")
    val INACTIVITY_TIMEOUT_MINUTES = intPreferencesKey("inactivity_timeout_minutes")
    val LAST_LOGIN_MILLIS = longPreferencesKey("last_login_millis")
    val IS_ADMIN_LOGGED_IN = booleanPreferencesKey("is_admin_logged_in")

    val MASTER_RECORDING = booleanPreferencesKey("master_recording")
    val APP_TRACKING = booleanPreferencesKey("app_tracking")
    val CALL_LOG_RECORDING = booleanPreferencesKey("call_log_recording")
    val UNLOCK_TRACKING = booleanPreferencesKey("unlock_tracking")

    val CLOUD_SYNC_SCHEDULE = stringPreferencesKey("cloud_sync_schedule")
    val CLOUD_SYNC_DAILY_HOUR = intPreferencesKey("cloud_sync_daily_hour")
    val CLOUD_SYNC_DAILY_MINUTE = intPreferencesKey("cloud_sync_daily_minute")
    val CLOUD_SYNC_CALLS = booleanPreferencesKey("cloud_sync_calls")
    val CLOUD_SYNC_APPS = booleanPreferencesKey("cloud_sync_apps")
    val CLOUD_SYNC_UNLOCKS = booleanPreferencesKey("cloud_sync_unlocks")
    val CLOUD_LAST_SYNC_MILLIS = longPreferencesKey("cloud_last_sync_millis")
    val CLOUD_LAST_SYNC_STATUS = stringPreferencesKey("cloud_last_sync_status")

    val LANGUAGE_CODE = stringPreferencesKey("language_code")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val REMOTE_UNINSTALL_MODE = stringPreferencesKey("remote_uninstall_mode")

    val SIGNALING_URL_ENC = stringPreferencesKey("signaling_url_enc")
    val STUN_URI = stringPreferencesKey("stun_uri")
    val TURN_URI = stringPreferencesKey("turn_uri")
    val TURN_USERNAME_ENC = stringPreferencesKey("turn_username_enc")
    val TURN_CREDENTIAL_ENC = stringPreferencesKey("turn_credential_enc")
    val CONNECTION_TOKEN_ENC = stringPreferencesKey("connection_token_enc")
    val UPDATE_MANIFEST_URL = stringPreferencesKey("update_manifest_url")
  }

  val recordingSettingsFlow: Flow<RecordingSettings> =
    context.dataStore.data.map { prefs ->
      RecordingSettings(
        masterEnabled = prefs[Keys.MASTER_RECORDING] ?: true,
        appTrackingEnabled = prefs[Keys.APP_TRACKING] ?: true,
        callLogEnabled = prefs[Keys.CALL_LOG_RECORDING] ?: true,
        unlockTrackingEnabled = prefs[Keys.UNLOCK_TRACKING] ?: true,
      )
    }

  val cloudSyncPreferencesFlow: Flow<CloudSyncPreferences> =
    context.dataStore.data.map { prefs ->
      CloudSyncPreferences(
        schedule = CloudSyncSchedule.fromRaw(prefs[Keys.CLOUD_SYNC_SCHEDULE]),
        dailyHour = (prefs[Keys.CLOUD_SYNC_DAILY_HOUR] ?: 22).coerceIn(0, 23),
        dailyMinute = (prefs[Keys.CLOUD_SYNC_DAILY_MINUTE] ?: 0).coerceIn(0, 59),
        syncCallLogs = prefs[Keys.CLOUD_SYNC_CALLS] ?: true,
        syncAppActivity = prefs[Keys.CLOUD_SYNC_APPS] ?: true,
        syncUnlockLogs = prefs[Keys.CLOUD_SYNC_UNLOCKS] ?: true,
        lastCloudSyncMillis = prefs[Keys.CLOUD_LAST_SYNC_MILLIS],
        lastCloudSyncStatus = prefs[Keys.CLOUD_LAST_SYNC_STATUS] ?: "Ready",
      )
    }

  val securitySettingsFlow: Flow<SecuritySettingsState> =
    context.dataStore.data.map { prefs ->
      SecuritySettingsState(
        adminId = prefs[Keys.ADMIN_ID] ?: BuildConfig.ADMIN_ID,
        failedAttempts = prefs[Keys.FAILED_ATTEMPTS] ?: 0,
        lockoutUntilMillis = prefs[Keys.LOCKOUT_UNTIL_MILLIS] ?: 0L,
        inactivityTimeoutMinutes = prefs[Keys.INACTIVITY_TIMEOUT_MINUTES] ?: 0,
        lastLoginTimestampMillis = prefs[Keys.LAST_LOGIN_MILLIS],
      )
    }

  val isAdminLoggedInFlow: Flow<Boolean> =
    context.dataStore.data.map { prefs -> prefs[Keys.IS_ADMIN_LOGGED_IN] ?: false }

  val languageCodeFlow: Flow<String> =
    context.dataStore.data.map { prefs -> prefs[Keys.LANGUAGE_CODE] ?: "en" }

  val themeModeFlow: Flow<String> =
    context.dataStore.data.map { prefs -> prefs[Keys.THEME_MODE] ?: "light" }

  val remoteUninstallModeFlow: Flow<String> =
    context.dataStore.data.map { prefs -> prefs[Keys.REMOTE_UNINSTALL_MODE] ?: "MODE_B_INTERACTIVE" }

  val internetConfigFlow: Flow<InternetConnectivityConfig> =
    context.dataStore.data.map { prefs ->
      val savedSignaling = prefs[Keys.SIGNALING_URL_ENC]?.let { CryptoManager.decryptLocalString(context, it) }
      val savedStun = prefs[Keys.STUN_URI]
      val savedUpdate = prefs[Keys.UPDATE_MANIFEST_URL]

      InternetConnectivityConfig(
        signalingUrl =
          if (!savedSignaling.isNullOrBlank()) savedSignaling else InternetConnectivityConfig.DEFAULT_SIGNALING_URL,
        stunUri =
          if (!savedStun.isNullOrBlank()) savedStun else InternetConnectivityConfig.DEFAULT_STUN_URI,
        turnUri = prefs[Keys.TURN_URI] ?: "",
        turnUsername =
          prefs[Keys.TURN_USERNAME_ENC]?.let { CryptoManager.decryptLocalString(context, it) } ?: "",
        turnCredential =
          prefs[Keys.TURN_CREDENTIAL_ENC]?.let { CryptoManager.decryptLocalString(context, it) }
            ?: "",
        connectionToken =
          prefs[Keys.CONNECTION_TOKEN_ENC]?.let { CryptoManager.decryptLocalString(context, it) }
            ?: "",
        updateManifestUrl =
          if (!savedUpdate.isNullOrBlank()) savedUpdate else InternetConnectivityConfig.DEFAULT_UPDATE_MANIFEST_URL,
        isAutoConfigured = savedSignaling.isNullOrBlank() && savedStun.isNullOrBlank(),
      )
    }

  suspend fun ensureInitialProvisioning() {
    val buildAdminId = BuildConfig.ADMIN_ID
    val buildInitialPass = BuildConfig.INITIAL_ADMIN_PASSWORD
    val fingerprint = CryptoManager.sha256Hex("$buildAdminId:$buildInitialPass")

    context.dataStore.edit { prefs ->
      if (prefs[Keys.LOCAL_DEVICE_ID].isNullOrBlank()) {
        prefs[Keys.LOCAL_DEVICE_ID] = "DEV-${UUID.randomUUID().toString().take(8).uppercase()}"
      }
      if (prefs[Keys.LOCAL_DEVICE_NAME].isNullOrBlank()) {
        prefs[Keys.LOCAL_DEVICE_NAME] = CryptoManager.getLocalDeviceModel().ifBlank { "This Device" }
      }

      prefs[Keys.ADMIN_ID] = buildAdminId

      val existingHash = prefs[Keys.ADMIN_PASSWORD_HASH]
      val userChanged = prefs[Keys.PASSWORD_USER_CHANGED] ?: false
      val prevFingerprint = prefs[Keys.PROVISIONED_FINGERPRINT]

      if (existingHash.isNullOrBlank() || (!userChanged && prevFingerprint != fingerprint)) {
        prefs[Keys.ADMIN_PASSWORD_HASH] = CryptoManager.hashPassword(buildInitialPass)
        prefs[Keys.PROVISIONED_FINGERPRINT] = fingerprint
        prefs[Keys.FAILED_ATTEMPTS] = 0
        prefs[Keys.LOCKOUT_UNTIL_MILLIS] = 0L
      }
    }
  }

  suspend fun getLocalDeviceId(): String {
    ensureInitialProvisioning()
    return context.dataStore.data.first()[Keys.LOCAL_DEVICE_ID] ?: "DEV-LOCAL"
  }

  suspend fun getLocalDeviceName(): String {
    ensureInitialProvisioning()
    return context.dataStore.data.first()[Keys.LOCAL_DEVICE_NAME] ?: CryptoManager.getLocalDeviceModel()
  }

  suspend fun setLocalDeviceName(name: String) {
    context.dataStore.edit { prefs -> prefs[Keys.LOCAL_DEVICE_NAME] = name.trim() }
  }

  suspend fun verifyAdminLogin(inputId: String, inputPassword: String): LoginOutcome =
    withContext(Dispatchers.IO) {
      ensureInitialProvisioning()
      val prefs = context.dataStore.data.first()
      val now = System.currentTimeMillis()
      val lockoutUntil = prefs[Keys.LOCKOUT_UNTIL_MILLIS] ?: 0L
      if (now < lockoutUntil) {
        val remainingSec = ((lockoutUntil - now) / 1000L).toInt().coerceAtLeast(1)
        return@withContext LoginOutcome.LockedOut(remainingSec)
      }

      val storedAdminId = prefs[Keys.ADMIN_ID] ?: BuildConfig.ADMIN_ID
      val storedHash = prefs[Keys.ADMIN_PASSWORD_HASH] ?: ""
      val idMatches = inputId.trim().equals(storedAdminId, ignoreCase = true)
      val passwordMatches =
        storedHash.isNotEmpty() && CryptoManager.verifyPassword(inputPassword, storedHash)

      if (idMatches && passwordMatches) {
        context.dataStore.edit { editPrefs ->
          editPrefs[Keys.FAILED_ATTEMPTS] = 0
          editPrefs[Keys.LOCKOUT_UNTIL_MILLIS] = 0L
          editPrefs[Keys.LAST_LOGIN_MILLIS] = now
          editPrefs[Keys.IS_ADMIN_LOGGED_IN] = true
        }
        LoginOutcome.Success
      } else {
        val newAttempts = (prefs[Keys.FAILED_ATTEMPTS] ?: 0) + 1
        val maxAttempts = 5
        val newLockout = if (newAttempts >= maxAttempts) now + 60_000L else 0L
        context.dataStore.edit { editPrefs ->
          editPrefs[Keys.FAILED_ATTEMPTS] = if (newLockout > 0L) 0 else newAttempts
          editPrefs[Keys.LOCKOUT_UNTIL_MILLIS] = newLockout
        }
        if (newLockout > 0L) {
          LoginOutcome.LockedOut(60)
        } else {
          LoginOutcome.Invalid(remainingAttempts = maxAttempts - newAttempts)
        }
      }
    }

  suspend fun setAdminLoggedIn(loggedIn: Boolean) {
    context.dataStore.edit { prefs -> prefs[Keys.IS_ADMIN_LOGGED_IN] = loggedIn }
  }

  suspend fun changePassword(
    adminId: String,
    currentPassword: String,
    newPassword: String,
  ): PasswordChangeOutcome =
    withContext(Dispatchers.IO) {
      ensureInitialProvisioning()
      val prefs = context.dataStore.data.first()
      val now = System.currentTimeMillis()
      val lockoutUntil = prefs[Keys.LOCKOUT_UNTIL_MILLIS] ?: 0L
      if (now < lockoutUntil) {
        val remainingSec = ((lockoutUntil - now) / 1000L).toInt().coerceAtLeast(1)
        return@withContext PasswordChangeOutcome.LockedOut(remainingSec)
      }
      if (newPassword.length < 6) {
        return@withContext PasswordChangeOutcome.TooShort
      }
      val storedAdminId = prefs[Keys.ADMIN_ID] ?: BuildConfig.ADMIN_ID
      val storedHash = prefs[Keys.ADMIN_PASSWORD_HASH] ?: ""
      val idMatches = adminId.trim().equals(storedAdminId, ignoreCase = true)
      val passwordMatches =
        storedHash.isNotEmpty() && CryptoManager.verifyPassword(currentPassword, storedHash)

      if (!idMatches || !passwordMatches) {
        val newAttempts = (prefs[Keys.FAILED_ATTEMPTS] ?: 0) + 1
        val maxAttempts = 5
        val newLockout = if (newAttempts >= maxAttempts) now + 60_000L else 0L
        context.dataStore.edit { editPrefs ->
          editPrefs[Keys.FAILED_ATTEMPTS] = if (newLockout > 0L) 0 else newAttempts
          editPrefs[Keys.LOCKOUT_UNTIL_MILLIS] = newLockout
        }
        return@withContext if (newLockout > 0L) {
          PasswordChangeOutcome.LockedOut(60)
        } else {
          PasswordChangeOutcome.InvalidCredentials(remainingAttempts = maxAttempts - newAttempts)
        }
      }

      val newHash = CryptoManager.hashPassword(newPassword)
      context.dataStore.edit { editPrefs ->
        editPrefs[Keys.ADMIN_PASSWORD_HASH] = newHash
        editPrefs[Keys.PASSWORD_USER_CHANGED] = true
        editPrefs[Keys.FAILED_ATTEMPTS] = 0
        editPrefs[Keys.LOCKOUT_UNTIL_MILLIS] = 0L
      }
      PasswordChangeOutcome.Success
    }

  suspend fun setInactivityTimeoutMinutes(minutes: Int) {
    context.dataStore.edit { prefs -> prefs[Keys.INACTIVITY_TIMEOUT_MINUTES] = minutes }
  }

  suspend fun setMasterRecording(enabled: Boolean) {
    context.dataStore.edit { prefs -> prefs[Keys.MASTER_RECORDING] = enabled }
  }

  suspend fun setAppTrackingEnabled(enabled: Boolean) {
    context.dataStore.edit { prefs -> prefs[Keys.APP_TRACKING] = enabled }
  }

  suspend fun setCallLogRecordingEnabled(enabled: Boolean) {
    context.dataStore.edit { prefs -> prefs[Keys.CALL_LOG_RECORDING] = enabled }
  }

  suspend fun setUnlockTrackingEnabled(enabled: Boolean) {
    context.dataStore.edit { prefs -> prefs[Keys.UNLOCK_TRACKING] = enabled }
  }

  suspend fun setCloudSyncSchedule(schedule: CloudSyncSchedule) {
    context.dataStore.edit { prefs -> prefs[Keys.CLOUD_SYNC_SCHEDULE] = schedule.name }
  }

  suspend fun setCloudSyncDailyTime(hour: Int, minute: Int) {
    context.dataStore.edit { prefs ->
      prefs[Keys.CLOUD_SYNC_DAILY_HOUR] = hour.coerceIn(0, 23)
      prefs[Keys.CLOUD_SYNC_DAILY_MINUTE] = minute.coerceIn(0, 59)
    }
  }

  suspend fun setCloudSyncDataToggles(
    syncCallLogs: Boolean,
    syncAppActivity: Boolean,
    syncUnlockLogs: Boolean,
  ) {
    context.dataStore.edit { prefs ->
      prefs[Keys.CLOUD_SYNC_CALLS] = syncCallLogs
      prefs[Keys.CLOUD_SYNC_APPS] = syncAppActivity
      prefs[Keys.CLOUD_SYNC_UNLOCKS] = syncUnlockLogs
    }
  }

  suspend fun updateLastCloudSync(timestampMillis: Long, statusMessage: String) {
    context.dataStore.edit { prefs ->
      prefs[Keys.CLOUD_LAST_SYNC_MILLIS] = timestampMillis
      prefs[Keys.CLOUD_LAST_SYNC_STATUS] = statusMessage.take(240)
    }
  }

  suspend fun setLanguageCode(code: String) {
    context.dataStore.edit { prefs -> prefs[Keys.LANGUAGE_CODE] = code }
  }

  suspend fun setThemeMode(mode: String) {
    context.dataStore.edit { prefs -> prefs[Keys.THEME_MODE] = mode }
  }

  suspend fun setRemoteUninstallMode(mode: String) {
    context.dataStore.edit { prefs -> prefs[Keys.REMOTE_UNINSTALL_MODE] = mode }
  }

  suspend fun saveInternetConfig(config: InternetConnectivityConfig) {
    context.dataStore.edit { prefs ->
      prefs[Keys.SIGNALING_URL_ENC] =
        CryptoManager.encryptLocalString(context, config.signalingUrl.trim())
      prefs[Keys.STUN_URI] = config.stunUri.trim()
      prefs[Keys.TURN_URI] = config.turnUri.trim()
      prefs[Keys.TURN_USERNAME_ENC] =
        CryptoManager.encryptLocalString(context, config.turnUsername.trim())
      prefs[Keys.TURN_CREDENTIAL_ENC] =
        CryptoManager.encryptLocalString(context, config.turnCredential.trim())
      prefs[Keys.CONNECTION_TOKEN_ENC] =
        CryptoManager.encryptLocalString(context, config.connectionToken.trim())
      prefs[Keys.UPDATE_MANIFEST_URL] = config.updateManifestUrl.trim()
    }
  }

  suspend fun clearInternetConfig() {
    context.dataStore.edit { prefs ->
      prefs.remove(Keys.SIGNALING_URL_ENC)
      prefs.remove(Keys.STUN_URI)
      prefs.remove(Keys.TURN_URI)
      prefs.remove(Keys.TURN_USERNAME_ENC)
      prefs.remove(Keys.TURN_CREDENTIAL_ENC)
      prefs.remove(Keys.CONNECTION_TOKEN_ENC)
      prefs.remove(Keys.UPDATE_MANIFEST_URL)
    }
  }

  suspend fun getRecordingSettingsOnce(): RecordingSettings = recordingSettingsFlow.first()

  suspend fun getCloudSyncPreferencesOnce(): CloudSyncPreferences = cloudSyncPreferencesFlow.first()

  suspend fun getInternetConfigOnce(): InternetConnectivityConfig = internetConfigFlow.first()
}

sealed interface LoginOutcome {
  data object Success : LoginOutcome

  data class Invalid(val remainingAttempts: Int) : LoginOutcome

  data class LockedOut(val remainingSeconds: Int) : LoginOutcome
}

sealed interface PasswordChangeOutcome {
  data object Success : PasswordChangeOutcome

  data class InvalidCredentials(val remainingAttempts: Int) : PasswordChangeOutcome

  data class LockedOut(val remainingSeconds: Int) : PasswordChangeOutcome

  data object TooShort : PasswordChangeOutcome
}
