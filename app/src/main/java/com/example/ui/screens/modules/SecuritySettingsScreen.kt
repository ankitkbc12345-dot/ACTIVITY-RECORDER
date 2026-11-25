package com.example.ui.screens.modules

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.preferences.PasswordChangeOutcome
import com.example.monitoring.DateTimeUtils
import com.example.security.CryptoManager
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
  viewModel: MainViewModel,
  onBack: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val security by viewModel.securitySettings.collectAsStateWithLifecycle()

  var adminIdInput by remember { mutableStateOf("") }
  var currentPassword by remember { mutableStateOf("") }
  var newPassword by remember { mutableStateOf("") }
  var confirmPassword by remember { mutableStateOf("") }
  var passwordStatus by remember { mutableStateOf<String?>(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.module_security)) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("security_back")) {
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
          .padding(16.dp)
          .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // Cryptographic Status
      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Text("Admin Security & Keystore Status", style = MaterialTheme.typography.titleMedium)
          }
          Text("Admin ID: Fixed & Non-Editable", style = MaterialTheme.typography.labelSmall)
          Text("Password KDF: PBKDF2WithHmacSHA256 (120,000 rounds)", style = MaterialTheme.typography.bodyMedium)
          Text(
            "Android Keystore AES-256-GCM: ${if (CryptoManager.isKeystoreActive()) "Active" else "Software Fallback"}",
            style = MaterialTheme.typography.bodyMedium,
          )
          Text(
            "Last Login: ${security.lastLoginTimestampMillis?.let { DateTimeUtils.formatDateTime(it) } ?: "Current session"}",
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }

      // Change Password Form (Admin ID + Current Password required)
      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier =
          Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("Change Admin Password", style = MaterialTheme.typography.titleMedium)
          OutlinedTextField(
            value = adminIdInput,
            onValueChange = { adminIdInput = it; passwordStatus = null },
            label = { Text(stringResource(R.string.login_admin_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("change_pw_admin_id"),
          )
          OutlinedTextField(
            value = currentPassword,
            onValueChange = { currentPassword = it; passwordStatus = null },
            label = { Text("Current Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("change_pw_current"),
          )
          OutlinedTextField(
            value = newPassword,
            onValueChange = { newPassword = it; passwordStatus = null },
            label = { Text("New Password (min 6 chars)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("change_pw_new"),
          )
          OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it; passwordStatus = null },
            label = { Text("Confirm New Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("change_pw_confirm"),
          )
          if (passwordStatus != null) {
            Text(passwordStatus!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
          }
          Button(
            onClick = {
              if (newPassword != confirmPassword) {
                passwordStatus = "New password and confirmation do not match."
                return@Button
              }
              scope.launch(Dispatchers.Main) {
                when (val outcome = viewModel.changePassword(adminIdInput, currentPassword, newPassword)) {
                  PasswordChangeOutcome.Success -> {
                    adminIdInput = ""
                    currentPassword = ""
                    newPassword = ""
                    confirmPassword = ""
                    passwordStatus = "Admin password updated securely."
                  }
                  is PasswordChangeOutcome.InvalidCredentials -> {
                    passwordStatus =
                      "Invalid Admin ID or current password (${outcome.remainingAttempts} attempts remaining)."
                  }
                  is PasswordChangeOutcome.LockedOut -> {
                    passwordStatus =
                      "Too many failed attempts. Try again in ${outcome.remainingSeconds} seconds."
                  }
                  PasswordChangeOutcome.TooShort -> {
                    passwordStatus = "New password must be at least 6 characters."
                  }
                }
              }
            },
            enabled =
              adminIdInput.isNotBlank() &&
                currentPassword.isNotEmpty() &&
                newPassword.isNotEmpty() &&
                confirmPassword.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("change_pw_submit"),
          ) {
            Text("Save New Password")
          }
        }
      }

      // Session Lock Policy
      Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier =
          Modifier.fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp)),
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Session Lock Policy", style = MaterialTheme.typography.titleMedium)
          Text(
            "Manual Logout Only — Your Admin session stays active after login until you explicitly tap Lock & Exit.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
