package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.preferences.LoginOutcome
import com.example.data.preferences.PasswordChangeOutcome
import com.example.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminLoginScreen(
  viewModel: MainViewModel,
  onLoginSuccess: () -> Unit,
  onBack: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val focusManager = LocalFocusManager.current
  val textToolbar = LocalTextToolbar.current
  val securityState by viewModel.securitySettings.collectAsStateWithLifecycle()

  var adminIdInput by remember { mutableStateOf("") }
  var passwordInput by remember { mutableStateOf("") }
  var passwordVisible by remember { mutableStateOf(false) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var statusMessage by remember { mutableStateOf<String?>(null) }
  var lockoutSeconds by remember { mutableIntStateOf(0) }

  var showChangePassword by remember { mutableStateOf(false) }
  var changeAdminId by remember { mutableStateOf("") }
  var currentPassword by remember { mutableStateOf("") }
  var newPassword by remember { mutableStateOf("") }
  var confirmPassword by remember { mutableStateOf("") }
  var currentPasswordVisible by remember { mutableStateOf(false) }
  var newPasswordVisible by remember { mutableStateOf(false) }
  var changeErrorMessage by remember { mutableStateOf<String?>(null) }

  BackHandler(enabled = showChangePassword) {
    textToolbar.hide()
    focusManager.clearFocus(force = true)
    showChangePassword = false
    changeErrorMessage = null
  }

  LaunchedEffect(securityState.lockoutUntilMillis) {
    val rem = ((securityState.lockoutUntilMillis - System.currentTimeMillis()) / 1000L).toInt()
    if (rem > 0) lockoutSeconds = rem
  }

  LaunchedEffect(lockoutSeconds) {
    if (lockoutSeconds > 0) {
      delay(1000L)
      lockoutSeconds -= 1
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            if (showChangePassword) stringResource(R.string.change_pw_title)
            else stringResource(R.string.login_title)
          )
        },
        navigationIcon = {
          IconButton(
            onClick = {
              textToolbar.hide()
              focusManager.clearFocus(force = true)
              if (showChangePassword) {
                showChangePassword = false
                changeErrorMessage = null
              } else {
                onBack()
              }
            },
            modifier = Modifier.testTag("login_back_button"),
          ) {
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
          .padding(20.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier =
          Modifier.widthIn(max = 460.dp)
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp)),
      ) {
        if (!showChangePassword) {
          Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Column {
                Text(stringResource(R.string.login_title), style = MaterialTheme.typography.titleLarge)
                Text(
                  stringResource(R.string.login_subtitle),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
              Text(
                text = stringResource(R.string.login_offline_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
              )
            }

            if (statusMessage != null) {
              Text(
                text = statusMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
              )
            }

            // Manual Admin ID entry (validated against owner-provisioned Admin ID)
            OutlinedTextField(
              value = adminIdInput,
              onValueChange = {
                adminIdInput = it
                errorMessage = null
                statusMessage = null
              },
              label = { Text(stringResource(R.string.login_admin_id)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("login_admin_id_field"),
            )

            OutlinedTextField(
              value = passwordInput,
              onValueChange = {
                passwordInput = it
                errorMessage = null
                statusMessage = null
              },
              label = { Text(stringResource(R.string.login_admin_password)) },
              singleLine = true,
              visualTransformation =
                if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                  Icon(
                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                  )
                }
              },
              modifier = Modifier.fillMaxWidth().testTag("login_password_field"),
            )

            if (lockoutSeconds > 0) {
              Text(
                text = stringResource(R.string.login_error_locked, lockoutSeconds),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
              )
            } else if (errorMessage != null) {
              Text(
                text = errorMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
              )
            }

            Button(
              onClick = {
                scope.launch(Dispatchers.Main) {
                  when (val outcome = viewModel.login(adminIdInput, passwordInput)) {
                    LoginOutcome.Success -> {
                      textToolbar.hide()
                      focusManager.clearFocus(force = true)
                      adminIdInput = ""
                      passwordInput = ""
                      onLoginSuccess()
                    }
                    is LoginOutcome.Invalid -> {
                      errorMessage =
                        "Invalid Admin ID or password (${outcome.remainingAttempts} attempts remaining)."
                    }
                    is LoginOutcome.LockedOut -> {
                      lockoutSeconds = outcome.remainingSeconds
                      errorMessage = null
                    }
                  }
                }
              },
              enabled = lockoutSeconds == 0 && adminIdInput.isNotBlank() && passwordInput.isNotEmpty(),
              modifier = Modifier.fillMaxWidth().height(52.dp).testTag("login_submit_button"),
            ) {
              Text(stringResource(R.string.login_button))
            }

            OutlinedButton(
              onClick = {
                textToolbar.hide()
                focusManager.clearFocus(force = true)
                errorMessage = null
                statusMessage = null
                changeErrorMessage = null
                changeAdminId = ""
                currentPassword = ""
                newPassword = ""
                confirmPassword = ""
                showChangePassword = true
              },
              modifier = Modifier.fillMaxWidth().height(48.dp).testTag("login_change_password_button"),
            ) {
              Text(stringResource(R.string.login_change_password))
            }
          }
        } else {
          Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Column {
                Text(stringResource(R.string.change_pw_title), style = MaterialTheme.typography.titleLarge)
                Text(
                  stringResource(R.string.change_pw_subtitle),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            OutlinedTextField(
              value = changeAdminId,
              onValueChange = {
                changeAdminId = it
                changeErrorMessage = null
              },
              label = { Text(stringResource(R.string.login_admin_id)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("login_change_pw_admin_id"),
            )

            OutlinedTextField(
              value = currentPassword,
              onValueChange = {
                currentPassword = it
                changeErrorMessage = null
              },
              label = { Text("Current Password") },
              singleLine = true,
              visualTransformation =
                if (currentPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              trailingIcon = {
                IconButton(onClick = { currentPasswordVisible = !currentPasswordVisible }) {
                  Icon(
                    imageVector =
                      if (currentPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (currentPasswordVisible) "Hide password" else "Show password",
                  )
                }
              },
              modifier = Modifier.fillMaxWidth().testTag("login_change_pw_current"),
            )

            OutlinedTextField(
              value = newPassword,
              onValueChange = {
                newPassword = it
                changeErrorMessage = null
              },
              label = { Text("New Password (min 6 chars)") },
              singleLine = true,
              visualTransformation =
                if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              trailingIcon = {
                IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                  Icon(
                    imageVector =
                      if (newPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (newPasswordVisible) "Hide password" else "Show password",
                  )
                }
              },
              modifier = Modifier.fillMaxWidth().testTag("login_change_pw_new"),
            )

            OutlinedTextField(
              value = confirmPassword,
              onValueChange = {
                confirmPassword = it
                changeErrorMessage = null
              },
              label = { Text("Confirm New Password") },
              singleLine = true,
              visualTransformation =
                if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              modifier = Modifier.fillMaxWidth().testTag("login_change_pw_confirm"),
            )

            if (lockoutSeconds > 0) {
              Text(
                text = stringResource(R.string.login_error_locked, lockoutSeconds),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
              )
            } else if (changeErrorMessage != null) {
              Text(
                text = changeErrorMessage!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
              )
            }

            Button(
              onClick = {
                if (newPassword != confirmPassword) {
                  changeErrorMessage = "New password and confirmation do not match."
                  return@Button
                }
                scope.launch(Dispatchers.Main) {
                  when (val outcome = viewModel.changePassword(changeAdminId, currentPassword, newPassword)) {
                    PasswordChangeOutcome.Success -> {
                      textToolbar.hide()
                      focusManager.clearFocus(force = true)
                      changeAdminId = ""
                      currentPassword = ""
                      newPassword = ""
                      confirmPassword = ""
                      changeErrorMessage = null
                      showChangePassword = false
                      statusMessage = "Admin password updated securely. Sign in with your new password."
                    }
                    is PasswordChangeOutcome.InvalidCredentials -> {
                      changeErrorMessage =
                        "Invalid Admin ID or current password (${outcome.remainingAttempts} attempts remaining)."
                    }
                    is PasswordChangeOutcome.LockedOut -> {
                      lockoutSeconds = outcome.remainingSeconds
                      changeErrorMessage = null
                    }
                    PasswordChangeOutcome.TooShort -> {
                      changeErrorMessage = "New password must be at least 6 characters."
                    }
                  }
                }
              },
              enabled =
                lockoutSeconds == 0 &&
                  changeAdminId.isNotBlank() &&
                  currentPassword.isNotEmpty() &&
                  newPassword.isNotEmpty() &&
                  confirmPassword.isNotEmpty(),
              modifier = Modifier.fillMaxWidth().height(52.dp).testTag("login_change_pw_submit"),
            ) {
              Text("Save New Password")
            }

            TextButton(
              onClick = {
                textToolbar.hide()
                focusManager.clearFocus(force = true)
                showChangePassword = false
                changeErrorMessage = null
              },
              modifier = Modifier.fillMaxWidth().height(48.dp).testTag("login_change_pw_cancel"),
            ) {
              Text(stringResource(R.string.login_back_to_login))
            }
          }
        }
      }
    }
  }
}
