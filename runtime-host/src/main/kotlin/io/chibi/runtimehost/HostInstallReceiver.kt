package io.chibi.runtimehost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/** Receives the result of [ExtensionInstaller] package sessions. */
class HostInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) ?: return
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i("ChibiHost/Installer", "Extension installed: $message")
        } else {
            Log.e("ChibiHost/Installer", "Extension install failed ($status): $message")
        }
    }
}
