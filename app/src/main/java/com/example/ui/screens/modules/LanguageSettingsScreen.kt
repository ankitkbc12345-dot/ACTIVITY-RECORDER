package com.example.ui.screens.modules

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.MainViewModel

private data class LanguageOption(val code: String, val name: String, val nativeName: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSettingsScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val selectedCode by viewModel.languageCode.collectAsStateWithLifecycle()

  val options =
    listOf(
      LanguageOption("en", "English", "English"),
      LanguageOption("hi", "Hindi", "हिन्दी"),
      LanguageOption("es", "Spanish", "Español"),
      LanguageOption("fr", "French", "Français"),
      LanguageOption("ar", "Arabic", "العربية"),
    )

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.language_title)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("language_back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back_cd))
          }
        },
      )
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = stringResource(R.string.language_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      options.forEach { opt ->
        val isSelected = selectedCode == opt.code
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
              .clickable { viewModel.setLanguageCode(opt.code) }
              .testTag("lang_option_${opt.code}"),
        ) {
          Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column {
              Text(opt.nativeName, style = MaterialTheme.typography.titleMedium)
              Text("${opt.name} (${opt.code})", style = MaterialTheme.typography.labelSmall)
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
