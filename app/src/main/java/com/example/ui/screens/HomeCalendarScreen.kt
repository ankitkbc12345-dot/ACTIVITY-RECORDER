package com.example.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.connectivity.UpdateCheckResult
import com.example.ui.HomeUpdateFeedback
import com.example.updates.ApkDownloadState
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val HomeUpdateBannerRed = Color(0xFFD32F2F)
private val HomeUpdateSuccessGreen = Color(0xFF2E7D32)

@Composable
fun HomeCalendarScreen(
  onOpenAdminPanel: () -> Unit,
  onOpenCheckUpdates: () -> Unit = {},
  updateCheckResult: UpdateCheckResult = UpdateCheckResult.NotChecked,
  apkDownloadState: ApkDownloadState = ApkDownloadState.Idle,
  isCheckingUpdates: Boolean = false,
  homeUpdateFeedback: HomeUpdateFeedback = HomeUpdateFeedback.None,
  onStartOrRetryUpdate: () -> Unit = onOpenCheckUpdates,
  onCircularUpdateClick: () -> Unit = onStartOrRetryUpdate,
  onDismissUpdateFeedback: () -> Unit = {},
) {
  val context = LocalContext.current
  val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
  val scope = rememberCoroutineScope()

  var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

  // Listen to system clock / date / timezone broadcasts and keep live clock refreshed
  DisposableEffect(context) {
    val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
          nowMillis = System.currentTimeMillis()
        }
      }
    val filter =
      IntentFilter().apply {
        addAction(Intent.ACTION_TIME_TICK)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_DATE_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
      }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    onDispose {
      try {
        context.unregisterReceiver(receiver)
      } catch (_: Exception) {}
    }
  }

  LaunchedEffect(Unit) {
    while (true) {
      delay(15_000L)
      nowMillis = System.currentTimeMillis()
    }
  }

  val localCal =
    remember(nowMillis) {
      Calendar.getInstance(TimeZone.getDefault(), Locale.getDefault()).apply {
        timeInMillis = nowMillis
      }
    }
  val currentYear = localCal.get(Calendar.YEAR)
  val currentMonth = localCal.get(Calendar.MONTH)
  val currentDay = localCal.get(Calendar.DAY_OF_MONTH)

  var displayedYear by remember { mutableIntStateOf(currentYear) }
  var displayedMonth by remember { mutableIntStateOf(currentMonth) }
  var selectedYear by remember { mutableIntStateOf(currentYear) }
  var selectedMonth by remember { mutableIntStateOf(currentMonth) }
  var selectedDay by remember { mutableIntStateOf(currentDay) }

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
          text = stringResource(R.string.app_name),
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        NavigationDrawerItem(
          label = { Text(stringResource(R.string.menu_admin_panel)) },
          icon = { Icon(Icons.Default.AdminPanelSettings, contentDescription = null) },
          selected = false,
          onClick = {
            scope.launch { drawerState.close() }
            onOpenAdminPanel()
          },
          modifier =
            Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
              .testTag("drawer_admin_panel"),
        )
      }
    },
  ) {
    Scaffold(
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
      val isUpdateBusy = isCheckingUpdates || apkDownloadState is ApkDownloadState.Downloading
      val infiniteTransition = rememberInfiniteTransition(label = "home_update_spin")
      val spinAngle by
        infiniteTransition.animateFloat(
          initialValue = 0f,
          targetValue = 360f,
          animationSpec =
            infiniteRepeatable(
              animation = tween(durationMillis = 900, easing = LinearEasing),
              repeatMode = RepeatMode.Restart,
            ),
          label = "home_update_rotation",
        )

      Column(
        modifier =
          Modifier.fillMaxSize()
            .padding(innerPadding)
            .statusBarsPadding()
            .padding(top = 8.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Minimal top row: left 3-line hamburger icon & right circular rotating update icon
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(
            onClick = { scope.launch { drawerState.open() } },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("hamburger_menu_button"),
          ) {
            Icon(
              imageVector = Icons.Default.Menu,
              contentDescription = stringResource(R.string.menu_open_cd),
              tint = MaterialTheme.colorScheme.onBackground,
              modifier = Modifier.size(28.dp),
            )
          }

          Surface(
            shape = CircleShape,
            color =
              if (isUpdateBusy) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.primaryContainer,
            contentColor =
              if (isUpdateBusy) MaterialTheme.colorScheme.onPrimary
              else MaterialTheme.colorScheme.onPrimaryContainer,
            tonalElevation = 2.dp,
            modifier =
              Modifier.minimumInteractiveComponentSize()
                .size(44.dp)
                .clip(CircleShape)
                .border(
                  1.dp,
                  MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                  CircleShape,
                )
                .clickable(enabled = !isUpdateBusy, onClick = onCircularUpdateClick)
                .testTag("home_circular_update_button"),
          ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
              Icon(
                imageVector = Icons.Default.Autorenew,
                contentDescription = stringResource(R.string.update_circular_button_cd),
                modifier =
                  Modifier.size(24.dp).rotate(if (isUpdateBusy) spinAngle else 0f),
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column(
          modifier =
            Modifier.weight(1f)
              .widthIn(max = 560.dp)
              .fillMaxWidth()
              .verticalScroll(rememberScrollState())
              .padding(bottom = 24.dp),
          horizontalAlignment = Alignment.Start,
        ) {
          val availableUpdate =
            (updateCheckResult as? UpdateCheckResult.Checked)?.takeIf { it.updateAvailable }

          AnimatedVisibility(visible = homeUpdateFeedback !is HomeUpdateFeedback.None) {
            if (homeUpdateFeedback !is HomeUpdateFeedback.None) {
              HomeUpdateFeedbackChip(
                feedback = homeUpdateFeedback,
                onDismiss = onDismissUpdateFeedback,
              )
            }
          }

          if (homeUpdateFeedback !is HomeUpdateFeedback.None) {
            Spacer(modifier = Modifier.height(10.dp))
          }

          AnimatedVisibility(visible = availableUpdate != null) {
            if (availableUpdate != null) {
              HomeRedUpdateBanner(
                latestVersionName = availableUpdate.latestVersionName,
                downloadState = apkDownloadState,
                onClick = onStartOrRetryUpdate,
              )
            }
          }

          if (availableUpdate != null) {
            Spacer(modifier = Modifier.height(14.dp))
          }

          val locale = Locale.getDefault()
          val tz = TimeZone.getDefault()
          val dayOfWeekText =
            remember(nowMillis, locale) {
              SimpleDateFormat("EEEE", locale).apply { timeZone = tz }.format(Date(nowMillis))
            }
          val fullDateText =
            remember(nowMillis, locale) {
              SimpleDateFormat("d MMMM yyyy", locale).apply { timeZone = tz }.format(Date(nowMillis))
            }
          val liveTimeText =
            remember(nowMillis, locale) {
              SimpleDateFormat("hh:mm a", locale).apply { timeZone = tz }.format(Date(nowMillis))
            }

          // Clean live date & time header
          Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier =
              Modifier.fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)),
          ) {
            Column(modifier = Modifier.padding(24.dp)) {
              Text(
                text = dayOfWeekText.uppercase(locale),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = fullDateText,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Spacer(modifier = Modifier.height(12.dp))
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = liveTimeText,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                )
                Text(
                  text = "${stringResource(R.string.calendar_timezone_prefix)}: ${tz.getDisplayName(false, TimeZone.SHORT, locale)}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(20.dp))

          // Monthly Calendar Card
          Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier =
              Modifier.fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)),
          ) {
            Column(modifier = Modifier.padding(20.dp)) {
              val monthCal =
                remember(displayedYear, displayedMonth) {
                  Calendar.getInstance(tz, locale).apply {
                    set(Calendar.YEAR, displayedYear)
                    set(Calendar.MONTH, displayedMonth)
                    set(Calendar.DAY_OF_MONTH, 1)
                  }
                }
              val monthTitle =
                remember(displayedYear, displayedMonth, locale) {
                  SimpleDateFormat("MMMM yyyy", locale).apply { timeZone = tz }.format(monthCal.time)
                }

              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = monthTitle,
                  style = MaterialTheme.typography.titleLarge,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                  if (displayedYear != currentYear || displayedMonth != currentMonth) {
                    TextButton(
                      onClick = {
                        displayedYear = currentYear
                        displayedMonth = currentMonth
                        selectedYear = currentYear
                        selectedMonth = currentMonth
                        selectedDay = currentDay
                      },
                      modifier = Modifier.testTag("calendar_today_button"),
                    ) {
                      Text(stringResource(R.string.calendar_today_action))
                    }
                  }
                  IconButton(
                    onClick = {
                      if (displayedMonth == 0) {
                        displayedMonth = 11
                        displayedYear -= 1
                      } else {
                        displayedMonth -= 1
                      }
                    },
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("calendar_prev_month"),
                  ) {
                    Icon(
                      Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                      contentDescription = stringResource(R.string.calendar_prev_month_cd),
                    )
                  }
                  IconButton(
                    onClick = {
                      if (displayedMonth == 11) {
                        displayedMonth = 0
                        displayedYear += 1
                      } else {
                        displayedMonth += 1
                      }
                    },
                    modifier = Modifier.minimumInteractiveComponentSize().testTag("calendar_next_month"),
                  ) {
                    Icon(
                      Icons.AutoMirrored.Filled.KeyboardArrowRight,
                      contentDescription = stringResource(R.string.calendar_next_month_cd),
                    )
                  }
                }
              }

              Spacer(modifier = Modifier.height(16.dp))

              // Day of week headers (Sun .. Sat)
              val shortWeekdays = remember(locale) { DateFormatSymbols(locale).shortWeekdays }
              Row(modifier = Modifier.fillMaxWidth()) {
                for (dow in Calendar.SUNDAY..Calendar.SATURDAY) {
                  Text(
                    text = shortWeekdays[dow].take(3),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                  )
                }
              }

              Spacer(modifier = Modifier.height(10.dp))

              val firstDayOfWeek = monthCal.get(Calendar.DAY_OF_WEEK) // 1 = Sun
              val leadingBlanks = firstDayOfWeek - Calendar.SUNDAY
              val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
              val totalCells = ((leadingBlanks + daysInMonth + 6) / 7) * 7

              for (rowStart in 0 until totalCells step 7) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                  horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  for (col in 0 until 7) {
                    val cellIndex = rowStart + col
                    val dayNum = cellIndex - leadingBlanks + 1
                    if (dayNum in 1..daysInMonth) {
                      val isToday =
                        displayedYear == currentYear &&
                          displayedMonth == currentMonth &&
                          dayNum == currentDay
                      val isSelected =
                        displayedYear == selectedYear &&
                          displayedMonth == selectedMonth &&
                          dayNum == selectedDay

                      val bgColor =
                        when {
                          isToday -> MaterialTheme.colorScheme.primary
                          isSelected -> MaterialTheme.colorScheme.secondaryContainer
                          else -> MaterialTheme.colorScheme.surface
                        }
                      val textColor =
                        when {
                          isToday -> MaterialTheme.colorScheme.onPrimary
                          isSelected -> MaterialTheme.colorScheme.onSecondaryContainer
                          else -> MaterialTheme.colorScheme.onSurface
                        }

                      Box(
                        modifier =
                          Modifier.weight(1f)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(bgColor)
                            .clickable {
                              selectedYear = displayedYear
                              selectedMonth = displayedMonth
                              selectedDay = dayNum
                            },
                        contentAlignment = Alignment.Center,
                      ) {
                        Text(
                          text = dayNum.toString(),
                          style = MaterialTheme.typography.bodyLarge,
                          fontWeight =
                            if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                          color = textColor,
                        )
                      }
                    } else {
                      Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                    }
                  }
                }
              }

              Spacer(modifier = Modifier.height(12.dp))
              HorizontalDivider()
              Spacer(modifier = Modifier.height(10.dp))

              val selectedCal =
                remember(selectedYear, selectedMonth, selectedDay) {
                  Calendar.getInstance(tz, locale).apply {
                    set(Calendar.YEAR, selectedYear)
                    set(Calendar.MONTH, selectedMonth)
                    set(Calendar.DAY_OF_MONTH, selectedDay)
                  }
                }
              val selectedLabel =
                remember(selectedYear, selectedMonth, selectedDay, locale) {
                  SimpleDateFormat("EEEE, d MMMM yyyy", locale).apply { timeZone = tz }.format(selectedCal.time)
                }
              Text(
                text = selectedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().testTag("calendar_selected_date"),
                textAlign = TextAlign.Center,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun HomeRedUpdateBanner(
  latestVersionName: String,
  downloadState: ApkDownloadState,
  onClick: () -> Unit,
) {
  val isDownloading = downloadState is ApkDownloadState.Downloading
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = HomeUpdateBannerRed,
    contentColor = Color.White,
    shadowElevation = 4.dp,
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(18.dp))
        .clickable(enabled = !isDownloading, onClick = onClick)
        .minimumInteractiveComponentSize()
        .testTag("home_update_banner"),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        val icon =
          when (downloadState) {
            is ApkDownloadState.Downloading -> Icons.Default.CloudDownload
            is ApkDownloadState.PermissionRequired -> Icons.Default.Security
            is ApkDownloadState.ReadyToInstall -> Icons.Default.InstallMobile
            is ApkDownloadState.Failed -> Icons.Default.ErrorOutline
            ApkDownloadState.Idle -> Icons.Default.SystemUpdate
          }
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(26.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          val primaryMessage =
            when (downloadState) {
              ApkDownloadState.Idle -> stringResource(R.string.update_banner_available)
              is ApkDownloadState.Downloading ->
                if (downloadState.progressPercent >= 0)
                  "${stringResource(R.string.updates_downloading_title)} (${downloadState.progressPercent}%)"
                else stringResource(R.string.updates_downloading_title)
              is ApkDownloadState.PermissionRequired ->
                stringResource(R.string.updates_permission_required)
              is ApkDownloadState.ReadyToInstall ->
                stringResource(R.string.updates_ready_to_install)
              is ApkDownloadState.Failed ->
                stringResource(R.string.updates_download_failed, downloadState.message)
            }
          Text(
            text = primaryMessage,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
          )
          val subMessage =
            when (downloadState) {
              ApkDownloadState.Idle -> "v$latestVersionName • Tap to download & install"
              is ApkDownloadState.Downloading ->
                if (downloadState.totalBytes > 0L)
                  "${formatByteCount(downloadState.downloadedBytes)} / ${formatByteCount(downloadState.totalBytes)}"
                else formatByteCount(downloadState.downloadedBytes)
              is ApkDownloadState.PermissionRequired ->
                "${stringResource(R.string.updates_allow_permission_button)} • v$latestVersionName"
              is ApkDownloadState.ReadyToInstall ->
                "${stringResource(R.string.updates_install_apk_button)} • v$latestVersionName"
              is ApkDownloadState.Failed ->
                stringResource(R.string.updates_retry_button)
            }
          Text(
            text = subMessage,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.9f),
          )
        }
      }

      if (downloadState is ApkDownloadState.Downloading) {
        if (downloadState.progressPercent >= 0) {
          LinearProgressIndicator(
            progress = { (downloadState.progressPercent / 100f).coerceIn(0f, 1f) },
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth().height(6.dp),
          )
        } else {
          LinearProgressIndicator(
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.3f),
            modifier = Modifier.fillMaxWidth().height(6.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun HomeUpdateFeedbackChip(
  feedback: HomeUpdateFeedback,
  onDismiss: () -> Unit,
) {
  val (bgColor, fgColor, icon, text) =
    when (feedback) {
      HomeUpdateFeedback.AlreadyUpdated ->
        FeedbackStyle(
          bg = HomeUpdateSuccessGreen.copy(alpha = 0.12f),
          fg = HomeUpdateSuccessGreen,
          icon = Icons.Default.CheckCircle,
          text = stringResource(R.string.update_already_updated),
        )
      HomeUpdateFeedback.Updated ->
        FeedbackStyle(
          bg = HomeUpdateSuccessGreen.copy(alpha = 0.14f),
          fg = HomeUpdateSuccessGreen,
          icon = Icons.Default.CheckCircle,
          text = stringResource(R.string.update_completed_message),
        )
      HomeUpdateFeedback.Offline ->
        FeedbackStyle(
          bg = MaterialTheme.colorScheme.tertiaryContainer,
          fg = MaterialTheme.colorScheme.onTertiaryContainer,
          icon = Icons.Default.CloudOff,
          text = stringResource(R.string.updates_offline_status),
        )
      is HomeUpdateFeedback.Error ->
        FeedbackStyle(
          bg = MaterialTheme.colorScheme.errorContainer,
          fg = MaterialTheme.colorScheme.onErrorContainer,
          icon = Icons.Default.ErrorOutline,
          text = feedback.reason,
        )
      HomeUpdateFeedback.None -> return
    }

  Surface(
    shape = RoundedCornerShape(14.dp),
    color = bgColor,
    modifier =
      Modifier.fillMaxWidth()
        .border(1.dp, fgColor.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
        .testTag("home_update_status_chip"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(imageVector = icon, contentDescription = null, tint = fgColor, modifier = Modifier.size(20.dp))
      Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = fgColor,
        modifier = Modifier.weight(1f),
      )
      IconButton(
        onClick = onDismiss,
        modifier = Modifier.size(32.dp).testTag("home_update_status_dismiss"),
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = "Dismiss",
          tint = fgColor,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

private data class FeedbackStyle(
  val bg: Color,
  val fg: Color,
  val icon: androidx.compose.ui.graphics.vector.ImageVector,
  val text: String,
)
