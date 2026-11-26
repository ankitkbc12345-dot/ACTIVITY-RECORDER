package com.example

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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import com.example.connectivity.UpdateCheckResult
import com.example.ui.HomeUpdateFeedback
import com.example.ui.MainViewModel
import com.example.ui.SafeAndroidTextToolbar
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppNavigationRobolectricTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun `home screen shows circular update icon and drawer keeps only admin panel`() {
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_circular_update_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("calendar_selected_date").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("hamburger_menu_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("drawer_admin_panel").assertIsDisplayed()
    composeTestRule.onNodeWithTag("drawer_check_updates").assertDoesNotExist()
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
  fun `home screen shows red update banner when newer release is available`() {
    composeTestRule.waitForIdle()
    val vm = ViewModelProvider(composeTestRule.activity)[MainViewModel::class.java]
    val field = MainViewModel::class.java.getDeclaredField("_updateCheckResult")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val stateFlow = field.get(vm) as MutableStateFlow<UpdateCheckResult>
    stateFlow.value =
      UpdateCheckResult.Checked(
        latestVersionName = "1.0.9",
        latestVersionCode = 10,
        updateAvailable = true,
        releaseNotes = "versionCode: 10",
        apkDownloadUrl = "https://example.com/app.apk",
        apkAssetName = "app.apk",
        apkSizeBytes = 1024L,
      )
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_update_banner").assertIsDisplayed()
  }

  @Test
  fun `home screen shows and dismisses already updated status chip`() {
    composeTestRule.waitForIdle()
    val vm = ViewModelProvider(composeTestRule.activity)[MainViewModel::class.java]
    val field = MainViewModel::class.java.getDeclaredField("_homeUpdateFeedback")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val stateFlow = field.get(vm) as MutableStateFlow<HomeUpdateFeedback>
    stateFlow.value = HomeUpdateFeedback.AlreadyUpdated
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_update_status_chip").assertIsDisplayed()
    composeTestRule.onNodeWithText("Already Updated", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("home_update_status_dismiss").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_update_status_chip").assertDoesNotExist()
  }

  @Test
  fun `call log screen shows access required state when permission is denied`() {
    val appContext = composeTestRule.activity.applicationContext
    val vm = ViewModelProvider(composeTestRule.activity)[MainViewModel::class.java]
    kotlinx.coroutines.runBlocking {
      com.example.data.preferences.SettingsStore(appContext).setAdminLoggedIn(false)
    }
    try {
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("hamburger_menu_button").performClick()
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("drawer_admin_panel").performClick()

      composeTestRule.waitUntil(timeoutMillis = 5_000L) {
        composeTestRule.onAllNodesWithTag("login_admin_id_field").fetchSemanticsNodes().isNotEmpty()
      }
      composeTestRule.onNodeWithTag("login_admin_id_field").performTextInput(BuildConfig.ADMIN_ID)
      composeTestRule.onNodeWithTag("login_password_field").performTextInput(BuildConfig.INITIAL_ADMIN_PASSWORD)
      composeTestRule.onNodeWithTag("login_submit_button").performClick()

      composeTestRule.waitUntil(timeoutMillis = 10_000L) {
        composeTestRule.onAllNodesWithTag("dashboard_grid").fetchSemanticsNodes().isNotEmpty()
      }
      composeTestRule.onNodeWithTag("dashboard_grid").performScrollToNode(hasTestTag("mod_call_logs"))
      composeTestRule.onNodeWithTag("mod_call_logs").performClick()

      composeTestRule.waitUntil(timeoutMillis = 5_000L) {
        composeTestRule.onAllNodesWithTag("call_log_access_required_card").fetchSemanticsNodes().isNotEmpty()
      }
      composeTestRule.onNodeWithTag("call_log_access_required_card").assertIsDisplayed()
      composeTestRule.onNodeWithText("Access Required").assertIsDisplayed()
      composeTestRule.onNodeWithTag("grant_call_log_perm").assertIsDisplayed()
      composeTestRule.onNodeWithTag("open_app_settings_call_log").assertIsDisplayed()
      composeTestRule.onNodeWithTag("call_logs_sync").assertDoesNotExist()
      composeTestRule.onNodeWithTag("call_log_records_list").assertDoesNotExist()
    } finally {
      vm.logout()
      kotlinx.coroutines.runBlocking {
        com.example.data.preferences.SettingsStore(appContext).setAdminLoggedIn(false)
      }
      composeTestRule.waitForIdle()
    }
  }
}
