package com.example.ui.screens.modules

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.MainViewModel

private data class ThemeChoice(
  val mode: String,
  val labelRes: Int,
  val subtitle: String,
  val icon: ImageVector,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

  // Exact serial order requested:
  // 1. Default Mode (System)
  // 2. Bright Mode (Light)
  // 3. Dark Mode
  val themes =
    listOf(
      ThemeChoice(
        mode = "system",
        labelRes = R.string.theme_system,
        subtitle = "Follow phone system setting automatically",
        icon = Icons.Default.BrightnessAuto,
      ),
      ThemeChoice(
        mode = "light",
        labelRes = R.string.theme_light,
        subtitle = "Clean daylight high-contrast surface",
        icon = Icons.Default.LightMode,
      ),
      ThemeChoice(
        mode = "dark",
        labelRes = R.string.theme_dark,
        subtitle = "Low-glare dark display surface",
        icon = Icons.Default.DarkMode,
      ),
    )

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.theme_title)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("theme_settings_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_cd))
          }
        },
      )
    }
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = stringResource(R.string.theme_section_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = stringResource(R.string.theme_section_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(4.dp))

      themes.forEach { th ->
        val isSelected = themeMode == th.mode
        Surface(
          shape = RoundedCornerShape(16.dp),
          color =
            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface,
          modifier =
            Modifier.fillMaxWidth()
              .border(
                width = if (isSelected) 2.dp else 1.dp,
                color =
                  if (isSelected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(16.dp),
              )
              .clickable { viewModel.setThemeMode(th.mode) }
              .testTag("theme_option_${th.mode}"),
        ) {
          Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = th.icon,
              contentDescription = null,
              tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
              Text(stringResource(th.labelRes), style = MaterialTheme.typography.titleMedium)
              Text(
                th.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            if (isSelected) {
              Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
            }
          }
        }
      }
    }
  }
}
