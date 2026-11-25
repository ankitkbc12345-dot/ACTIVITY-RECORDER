package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppActivityDao {
  @Query("SELECT * FROM app_activity_events WHERE deviceId = :deviceId ORDER BY timestampMillis DESC")
  fun observeEventsForDevice(deviceId: String): Flow<List<AppActivityEventEntity>>

  @Query("SELECT * FROM app_activity_events WHERE deviceId = :deviceId ORDER BY timestampMillis DESC")
  suspend fun getEventsForDeviceOnce(deviceId: String): List<AppActivityEventEntity>

  @Query("SELECT * FROM app_activity_events ORDER BY timestampMillis DESC")
  suspend fun getAllEventsOnce(): List<AppActivityEventEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertEvent(event: AppActivityEventEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertEvents(events: List<AppActivityEventEntity>)

  @Query("DELETE FROM app_activity_events WHERE deviceId = :deviceId AND timestampMillis < :olderThanMillis")
  suspend fun deleteEventsOlderThan(deviceId: String, olderThanMillis: Long): Int

  @Query("DELETE FROM app_activity_events WHERE deviceId = :deviceId")
  suspend fun deleteEventsForDevice(deviceId: String): Int

  @Query("SELECT COUNT(*) FROM app_activity_events WHERE deviceId = :deviceId")
  fun observeEventCount(deviceId: String): Flow<Int>

  @Query("SELECT COUNT(*) FROM app_activity_events")
  suspend fun getTotalEventCount(): Int
}

@Dao
interface AppInventoryDao {
  @Query("SELECT * FROM app_inventory_snapshots WHERE deviceId = :deviceId ORDER BY appName COLLATE NOCASE ASC")
  fun observeInventoryForDevice(deviceId: String): Flow<List<AppInventorySnapshotEntity>>

  @Query("SELECT * FROM app_inventory_snapshots WHERE deviceId = :deviceId ORDER BY appName COLLATE NOCASE ASC")
  suspend fun getInventoryForDeviceOnce(deviceId: String): List<AppInventorySnapshotEntity>

  @Query("SELECT * FROM app_inventory_snapshots")
  suspend fun getAllInventoryOnce(): List<AppInventorySnapshotEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertInventory(items: List<AppInventorySnapshotEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertItem(item: AppInventorySnapshotEntity)

  @Query("DELETE FROM app_inventory_snapshots WHERE deviceId = :deviceId")
  suspend fun deleteInventoryForDevice(deviceId: String): Int

  @Query("SELECT COUNT(*) FROM app_inventory_snapshots")
  suspend fun getTotalInventoryCount(): Int
}

@Dao
interface CallLogDao {
  @Query("SELECT * FROM call_logs WHERE deviceId = :deviceId ORDER BY timestampMillis DESC")
  fun observeCallLogsForDevice(deviceId: String): Flow<List<CallLogEntity>>

  @Query("SELECT * FROM call_logs WHERE deviceId = :deviceId ORDER BY timestampMillis DESC")
  suspend fun getCallLogsForDeviceOnce(deviceId: String): List<CallLogEntity>

  @Query("SELECT * FROM call_logs ORDER BY timestampMillis DESC")
  suspend fun getAllCallLogsOnce(): List<CallLogEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertCallLogs(logs: List<CallLogEntity>)

  @Query("DELETE FROM call_logs WHERE deviceId = :deviceId AND timestampMillis < :olderThanMillis")
  suspend fun deleteCallLogsOlderThan(deviceId: String, olderThanMillis: Long): Int

  @Query("DELETE FROM call_logs WHERE deviceId = :deviceId")
  suspend fun deleteCallLogsForDevice(deviceId: String): Int

  @Query("SELECT COUNT(*) FROM call_logs")
  suspend fun getTotalCallLogCount(): Int
}

@Dao
interface PairedDeviceDao {
  @Query("SELECT * FROM paired_devices ORDER BY createdAtMillis DESC")
  fun observePairedDevices(): Flow<List<PairedDeviceEntity>>

  @Query("SELECT * FROM paired_devices ORDER BY createdAtMillis DESC")
  suspend fun getPairedDevicesOnce(): List<PairedDeviceEntity>

  @Query("SELECT * FROM paired_devices WHERE deviceId = :deviceId LIMIT 1")
  suspend fun getDeviceById(deviceId: String): PairedDeviceEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertDevice(device: PairedDeviceEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertDevices(devices: List<PairedDeviceEntity>)

  @Query("DELETE FROM paired_devices WHERE deviceId = :deviceId")
  suspend fun deleteDevice(deviceId: String): Int

  @Query("SELECT COUNT(*) FROM paired_devices")
  suspend fun getTotalPairedDevicesCount(): Int
}

@Dao
interface RemoteUninstallDao {
  @Query("SELECT * FROM remote_uninstall_requests ORDER BY timestampMillis DESC")
  fun observeRequests(): Flow<List<RemoteUninstallRequestEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertRequest(request: RemoteUninstallRequestEntity)

  @Query("DELETE FROM remote_uninstall_requests WHERE targetDeviceId = :deviceId")
  suspend fun deleteRequestsForDevice(deviceId: String): Int
}
