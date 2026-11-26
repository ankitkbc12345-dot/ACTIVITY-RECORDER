package com.example

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.MainViewModel
import com.example.ui.NavRoutes
import com.example.ui.ProvideSafeTextToolbar
import com.example.ui.screens.AdminDashboardScreen
import com.example.ui.screens.AdminLoginScreen
import com.example.ui.screens.HomeCalendarScreen
import com.example.ui.screens.UpdatesScreen
import com.example.ui.screens.rememberApkUpdateAction
import com.example.ui.screens.modules.AppActivityRecordsScreen
import com.example.ui.screens.modules.BackupRestoreScreen
import com.example.ui.screens.modules.CallLogRecordsScreen
import com.example.ui.screens.modules.CloudSyncScreen
import com.example.ui.screens.modules.DataManagementScreen
import com.example.ui.screens.modules.DeviceConnectionsScreen
import com.example.ui.screens.modules.DeviceSelectionScreen
import com.example.ui.screens.modules.ExportPdfScreen
import com.example.ui.screens.modules.InternetConfigScreen
import com.example.ui.screens.modules.LanguageSettingsScreen
import com.example.ui.screens.modules.RecordingControlScreen
import com.example.ui.screens.modules.RemoteAppManagementScreen
import com.example.ui.screens.modules.SecuritySettingsScreen
import com.example.ui.theme.DeviceRecorderTheme
import java.util.Locale

private class LocalizedContextWrapper(
  base: Context,
  private val localizedResources: Resources,
) : ContextWrapper(base) {
  override fun getResources(): Resources = localizedResources

  override fun getAssets(): AssetManager = localizedResources.assets
}

