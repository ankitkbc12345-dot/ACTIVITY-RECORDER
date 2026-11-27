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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.BuildConfig
import com.example.R
import com.example.connectivity.UpdateCheckResult
import com.example.ui.HomeUpdateFeedback
import com.example.ui.MainViewModel
import com.example.updates.ApkDownloadState
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val HomeUpdateBannerRed = Color(0xFFD32F2F)
private val HomeUpdateSuccessGreen = Color(0xFF2E7D32)

@Composable
fun HomeCalendarScreen(
  viewModel: MainViewModel,
  onOpenAdminPanel: () -> Unit,
  onOpenCheckUpdates: () -> Unit = {},
  updateCheckResult: UpdateCheckResult = UpdateCheckResult.NotChecked,
  apkDownloadState: ApkDownloadState = ApkDownloadState.Idle,
  isCheckingUpdates: Boolean = false,
  homeUpdateFeedback: HomeUpdateFeedback = HomeUpdateFeedback.None,
  onStartOrRetryUpdate: () -> Unit = onOpenCheckUpdates,
  onDismissUpdateFeedback: () -> Unit = {},
) {
  val context = LocalContext.current
  val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
  val scope = rememberCoroutineScope()
  var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

  val languageCode by viewModel.languageCode.collectAsStateWithLifecycle()
  val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
  val currentUser = runCatching { Firebase.auth.currentUser }.getOrNull()
  val photoUrl = currentUser?.photoUrl?.toString()
  val displayName = currentUser?.displayName?.takeIf { it.isNotBlank() } ?: "Guest User"
  val email = currentUser?.email?.takeIf { it.isNotBlank() } ?: "Guest Mode"
  var updateCheckRequested by remember { mutableStateOf(false) }
  var showUpdatePopup by remember { mutableStateOf(false) }

  LaunchedEffect(isCheckingUpdates, homeUpdateFeedback, updateCheckResult, apkDownloadState, updateCheckRequested) {
    if (updateCheckRequested && !isCheckingUpdates) {
      when {
        homeUpdateFeedback is HomeUpdateFeedback.AlreadyUpdated ||
          homeUpdateFeedback is HomeUpdateFeedback.Offline ||
          homeUpdateFeedback is HomeUpdateFeedback.Error -> {
          showUpdatePopup = true
          updateCheckRequested = false
        }
        (updateCheckResult as? UpdateCheckResult.Checked)?.updateAvailable == true ||
          apkDownloadState !is ApkDownloadState.Idle -> {
          updateCheckRequested = false
        }
      }
    }
  }

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
      ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
        Column(
          modifier =
            Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Spacer(modifier = Modifier.height(8.dp))

          // 1. Authenticated User Profile & Full Details
          Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth().testTag("drawer_user_profile_card"),
          ) {
            Row(
              modifier = Modifier.padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              if (!photoUrl.isNullOrBlank()) {
                AsyncImage(
                  model = photoUrl,
                  contentDescription = displayName,
                  contentScale = ContentScale.Crop,
                  modifier = Modifier.size(50.dp).clip(CircleShape),
                )
              } else {
                Surface(
                  shape = CircleShape,
                  color = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(50.dp),
                ) {
                  Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                      imageVector = Icons.Default.Person,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.onPrimary,
                      modifier = Modifier.size(26.dp),
                    )
                  }
                }
              }
              Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                  text = displayName,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                  text = email,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                )
              }
            }
          }

          HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

          // 2. Language Selection
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
              Icon(
                Icons.Default.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
              Text(
                "Language Selection",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
            val languages =
              listOf(
                "en" to "English",
                "hi" to "हिन्दी (Hindi)",
                "ar" to "العربية (Arabic)",
                "es" to "Español (Spanish)",
                "fr" to "Français (French)",
              )
            languages.forEach { (code, label) ->
              NavigationDrawerItem(
                label = { Text(label) },
                selected = languageCode == code,
                onClick = {
                  viewModel.setLanguageCode(code)
                },
                modifier =
                  Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    .testTag("lang_$code"),
              )
            }
          }

          HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

          // 3. Display Theme Modes
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
              Icon(
                Icons.Default.Contrast,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
              Text(
                "Display Theme Modes",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
            val themes =
              listOf(
                "system" to "Default Mode",
                "light" to "Bright / Light Mode",
                "dark" to "Dark Mode",
              )
            themes.forEach { (mode, label) ->
              NavigationDrawerItem(
                label = { Text(label) },
                selected = themeMode == mode,
                onClick = {
                  viewModel.setThemeMode(mode)
                },
                modifier =
                  Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    .testTag("theme_$mode"),
              )
            }
          }

          HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

          // 4. Update Option (Step 1: Directly checks & starts update or shows popup in-place; never opens another page)
          NavigationDrawerItem(
            label = { Text(stringResource(R.string.menu_check_updates)) },
            icon = {
              if (isCheckingUpdates) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
              } else {
                Icon(Icons.Default.SystemUpdate, contentDescription = null)
              }
            },
            selected = false,
            onClick = {
              updateCheckRequested = true
              showUpdatePopup = false
              scope.launch { drawerState.close() }
              onOpenCheckUpdates()
            },
            modifier =
              Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                .testTag("drawer_update_option"),
          )

          Spacer(modifier = Modifier.weight(1f))
          HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

          // 5. Admin Dashboard / Admin Login (At very bottom)
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
      }
    },
  ) {
    Scaffold(
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
      Column(
        modifier =
          Modifier.fillMaxSize()
            .padding(innerPadding)
            .statusBarsPadding()
            .padding(top = 8.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Top row: left hamburger menu & right circular user avatar (circle crop)
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

          // Top-right circular user profile avatar
          Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier =
              Modifier.size(44.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), CircleShape)
                .clickable { scope.launch { drawerState.open() } }
                .testTag("home_user_avatar_button"),
          ) {
            if (!photoUrl.isNullOrBlank()) {
              AsyncImage(
                model = photoUrl,
                contentDescription = displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
              )
            } else {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                  imageVector = Icons.Default.Person,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.size(22.dp),
                )
              }
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

          if (isCheckingUpdates && updateCheckRequested) {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.secondaryContainer,
              modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp).testTag("home_update_checking_chip"),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                  text = stringResource(R.string.update_popup_checking),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
              }
            }
          }

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
                    modifier = Modifier.testTag("calendar_prev_month"),
                  ) {
                    Icon(
                      imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
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
                    modifier = Modifier.testTag("calendar_next_month"),
                  ) {
                    Icon(
                      imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                      contentDescription = stringResource(R.string.calendar_next_month_cd),
                    )
                  }
                }
              }

              Spacer(modifier = Modifier.height(16.dp))

              val daysOfWeek = DateFormatSymbols(locale).shortWeekdays
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (dow in 1..7) {
                  val dayName =
                    try {
                      daysOfWeek[dow]
                    } catch (_: Exception) {
                      ""
                    }
                  Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                  ) {
                    Text(
                      text = dayName.take(2).uppercase(locale),
                      style = MaterialTheme.typography.labelMedium,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }

              Spacer(modifier = Modifier.height(12.dp))

              val firstDayOfWeek = monthCal.get(Calendar.DAY_OF_WEEK)
              val daysInMonth = monthCal.getActualMaximum(Calendar.DAY_OF_MONTH)
              val firstDayIndex = (firstDayOfWeek - monthCal.firstDayOfWeek + 7) % 7
              val totalCells = ((firstDayIndex + daysInMonth + 6) / 7) * 7

              val calendarCells = remember(displayedYear, displayedMonth, firstDayIndex, daysInMonth, totalCells) {
                val list = mutableListOf<Int?>()
                for (i in 0 until totalCells) {
                  val dayNum = i - firstDayIndex + 1
                  if (dayNum in 1..daysInMonth) {
                    list.add(dayNum)
                  } else {
                    list.add(null)
                  }
                }
                list
              }

              val weeks = calendarCells.chunked(7)
              for (week in weeks) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  for (dayNum in week) {
                    if (dayNum != null) {
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
                          isSelected -> MaterialTheme.colorScheme.secondaryContainer
                          isToday -> MaterialTheme.colorScheme.primary
                          else -> Color.Transparent
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

  if (showUpdatePopup) {
    val popupFeedback = homeUpdateFeedback
    AlertDialog(
      onDismissRequest = { showUpdatePopup = false },
      icon = {
        when (popupFeedback) {
          HomeUpdateFeedback.AlreadyUpdated, HomeUpdateFeedback.Updated ->
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = HomeUpdateSuccessGreen)
          HomeUpdateFeedback.Offline ->
            Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
          else ->
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        }
      },
      title = {
        Text(
          text =
            when (popupFeedback) {
              HomeUpdateFeedback.AlreadyUpdated -> stringResource(R.string.update_popup_uptodate_title)
              HomeUpdateFeedback.Updated -> stringResource(R.string.update_completed_message)
              HomeUpdateFeedback.Offline -> stringResource(R.string.menu_check_updates)
              is HomeUpdateFeedback.Error -> stringResource(R.string.menu_check_updates)
              HomeUpdateFeedback.None -> stringResource(R.string.update_popup_uptodate_title)
            }
        )
      },
      text = {
        Text(
          text =
            when (popupFeedback) {
              HomeUpdateFeedback.AlreadyUpdated ->
                "${stringResource(R.string.update_already_updated)}\n${stringResource(R.string.update_popup_uptodate_desc, "v${BuildConfig.VERSION_NAME}")}"
              HomeUpdateFeedback.Updated -> stringResource(R.string.update_completed_message)
              HomeUpdateFeedback.Offline -> stringResource(R.string.updates_offline_status)
              is HomeUpdateFeedback.Error -> popupFeedback.reason
              HomeUpdateFeedback.None -> stringResource(R.string.update_popup_uptodate_desc, "v${BuildConfig.VERSION_NAME}")
            }
        )
      },
      confirmButton = {
        Button(
          onClick = { showUpdatePopup = false },
          modifier = Modifier.testTag("home_update_popup_ok"),
        ) {
          Text(stringResource(R.string.update_popup_ok))
        }
      },
      modifier = Modifier.testTag("home_update_popup_dialog"),
    )
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
          imageVector = Icons.Default.ErrorOutline,
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
