package com.example.data.remote

import android.content.Context
import com.example.R
import com.example.data.local.AppDatabase
import com.example.security.CryptoManager
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

data class CloudSyncUploadSummary(
  val deviceId: String,
  val snapshotCount: Int,
  val uploadedRecordsCount: Int,
)

class FirestoreSyncRepository(private val db: FirebaseFirestore) {

  constructor(context: Context) : this(
    FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    )
  )

  private val auth = Firebase.auth

  private fun requireUserId(): String {
    val uid = auth.currentUser?.uid
    if (uid.isNullOrBlank()) {
      throw IllegalStateException("Google Sign-In required before accessing Cloud Firestore.")
    }
    return uid
  }

  fun observeUserProfile(): Flow<SyncProfile?> = flow {
    val uid = requireUserId()
    val docRef = db.collection("users").document(uid)
    val path = docRef.path
    emitAll(
      docRef.snapshots()
        .map { snapshot ->
          if (snapshot.exists()) {
            snapshot.toObject(SyncProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
          } else {
            null
          }
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, OperationType.GET, path)
          }
          throw error
        }
    )
  }

  fun observeDeviceSnapshots(): Flow<List<CloudDeviceSnapshot>> = flow {
    val uid = requireUserId()
    val path = "users/$uid/device_snapshots"
    emitAll(
      db.collection("users")
        .document(uid)
        .collection("device_snapshots")
        .orderBy("updatedAt", Query.Direction.DESCENDING)
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(
            CloudDeviceSnapshot::class.java,
            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
          )
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  fun observeActivityRecords(deviceIdFilter: String? = null): Flow<List<CloudActivityRecord>> = flow {
    val uid = requireUserId()
    val path = "users/$uid/activity_records"
    val baseCollection = db.collection("users").document(uid).collection("activity_records")
    val query =
      if (!deviceIdFilter.isNullOrBlank()) {
        baseCollection.whereEqualTo("deviceId", sanitizeFirestoreId(deviceIdFilter, maxLen = 64)).limit(100)
      } else {
        baseCollection.orderBy("recordedAtMillis", Query.Direction.DESCENDING).limit(100)
      }
    emitAll(
      query
        .snapshots()
        .map { snapshot ->
          snapshot
            .toObjects(
              CloudActivityRecord::class.java,
              DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
            .sortedByDescending { it.recordedAtMillis }
        }
        .catch { error ->
          val firestoreError =
            generateSequence(error) { it.cause }
              .filterIsInstance<FirebaseFirestoreException>()
              .firstOrNull()
          if (firestoreError == null && error is CancellationException) throw error
          val exceptionToLog = firestoreError ?: (error as? Exception)
          if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, OperationType.LIST, path)
          }
          throw error
        }
    )
  }

  suspend fun syncLocalDeviceToCloud(
    localDb: AppDatabase,
    localDeviceId: String,
    localDeviceName: String,
    summaryNote: String?,
  ): Result<CloudSyncUploadSummary> {
    var currentPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}"
    return try {
      val uid = requireUserId()
      val user = auth.currentUser
      val safeDeviceId = sanitizeFirestoreId(localDeviceId, maxLen = 64)
      val safeDeviceName = localDeviceName.trim().take(80).ifEmpty { "This Device" }
      val safeModel = CryptoManager.getLocalDeviceModel().trim().take(80).ifEmpty { "Android Device" }
      val safeNote = summaryNote?.trim()?.take(500)?.takeIf { it.isNotEmpty() }

      val inventory = localDb.appInventoryDao().getInventoryForDeviceOnce(localDeviceId)
      val events = localDb.appActivityDao().getEventsForDeviceOnce(localDeviceId)
      val calls = localDb.callLogDao().getCallLogsForDeviceOnce(localDeviceId)

      // 1. Upsert CloudDeviceSnapshot document for this device
      val snapshotDocRef =
        db.collection("users").document(uid).collection("device_snapshots").document(safeDeviceId)
      currentPath = snapshotDocRef.path
      val existingSnapshot = snapshotDocRef.get().await()
      val snapshotModel =
        CloudDeviceSnapshot(
          userId = uid,
          deviceId = safeDeviceId,
          deviceName = safeDeviceName,
          deviceModel = safeModel,
          appCount = inventory.count { it.isInstalled }.toLong(),
          activityCount = events.size.toLong(),
          callLogCount = calls.size.toLong(),
          summaryNote = safeNote,
          syncStatus = CloudSyncStatus.SYNCED.name,
        )
      if (existingSnapshot.exists()) {
        snapshotDocRef.update(snapshotModel.toUpdateMap()).await()
      } else {
        snapshotDocRef.set(snapshotModel.toCreateMap()).await()
      }

      // 2. Upload up to 20 recent activity & call metadata records (with masked phone privacy)
      val recordsToSync = buildList<Pair<String, CloudActivityRecord>> {
        events.take(15).forEach { ev ->
          val recId = sanitizeFirestoreId("EV_${safeDeviceId}_${ev.id}")
          add(
            recId to
              CloudActivityRecord(
                userId = uid,
                deviceId = safeDeviceId,
                recordCategory = CloudRecordCategory.APP_EVENT.name,
                title = ev.appName.trim().take(120).ifEmpty { ev.packageName.take(120) },
                subtitle = ev.packageName.trim().take(160).ifEmpty { "com.unknown" },
                detail = "${ev.eventType} • ${ev.installerLabel}".take(240),
                eventDate = ev.eventDate.take(20),
                eventTime = ev.eventTime.take(20),
                recordedAtMillis = ev.timestampMillis.coerceAtLeast(0L),
              )
          )
        }
        calls.take(10).forEach { call ->
          val recId = sanitizeFirestoreId("CL_${safeDeviceId}_${call.callLogId}_${call.timestampMillis}")
          add(
            recId to
              CloudActivityRecord(
                userId = uid,
                deviceId = safeDeviceId,
                recordCategory = CloudRecordCategory.CALL_LOG.name,
                title = "${call.callType} Call",
                subtitle = "Duration: ${call.durationSeconds}s",
                detail = "Encrypted call log metadata synced",
                eventDate = call.callDate.take(20),
                eventTime = call.callTime.take(20),
                recordedAtMillis = call.timestampMillis.coerceAtLeast(0L),
              )
          )
        }
      }

      val recordsCollection = db.collection("users").document(uid).collection("activity_records")
      val existingRecordIds =
        recordsCollection.limit(100).get().await().documents.mapTo(HashSet()) { it.id }

      var uploadedRecords = 0
      recordsToSync.forEach { (recId, record) ->
        val docRef = recordsCollection.document(recId)
        currentPath = docRef.path
        if (recId in existingRecordIds) {
          docRef.update(record.toUpdateMap()).await()
        } else {
          docRef.set(record.toCreateMap()).await()
        }
        uploadedRecords += 1
      }

      // 3. Upsert SyncProfile document after snapshot count is updated
      val totalSnapshots =
        db.collection("users").document(uid).collection("device_snapshots").get().await().size().toLong()
      val userDocRef = db.collection("users").document(uid)
      currentPath = userDocRef.path
      val profileModel =
        SyncProfile(
          userId = uid,
          displayName = user?.displayName?.trim()?.take(80)?.ifEmpty { "Device Admin" } ?: "Device Admin",
          email = user?.email?.trim()?.take(160)?.takeIf { it.isNotEmpty() },
          activeDeviceId = safeDeviceId,
          totalSnapshots = totalSnapshots,
        )
      if (userDocRef.get().await().exists()) {
        userDocRef.update(profileModel.toUpdateMap()).await()
      } else {
        userDocRef.set(profileModel.toCreateMap()).await()
      }

      Result.success(
        CloudSyncUploadSummary(
          deviceId = safeDeviceId,
          snapshotCount = totalSnapshots.toInt(),
          uploadedRecordsCount = uploadedRecords,
        )
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, currentPath)
      Result.failure(e)
    }
  }

  suspend fun toggleSnapshotSyncStatus(snapshot: CloudDeviceSnapshot): Result<Unit> {
    var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/device_snapshots/${snapshot.id}"
    return try {
      val uid = requireUserId()
      val targetId = snapshot.id.ifBlank { sanitizeFirestoreId(snapshot.deviceId, maxLen = 64) }
      require(targetId.isNotBlank()) { "Snapshot ID cannot be blank." }
      val docRef = db.collection("users").document(uid).collection("device_snapshots").document(targetId)
      docPath = docRef.path
      val nextStatus =
        if (snapshot.resolvedStatus() == CloudSyncStatus.SYNCED) CloudSyncStatus.PAUSED.name
        else CloudSyncStatus.SYNCED.name
      docRef.update(
        mapOf(
          "syncStatus" to nextStatus,
          "updatedAt" to FieldValue.serverTimestamp(),
        )
      ).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, docPath)
      Result.failure(e)
    }
  }

  suspend fun deleteDeviceSnapshot(snapshotId: String): Result<Unit> {
    var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/device_snapshots/$snapshotId"
    return try {
      val uid = requireUserId()
      require(snapshotId.isNotBlank()) { "Snapshot ID cannot be blank." }
      val docRef = db.collection("users").document(uid).collection("device_snapshots").document(snapshotId)
      docPath = docRef.path
      docRef.delete().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, docPath)
      Result.failure(e)
    }
  }

  suspend fun deleteActivityRecord(recordId: String): Result<Unit> {
    var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/activity_records/$recordId"
    return try {
      val uid = requireUserId()
      require(recordId.isNotBlank()) { "Record ID cannot be blank." }
      val docRef = db.collection("users").document(uid).collection("activity_records").document(recordId)
      docPath = docRef.path
      docRef.delete().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, docPath)
      Result.failure(e)
    }
  }
}
