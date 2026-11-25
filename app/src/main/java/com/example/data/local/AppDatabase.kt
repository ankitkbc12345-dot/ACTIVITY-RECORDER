package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [
    AppActivityEventEntity::class,
    AppInventorySnapshotEntity::class,
    CallLogEntity::class,
    PairedDeviceEntity::class,
    RemoteUninstallRequestEntity::class,
  ],
  version = 1,
  exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun appActivityDao(): AppActivityDao

  abstract fun appInventoryDao(): AppInventoryDao

  abstract fun callLogDao(): CallLogDao

  abstract fun pairedDeviceDao(): PairedDeviceDao

  abstract fun remoteUninstallDao(): RemoteUninstallDao

  companion object {
    const val DB_NAME = "device_recorder.db"

    @Volatile private var INSTANCE: AppDatabase? = null

    fun getInstance(context: Context): AppDatabase {
      return INSTANCE
        ?: synchronized(this) {
          INSTANCE
            ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME,
              )
              .build()
              .also { INSTANCE = it }
        }
    }

    fun getDatabaseSizeBytes(context: Context): Long {
      val dbFile = context.getDatabasePath(DB_NAME)
      val walFile = context.getDatabasePath("$DB_NAME-wal")
      val shmFile = context.getDatabasePath("$DB_NAME-shm")
      return listOf(dbFile, walFile, shmFile).sumOf { if (it.exists()) it.length() else 0L }
    }
  }
}
