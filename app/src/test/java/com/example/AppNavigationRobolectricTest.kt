package com.example

import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import androidx.activity.compose.setContent
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
import androidx.lifecycle.ViewModelProvider
import com.example.connectivity.UpdateCheckResult
import com.example.ui.HomeUpdateFeedback
import com.example.ui.MainViewModel
import com.example.ui.SafeAndroidTextToolbar
import com.example.ui.screens.AdminDashboardScreen
import com.example.ui.screens.HomeCalendarScreen
import com.example.ui.screens.modules.CallLogRecordsScreen
import com.example.ui.theme.DeviceRecorderTheme
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
  fun `main activity shows google auth gate on launch and guest button enters home`() {
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("google_auth_gate_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("continue_with_guest_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_user_avatar_button").assertIsDisplayed()
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

    toolbar.showMenu(Rect(0f, 0f, 10f, 10f), onPasteRequested = {})
    assertEquals(TextToolbarStatus.Shown, toolbar.status)
    toolbar.actionModeCallback.onDestroyActionMode(fakeMode)
    assertEquals(TextToolbarStatus.Hidden, toolbar.status)
    toolbar.hide()
    assertEquals(0, finishCount)

    toolbar.showMenu(Rect(0f, 0f, 10f, 10f), onCopyRequested = {})
    toolbar.hide()
    toolbar.hide()
    assertEquals(1, finishCount)
  }

  @Test
  fun `home calendar shows user avatar, drawer order, and in-place update popup`() {
    composeTestRule.waitForIdle()
    val vm = ViewModelProvider(composeTestRule.activity)[MainViewModel::class.java]
    val feedbackField = MainViewModel::class.java.getDeclaredField("_homeUpdateFeedback")
    feedbackField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val feedbackFlow = feedbackField.get(vm) as MutableStateFlow<HomeUpdateFeedback>

    val checkField = MainViewModel::class.java.getDeclaredField("_isCheckingUpdates")
    checkField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val checkingFlow = checkField.get(vm) as MutableStateFlow<Boolean>

    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        DeviceRecorderTheme {
          HomeCalendarScreen(
            viewModel = vm,
            onOpenAdminPanel = {},
            onOpenCheckUpdates = {
              checkingFlow.value = false
              feedbackFlow.value = HomeUpdateFeedback.AlreadyUpdated
            },
            updateCheckResult = UpdateCheckResult.NotChecked,
            isCheckingUpdates = false,
            homeUpdateFeedback = HomeUpdateFeedback.AlreadyUpdated,
          )
        }
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_user_avatar_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("home_circular_update_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("calendar_selected_date").performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag("hamburger_menu_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("drawer_user_profile_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("theme_system").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("theme_light").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("theme_dark").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("drawer_update_option").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("drawer_admin_panel").performScrollTo().assertIsDisplayed()

    // Tapping Check for Updates triggers in-place check and shows popup without opening new screen
    composeTestRule.onNodeWithTag("drawer_update_option").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_update_popup_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithTag("home_update_popup_ok").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_update_popup_dialog").assertDoesNotExist()
  }

  @Test
  fun `admin dashboard removes remote app management and duplicate modules`() {
    composeTestRule.waitForIdle()
    val vm = ViewModelProvider(composeTestRule.activity)[MainViewModel::class.java]
    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        DeviceRecorderTheme {
          AdminDashboardScreen(
            viewModel = vm,
            onNavigateModule = {},
            onLogout = {},
          )
        }
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("dashboard_telemetry_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("mod_remote_apps").assertDoesNotExist()
    composeTestRule.onNodeWithTag("mod_device_connections").assertDoesNotExist()
    composeTestRule.onNodeWithTag("mod_backup_restore").assertDoesNotExist()
    composeTestRule.onNodeWithTag("mod_language").assertDoesNotExist()
    composeTestRule.onNodeWithTag("mod_theme").assertDoesNotExist()

    composeTestRule.onNodeWithTag("dashboard_grid").performScrollToNode(hasTestTag("mod_app_activity"))
    composeTestRule.onNodeWithTag("mod_app_activity").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_grid").performScrollToNode(hasTestTag("mod_call_logs"))
    composeTestRule.onNodeWithTag("mod_call_logs").assertIsDisplayed()
    composeTestRule.onNodeWithTag("dashboard_grid").performScrollToNode(hasTestTag("mod_data_management"))
    composeTestRule.onNodeWithTag("mod_data_management").assertIsDisplayed()
  }

  @Test
  fun `call log screen shows access required state when permission is denied`() {
    composeTestRule.waitForIdle()
    val vm = ViewModelProvider(composeTestRule.activity)[MainViewModel::class.java]
    composeTestRule.activityRule.scenario.onActivity { activity ->
      activity.setContent {
        DeviceRecorderTheme {
          CallLogRecordsScreen(
            viewModel = vm,
            onBack = {},
          )
        }
      }
    }
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("call_log_access_required_card").fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag("call_log_access_required_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("Access Required").assertIsDisplayed()
    composeTestRule.onNodeWithTag("grant_call_log_perm").assertIsDisplayed()
    composeTestRule.onNodeWithTag("open_app_settings_call_log").assertIsDisplayed()
    composeTestRule.onNodeWithTag("call_logs_sync").assertDoesNotExist()
    composeTestRule.onNodeWithTag("call_log_records_list").assertDoesNotExist()
  }
}