class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels()

  override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
    viewModel.recordUserActivity()
    return super.dispatchTouchEvent(ev)
  }

  override fun onResume() {
    super.onResume()
    viewModel.recordUserActivity()
    viewModel.refreshPermissionStates()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val langCode by viewModel.languageCode.collectAsStateWithLifecycle()
      val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
      val isAuthenticated by viewModel.isAdminAuthenticated.collectAsStateWithLifecycle()
      val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
      val updateCheckResult by viewModel.updateCheckResult.collectAsStateWithLifecycle()
      val apkDownloadState by viewModel.apkDownloadState.collectAsStateWithLifecycle()
      val onTriggerApkUpdate = rememberApkUpdateAction(viewModel)

      val systemDark = isSystemInDarkTheme()
      val darkTheme =
        when (themeMode) {
          "dark" -> true
          "light" -> false
          else -> systemDark
        }

      val baseContext = LocalContext.current
      val localizedContext =
        remember(langCode, baseContext) { createLocalizedContext(baseContext, langCode) }
      val layoutDirection =
        if (langCode == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

      val navController = rememberNavController()
      val snackbarHostState = remember { SnackbarHostState() }

      LaunchedEffect(statusBanner) {
        statusBanner?.let { msg ->
          snackbarHostState.showSnackbar(msg)
          viewModel.clearBanner()
        }
      }

      // Automatically exit Admin screens if session locks due to inactivity
      LaunchedEffect(isAuthenticated) {
        if (!isAuthenticated) {
          val currentRoute = navController.currentBackStackEntry?.destination?.route
          if (currentRoute != null &&
            currentRoute != NavRoutes.HOME_CALENDAR &&
            currentRoute != NavRoutes.CHECK_UPDATES &&
            currentRoute != NavRoutes.ADMIN_LOGIN
          ) {
            navController.navigate(NavRoutes.HOME_CALENDAR) {
              popUpTo(NavRoutes.HOME_CALENDAR) { inclusive = false }
              launchSingleTop = true
            }
          }
        }
      }

      CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalLayoutDirection provides layoutDirection,
      ) {
        ProvideSafeTextToolbar {
          DeviceRecorderTheme(darkTheme = darkTheme) {
          Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
          ) { outerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(bottom = outerPadding.calculateBottomPadding())) {
              NavHost(
                navController = navController,
                startDestination = NavRoutes.HOME_CALENDAR,
              ) {
                composable(NavRoutes.HOME_CALENDAR) {
                  HomeCalendarScreen(
                    onOpenAdminPanel = {
                      val target =
                        if (isAuthenticated) NavRoutes.ADMIN_DASHBOARD else NavRoutes.ADMIN_LOGIN
                      navigateSingleTop(navController, target)
                    },
                    onOpenCheckUpdates = {
                      navigateSingleTop(navController, NavRoutes.CHECK_UPDATES)
                    },
                    updateCheckResult = updateCheckResult,
                    apkDownloadState = apkDownloadState,
                    onStartOrRetryUpdate = onTriggerApkUpdate,
                  )
                }

                composable(NavRoutes.CHECK_UPDATES) {
                  UpdatesScreen(
                    viewModel = viewModel,
                    onBack = { popBackSafely(navController) },
                  )
                }

                composable(NavRoutes.ADMIN_LOGIN) {
                  AdminLoginScreen(
                    viewModel = viewModel,
                    onLoginSuccess = {
                      runOnUiThread {
                        navController.navigate(NavRoutes.ADMIN_DASHBOARD) {
                          popUpTo(NavRoutes.ADMIN_LOGIN) { inclusive = true }
                          launchSingleTop = true
                        }
                      }
                    },
                    onBack = { popBackSafely(navController) },
                  )
                }

                composable(NavRoutes.ADMIN_DASHBOARD) {
                  AdminDashboardScreen(
                    viewModel = viewModel,
                    onNavigateModule = { route -> navigateSingleTop(navController, route) },
                    onLogout = {
                      runOnUiThread {
                        navController.navigate(NavRoutes.HOME_CALENDAR) {
                          popUpTo(NavRoutes.HOME_CALENDAR) { inclusive = true }
                          launchSingleTop = true
                        }
                      }
                    },
                  )
                }

                composable(NavRoutes.DEVICE_SELECTION) {
                  DeviceSelectionScreen(
                    viewModel = viewModel,
                    onOpenConnections = { navigateSingleTop(navController, NavRoutes.DEVICE_CONNECTIONS) },
                    onBack = { popBackSafely(navController) },
                  )
                }

                composable(NavRoutes.APP_ACTIVITY) {
                  AppActivityRecordsScreen(
                    viewModel = viewModel,
                    onBack = { popBackSafely(navController) },
                  )
                }

                composable(NavRoutes.CALL_LOGS) {
                  CallLogRecordsScreen(
                    viewModel = viewModel,
                    onBack = { popBackSafely(navController) },
                  )
                }

                composable(NavRoutes.RECORDING_CONTROL) {
                  RecordingControlScreen(
                    viewModel = viewModel,
                    onBack = { popBackSafely(navController) },
                  )
                }

                composable(NavRoutes.DEVICE_CONNECTIONS) {
                  DeviceConnectionsScreen(
                    viewModel = viewModel,
                    onViewAppActivity = { navigateSingleTop(navController, NavRoutes.APP_ACTIVITY) },
                    onViewCallLogs = { navigateSingleTop(navController, NavRoutes.CALL_LOGS) },
                    onBack = { popBackSafely(navController) },
                  )
                }

                composable(NavRoutes.REMOTE_APPS) {
                  RemoteAppManagementScreen(
                    viewModel = viewModel,
                    onBack = { popBackSafely(navController) },
                  )
                }

                composable(NavRoutes.EXPORT_PDF) {
                  ExportPdfScreen(
                    viewModel = viewModel,
                    onBack = { popBackSafely(navController) },
                  )
                }

                composable(NavRoutes.BACKUP_RESTORE) {
                  BackupRestoreScreen(
                    viewModel = viewModel,
                    onBack = { popBackSafely(navController) },
                  )
                }

                composable(NavRoutes.DATA_MANAGEMENT) {
                  DataManagementScreen(
                    viewModel = viewModel,
                    onBack = { popBackSafely(navController) },
                  )
                }

                composable(NavRoutes.LANGUAGE_SETTINGS) {
                  LanguageSettingsScreen(
                    viewModel = viewModel,
                    onBack = { popBackSafely(navController) },
                  )
                }

                composable(NavRoutes.SECURITY_SETTINGS) {
                  SecuritySettingsScreen(
                    viewModel = viewModel,
                    onBack = { popBackSafely(navController) },
                  )
                }

                composable(NavRoutes.INTERNET_CONFIG) {
                  InternetConfigScreen(
                    viewModel = viewModel,
                    onBack = { popBackSafely(navController) },
                  )
                }

                composable(NavRoutes.CLOUD_SYNC) {
                  val localDeviceId by viewModel.localDeviceId.collectAsStateWithLifecycle()
                  val localDeviceName by viewModel.localDeviceName.collectAsStateWithLifecycle()
                  CloudSyncScreen(
                    app = application as DeviceRecorderApp,
                    localDeviceId = localDeviceId,
                    localDeviceName = localDeviceName,
                    onBack = { popBackSafely(navController) },
                  )
                }
              }
            }
          }
        }
        }
      }
    }
  }

  private fun navigateSingleTop(navController: NavHostController, route: String) {
    runOnUiThread {
      navController.navigate(route) {
        launchSingleTop = true
      }
    }
  }

  private fun popBackSafely(navController: NavHostController) {
    runOnUiThread {
      val canPop = navController.previousBackStackEntry != null
      val isResumed =
        navController.currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED)
          ?: true
      if (canPop && isResumed) {
        navController.popBackStack()
      }
    }
  }

  private fun createLocalizedContext(base: Context, languageCode: String): Context {
    val locale = Locale.forLanguageTag(languageCode)
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    val configContext = base.createConfigurationContext(config)
    return LocalizedContextWrapper(base, configContext.resources)
  }
}
