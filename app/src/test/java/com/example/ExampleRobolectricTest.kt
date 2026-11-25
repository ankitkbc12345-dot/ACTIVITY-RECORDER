package com.example

import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.preferences.LoginOutcome
import com.example.data.preferences.PasswordChangeOutcome
import com.example.data.preferences.SettingsStore
import com.example.security.CryptoManager
import com.example.ui.SafeAndroidTextToolbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

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
  fun `manual admin login and current-password change work offline with zero recovery codes`() = runBlocking {
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
  fun `home screen renders minimal live calendar and two drawer options`() {
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("calendar_selected_date").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("hamburger_menu_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("drawer_admin_panel").assertIsDisplayed()
    composeTestRule.onNodeWithTag("drawer_check_updates").assertIsDisplayed()
  }

  @Test
  fun `end to end navigation across updates admin login all 12 modules language switch and logout`() {
    composeTestRule.waitForIdle()

    // 1. Check for Updates from Home drawer (unauthenticated)
    composeTestRule.onNodeWithTag("hamburger_menu_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("drawer_check_updates").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("updates_check_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("updates_back_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("calendar_selected_date").performScrollTo().assertIsDisplayed()

    // 2. Navigate to Admin Login, verify zero recovery UI & blank manual fields
    composeTestRule.onNodeWithTag("hamburger_menu_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("drawer_admin_panel").performClick()
    composeTestRule.waitForIdle()

    assertEquals(
      0,
      composeTestRule.onAllNodesWithText("Recover", substring = true, ignoreCase = true)
        .fetchSemanticsNodes().size,
    )
    composeTestRule.onNodeWithTag("login_admin_id_field").performScrollTo().performTextInput(BuildConfig.ADMIN_ID)
    composeTestRule.onNodeWithTag("login_password_field").performScrollTo().performTextInput(BuildConfig.INITIAL_ADMIN_PASSWORD)
    composeTestRule.onNodeWithTag("login_submit_button").performScrollTo().performClick()
    composeTestRule.waitUntil(timeoutMillis = 10_000L) {
      composeTestRule.onAllNodesWithTag("dashboard_logout_button").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.waitForIdle()

    // Verify Admin Dashboard opened
    composeTestRule.onNodeWithTag("dashboard_logout_button").assertIsDisplayed()

    // 3. Visit every Admin module and verify back returns to Admin Dashboard without crashing
    val moduleVisits =
      listOf(
        "mod_device_selection" to "device_sel_back",
        "mod_app_activity" to "app_activity_back",
        "mod_call_logs" to "call_logs_back",
        "mod_recording_control" to "recording_back",
        "mod_device_connections" to "connections_back",
        "mod_remote_apps" to "remote_apps_back",
        "mod_export_pdf" to "export_pdf_back",
        "mod_backup_restore" to "backup_back",
        "mod_data_management" to "data_mgmt_back",
        "mod_language" to "language_back",
        "mod_security" to "security_back",
        "mod_internet_config" to "internet_cfg_back",
      )

    for ((moduleTag, backTag) in moduleVisits) {
      composeTestRule.onNodeWithTag("dashboard_grid").performScrollToNode(hasTestTag(moduleTag))
      composeTestRule.onNodeWithTag(moduleTag).performClick()
      composeTestRule.waitForIdle()

      // Extra in-module interactions that previously posed crash or layout bugs
      if (moduleTag == "mod_security") {
        assertEquals(
          0,
          composeTestRule.onAllNodesWithText("Recovery", substring = true, ignoreCase = true)
            .fetchSemanticsNodes().size,
        )
      }
      if (moduleTag == "mod_language") {
        composeTestRule.onNodeWithTag("lang_option_ar").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("lang_option_en").performClick()
        composeTestRule.waitForIdle()
      }
      if (moduleTag == "mod_app_activity") {
        composeTestRule.onNodeWithTag("tab_events").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("tab_inventory").performClick()
        composeTestRule.waitForIdle()
      }

      composeTestRule.onNodeWithTag(backTag).assertIsDisplayed().performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("dashboard_logout_button").assertIsDisplayed()
    }

    // 4. Logout cleanly back to Home Calendar
    composeTestRule.onNodeWithTag("dashboard_logout_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("calendar_selected_date").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun `safe text toolbar never calls finish twice or after framework destroy`() {
    val hostView = composeTestRule.activity.window.decorView
    var finishCount = 0
    lateinit var fakeMode: ActionMode
    val toolbar =
      SafeAndroidTextToolbar(
        view = hostView,
        startFloatingActionMode = { _, _ ->
          fakeMode =
            object : ActionMode() {
              override fun setTitle(title: CharSequence?) {}
              override fun setTitle(resId: Int) {}
              override fun setSubtitle(subtitle: CharSequence?) {}
              override fun setSubtitle(resId: Int) {}
              override fun setCustomView(view: View?) {}
              override fun invalidate() {}
              override fun finish() {
                finishCount += 1
              }
              override fun getMenu(): Menu? = null
              override fun getTitle(): CharSequence? = null
              override fun getSubtitle(): CharSequence? = null
              override fun getCustomView(): View? = null
              override fun getMenuInflater(): MenuInflater? = null
            }
          fakeMode
        },
      )

    // Case 1: framework destroys ActionMode first, then Compose calls hide()
    toolbar.showMenu(Rect(0f, 0f, 10f, 10f), onPasteRequested = {})
    assertEquals(TextToolbarStatus.Shown, toolbar.status)
    toolbar.actionModeCallback.onDestroyActionMode(fakeMode)
    assertEquals(TextToolbarStatus.Hidden, toolbar.status)
    toolbar.hide()
    assertEquals(0, finishCount)

    // Case 2: Compose calls hide() twice -> finish() invoked only once
    toolbar.showMenu(Rect(0f, 0f, 10f, 10f), onCopyRequested = {})
    toolbar.hide()
    toolbar.hide()
    assertEquals(1, finishCount)
  }

  @Test
  fun `admin login screen change password requires previous id and password then logs in with new password`() {
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("hamburger_menu_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("drawer_admin_panel").performClick()
    composeTestRule.waitForIdle()

    // Tap Change Password option below Login
    composeTestRule.onNodeWithTag("login_change_password_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    // Wrong Admin ID is rejected
    composeTestRule.onNodeWithTag("login_change_pw_admin_id").performScrollTo().performTextInput("WRONG_ID")
    composeTestRule.onNodeWithTag("login_change_pw_current").performScrollTo().performTextInput(BuildConfig.INITIAL_ADMIN_PASSWORD)
    composeTestRule.onNodeWithTag("login_change_pw_new").performScrollTo().performTextInput("UpdatedPass#2026")
    composeTestRule.onNodeWithTag("login_change_pw_confirm").performScrollTo().performTextInput("UpdatedPass#2026")
    composeTestRule.onNodeWithTag("login_change_pw_submit").performScrollTo().performClick()
    composeTestRule.waitUntil(timeoutMillis = 10_000L) {
      composeTestRule.onAllNodesWithText("Invalid Admin ID or current password", substring = true)
        .fetchSemanticsNodes().isNotEmpty()
    }

    // Re-open clean form with correct Admin ID + Current Password
    composeTestRule.onNodeWithTag("login_change_pw_cancel").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("login_change_password_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("login_change_pw_admin_id").performScrollTo().performTextInput(BuildConfig.ADMIN_ID)
    composeTestRule.onNodeWithTag("login_change_pw_current").performScrollTo().performTextInput(BuildConfig.INITIAL_ADMIN_PASSWORD)
    composeTestRule.onNodeWithTag("login_change_pw_new").performScrollTo().performTextInput("UpdatedPass#2026")
    composeTestRule.onNodeWithTag("login_change_pw_confirm").performScrollTo().performTextInput("UpdatedPass#2026")
    composeTestRule.onNodeWithTag("login_change_pw_submit").performScrollTo().performClick()

    composeTestRule.waitUntil(timeoutMillis = 10_000L) {
      composeTestRule.onAllNodesWithTag("login_admin_id_field").fetchSemanticsNodes().isNotEmpty()
    }

    // Sign in with new password and verify dashboard opens + logout works
    composeTestRule.onNodeWithTag("login_admin_id_field").performScrollTo().performTextInput(BuildConfig.ADMIN_ID)
    composeTestRule.onNodeWithTag("login_password_field").performScrollTo().performTextInput("UpdatedPass#2026")
    composeTestRule.onNodeWithTag("login_submit_button").performScrollTo().performClick()

    composeTestRule.waitUntil(timeoutMillis = 10_000L) {
      composeTestRule.onAllNodesWithTag("dashboard_logout_button").fetchSemanticsNodes().isNotEmpty()
    }

    // Open Security Settings and restore initial password so shared DataStore stays clean
    composeTestRule.onNodeWithTag("dashboard_grid").performScrollToNode(hasTestTag("mod_security"))
    composeTestRule.onNodeWithTag("mod_security").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Manual Logout Only", substring = true).performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("change_pw_admin_id").performScrollTo().performTextInput(BuildConfig.ADMIN_ID)
    composeTestRule.onNodeWithTag("change_pw_current").performScrollTo().performTextInput("UpdatedPass#2026")
    composeTestRule.onNodeWithTag("change_pw_new").performScrollTo().performTextInput(BuildConfig.INITIAL_ADMIN_PASSWORD)
    composeTestRule.onNodeWithTag("change_pw_confirm").performScrollTo().performTextInput(BuildConfig.INITIAL_ADMIN_PASSWORD)
    composeTestRule.onNodeWithTag("change_pw_submit").performScrollTo().performClick()
    composeTestRule.waitUntil(timeoutMillis = 10_000L) {
      composeTestRule.onAllNodesWithText("Admin password updated securely.", substring = true)
        .fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("security_back").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("dashboard_logout_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("calendar_selected_date").performScrollTo().assertIsDisplayed()
  }
}
