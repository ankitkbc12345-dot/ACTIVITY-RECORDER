package com.example.monitoring

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class BatteryStatusInfo(
  val percent: Int = 100,
  val isCharging: Boolean = false,
) {
  val statusLabel: String
    get() = if (isCharging) "$percent% • Charging" else "$percent% • Battery"
}

object BatteryUtils {

  fun getBatteryStatus(context: Context): BatteryStatusInfo {
    return try {
      val appCtx = context.applicationContext
      val batteryIntent = appCtx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
      val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
      val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
      val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
      val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

      val percentFromIntent =
        if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else null

      val manager = appCtx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
      val percentFromManager =
        manager
          ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
          ?.takeIf { it in 0..100 }

      val finalPercent = percentFromIntent ?: percentFromManager ?: 100
      val charging =
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
          status == BatteryManager.BATTERY_STATUS_FULL ||
          plugged != 0

      BatteryStatusInfo(percent = finalPercent, isCharging = charging)
    } catch (_: Exception) {
      BatteryStatusInfo()
    }
  }
}
