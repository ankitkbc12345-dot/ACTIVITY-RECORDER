package com.example.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

class PackageInstallStatusReceiver : BroadcastReceiver() {
  companion object {
    const val ACTION_INSTALL_STATUS = "com.example.updates.ACTION_INSTALL_STATUS"
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_INSTALL_STATUS) return
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    when (status) {
      PackageInstaller.STATUS_PENDING_USER_ACTION -> {
        val confirmIntent =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
          } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
          }
        confirmIntent?.let {
          it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          context.startActivity(it)
        }
      }
      PackageInstaller.STATUS_SUCCESS -> {
        // App update successfully installed by system
      }
    }
  }
}
