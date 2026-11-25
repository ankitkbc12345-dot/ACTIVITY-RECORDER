package com.example.monitoring

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DateTimeUtils {
  fun formatDate(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    sdf.timeZone = TimeZone.getDefault()
    return sdf.format(Date(millis))
  }

  fun formatTime(millis: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
    sdf.timeZone = TimeZone.getDefault()
    return sdf.format(Date(millis))
  }

  fun formatDateTime(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    sdf.timeZone = TimeZone.getDefault()
    return sdf.format(Date(millis))
  }

  fun formatDuration(seconds: Long): String {
    if (seconds <= 0L) return "0s"
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
      hrs > 0 -> "${hrs}h ${mins}m ${secs}s"
      mins > 0 -> "${mins}m ${secs}s"
      else -> "${secs}s"
    }
  }
}
