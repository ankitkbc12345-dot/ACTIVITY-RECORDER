package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.ui.MainViewModel
import com.example.ui.auth.GoogleSignInButton
import com.example.ui.auth.attemptAutoSignIn
import com.example.ui.auth.safeCurrentFirebaseUser

@Composable
fun GoogleAuthGateScreen(
  viewModel: MainViewModel,
  onAuthSuccess: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var isCheckingAutoAuth by remember { mutableStateOf(true) }

  LaunchedEffect(Unit) {
    if (safeCurrentFirebaseUser(context) != null) {
      viewModel.refreshGoogleUser()
      onAuthSuccess()
      return@LaunchedEffect
    }
    attemptAutoSignIn(
      context = context,
      credentialManager = credentialManager,
      onAuthSuccess = {
        isCheckingAutoAuth = false
        viewModel.refreshGoogleUser()
        onAuthSuccess()
      },
      onUnauthenticated = {
        isCheckingAutoAuth = false
      },
      scope = scope,
    )
  }

  Surface(
    modifier = Modifier.fillMaxSize().testTag("google_auth_gate_screen"),
    color = MaterialTheme.colorScheme.background,
  ) {
    Box(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
      ) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primaryContainer,
          modifier = Modifier.size(88.dp),
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
              imageVector = Icons.Default.Security,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(44.dp),
            )
          }
        }

        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "Device Recorder",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
          )
          Text(
            text = "Secure device monitoring, call logs, activity tracking, and cloud sync. Please sign in with your Google account to continue.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }

        if (!errorMessage.isNullOrBlank()) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = errorMessage ?: "",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(16.dp),
              textAlign = TextAlign.Center,
            )
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isCheckingAutoAuth) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Text(
              text = "Checking Google account...",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          GoogleSignInButton(
            onAuthSuccess = {
              viewModel.refreshGoogleUser()
              onAuthSuccess()
            },
            onAuthError = { err ->
              errorMessage = err
            },
          )
        }

        OutlinedButton(
          onClick = {
            viewModel.continueAsGuest()
            onAuthSuccess()
          },
          modifier = Modifier.fillMaxWidth().height(52.dp).testTag("continue_with_guest_button"),
        ) {
          Icon(Icons.Default.Person, contentDescription = null)
          Spacer(modifier = Modifier.width(8.dp))
          Text("Continue with Guest")
        }
      }
    }
  }
}
