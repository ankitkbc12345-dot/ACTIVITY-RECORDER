package com.example.ui.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

@SuppressLint("DiscouragedApi")
internal fun Context.googleWebClientId(): String? {
  return try {
    val id = resources.getIdentifier("default_web_client_id", "string", packageName)
    if (id != 0) getString(id).takeIf { it.isNotBlank() } else null
  } catch (_: Exception) {
    null
  }
}

fun isFirebaseInitialized(context: Context): Boolean {
  return try {
    if (FirebaseApp.getApps(context).isNotEmpty()) {
      true
    } else {
      FirebaseApp.initializeApp(context) != null
    }
  } catch (_: Exception) {
    false
  }
}

fun safeCurrentFirebaseUser(context: Context? = null): FirebaseUser? {
  return try {
    if (context != null && !isFirebaseInitialized(context)) {
      null
    } else {
      Firebase.auth.currentUser
    }
  } catch (_: Exception) {
    null
  }
}

fun attemptAutoSignIn(
  context: Context,
  credentialManager: CredentialManager,
  onAuthSuccess: () -> Unit,
  onUnauthenticated: () -> Unit,
  scope: CoroutineScope,
) {
  if (!isFirebaseInitialized(context)) {
    onUnauthenticated()
    return
  }
  if (safeCurrentFirebaseUser(context) != null) {
    onAuthSuccess()
    return
  }
  val clientId = context.googleWebClientId()
  if (clientId.isNullOrBlank()) {
    onUnauthenticated()
    return
  }
  val googleIdOption =
    GetGoogleIdOption.Builder()
      .setFilterByAuthorizedAccounts(true)
      .setServerClientId(clientId)
      .setAutoSelectEnabled(true)
      .build()
  val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()
  scope.launch {
    try {
      val cred = credentialManager.getCredential(context, request).credential
      if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
        Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
        onAuthSuccess()
      } else {
        onUnauthenticated()
      }
    } catch (_: Exception) {
      onUnauthenticated()
    }
  }
}

@Composable
fun GoogleSignInButton(
  onAuthSuccess: () -> Unit,
  onAuthError: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }
  var isLoading by remember { mutableStateOf(false) }

  Button(
    onClick = {
      isLoading = true
      onGoogleSignInClicked(
        context = context,
        credentialManager = credentialManager,
        onAuthSuccess = {
          isLoading = false
          onAuthSuccess()
        },
        onAuthError = { msg ->
          isLoading = false
          onAuthError(msg)
        },
        scope = scope,
        onAuthCancelled = { isLoading = false },
      )
    },
    enabled = !isLoading,
    modifier = modifier.fillMaxWidth().height(52.dp).testTag("cloud_google_signin_button"),
  ) {
    if (isLoading) {
      CircularProgressIndicator(
        modifier = Modifier.size(22.dp),
        color = MaterialTheme.colorScheme.onPrimary,
        strokeWidth = 2.dp,
      )
    } else {
      Icon(Icons.Default.AccountCircle, contentDescription = null)
      Spacer(modifier = Modifier.width(8.dp))
      Text("Sign in with Google")
    }
  }
}

fun onGoogleSignInClicked(
  context: Context,
  credentialManager: CredentialManager,
  onAuthSuccess: () -> Unit,
  onAuthError: (String) -> Unit,
  scope: CoroutineScope,
  onAuthCancelled: () -> Unit = {},
) {
  if (!isFirebaseInitialized(context)) {
    onAuthError("Google Sign-In is unavailable in this build. Tap Continue with Guest to use all app features.")
    return
  }
  val clientId = context.googleWebClientId()
  if (clientId.isNullOrBlank()) {
    onAuthError("Google Sign-In configuration missing. Tap Continue with Guest to use all app features.")
    return
  }
  val activity = context.findActivity()
  if (activity == null) {
    onAuthError("Activity context required for Google Sign-In")
    return
  }
  val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
  val request = GetCredentialRequest.Builder().addCredentialOption(signInOption).build()
  scope.launch {
    try {
      val cred = credentialManager.getCredential(activity, request).credential
      if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
        Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
        onAuthSuccess()
      } else {
        onAuthError("Unexpected credential type returned by Google Sign-In.")
      }
    } catch (e: GetCredentialCancellationException) {
      Log.w("Auth", "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
      onAuthCancelled()
    } catch (e: Exception) {
      Log.e("Auth", "Google Sign-In failed", e)
      onAuthError(e.localizedMessage ?: "Google Sign-In failed")
    }
  }
}

fun signOutFromGoogle(
  credentialManager: CredentialManager,
  scope: CoroutineScope,
  onSignOutComplete: () -> Unit = {},
) {
  scope.launch {
    withContext(NonCancellable) {
      try {
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
      } catch (e: Exception) {
        Log.w("Auth", "Failed to clear credential state: ${e.message}")
      } finally {
        runCatching { Firebase.auth.signOut() }
        onSignOutComplete()
      }
    }
  }
}
