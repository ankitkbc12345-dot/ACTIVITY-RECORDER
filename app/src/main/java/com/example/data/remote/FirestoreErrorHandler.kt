package com.example.data.remote

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
  CREATE("create"),
  UPDATE("update"),
  DELETE("delete"),
  LIST("list"),
  GET("get"),
  WRITE("write"),
}

fun handleFirestoreError(
  exception: Throwable,
  operationType: OperationType,
  path: String?,
): String {
  val causes = generateSequence(exception) { it.cause }
  val rootError =
    causes.firstOrNull { it is FirebaseFirestoreException }
      ?: causes.firstOrNull { it !is CancellationException }
      ?: exception

  val currentUser =
    try {
      FirebaseAuth.getInstance().currentUser
    } catch (_: Exception) {
      null
    }

  val providerInfoList =
    currentUser?.providerData?.map { provider ->
      JSONObject().apply {
        put("providerId", provider.providerId)
        put("email", provider.email)
      }
    } ?: emptyList()

  val authInfoJson =
    JSONObject().apply {
      put("userId", currentUser?.uid)
      put("email", currentUser?.email)
      put("emailVerified", currentUser?.isEmailVerified)
      put("tenantId", currentUser?.tenantId)
      put("providerInfo", JSONArray(providerInfoList))
    }

  val errorInfoJson =
    JSONObject().apply {
      put("error", rootError.message ?: rootError.toString())
      put("operationType", operationType.value)
      put("path", path)
      put("authInfo", authInfoJson)
    }

  val jsonString = errorInfoJson.toString()
  Log.e("FirestoreError", "Firestore Error: $jsonString")
  return jsonString
}

fun formatUserFacingFirestoreError(error: Throwable, projectId: String = "", databaseId: String = ""): String {
  val causes = generateSequence(error) { it.cause }
  val root = causes.firstOrNull { it is FirebaseFirestoreException } ?: error
  val message = root.message ?: "Cloud sync error occurred."
  return if (message.contains("Quota exceeded for quota metric", ignoreCase = true) &&
    projectId.isNotBlank() &&
    databaseId.isNotBlank()
  ) {
    "Firestore daily free-tier quota exceeded. Quota resets tomorrow or can be upgraded at https://console.firebase.google.com/project/$projectId/firestore/databases/$databaseId/data?openUpgradeDialog=true"
  } else if (message.contains("Quota exceeded", ignoreCase = true)) {
    "Firestore usage quota exceeded. Your daily quota resets tomorrow."
  } else {
    message
  }
}
