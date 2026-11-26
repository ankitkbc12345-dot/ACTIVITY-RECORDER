package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [
    AppActivityEventEntity::class,
    AppInventorySnapshotEntity::class,
    CallLogEntity::class,
    DeviceUnlockEventEntity::class,
    PairedDeviceEntity::class,
    RemoteUninstallRequestEntity::class,
  ],
  version = 2,
  exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun appActivityDao(): AppActivityDao

  abstract fun appInventoryDao(): AppInventoryDao

  abstract fun callLogDao(): CallLogDao

  abstract fun unlockEventDao(): UnlockEventDao

  abstract fun pairedDeviceDao(): PairedDeviceDao

  abstract fun remoteUninstallDao(): RemoteUninstallDao

  companion object {
    const val DB_NAME = "device_recorder.db"

    val MIGRATION_1_2 =
      object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL("ALTER TABLE app_activity_events ADD COLUMN batteryPercent INTEGER DEFAULT NULL")
          db.execSQL("ALTER TABLE call_logs ADD COLUMN contactName TEXT NOT NULL DEFAULT ''")
          db.execSQL("ALTER TABLE call_logs ADD COLUMN batteryPercent INTEGER DEFAULT NULL")
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `device_unlock_events` (
              `id` TEXT NOT NULL,
              `deviceId` TEXT NOT NULL,
              `unlockMethod` TEXT NOT NULL,
              `unlockCategory` TEXT NOT NULL,
              `batteryPercent` INTEGER,
              `unlockDate` TEXT NOT NULL,
              `unlockTime` TEXT NOT NULL,
              `timestampMillis` INTEGER NOT NULL,
              `source` TEXT NOT NULL,
              `isImportedSnapshot` INTEGER NOT NULL,
              `importedAtMillis` INTEGER,
              PRIMARY KEY(`id`)
            )
            """.trimIndent()
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_device_unlock_events_deviceId` ON `device_unlock_events` (`deviceId`)"
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_device_unlock_events_timestampMillis` ON `device_unlock_events` (`timestampMillis`)"
          )
          db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_device_unlock_events_unlockDate` ON `device_unlock_events` (`unlockDate`)"
          )
        }
      }

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
              .addMigrations(MIGRATION_1_2)
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
