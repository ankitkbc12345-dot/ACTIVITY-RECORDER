package com.example

import android.view.ActionMode
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.ui.SafeAndroidTextToolbar
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
  fun `home screen renders minimal live calendar and two drawer options`() {
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("calendar_selected_date").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("hamburger_menu_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("drawer_admin_panel").assertIsDisplayed()
    composeTestRule.onNodeWithTag("drawer_check_updates").assertIsDisplayed()
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
    val vm = androidx.lifecycle.ViewModelProvider(composeTestRule.activity)[com.example.ui.MainViewModel::class.java]
    val field = com.example.ui.MainViewModel::class.java.getDeclaredField("_updateCheckResult")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val stateFlow =
      field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<com.example.connectivity.UpdateCheckResult>
    stateFlow.value =
      com.example.connectivity.UpdateCheckResult.Checked(
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
}
