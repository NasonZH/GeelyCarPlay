package com.geely.carplay.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shilapi.xcertplay.AirPlayPersistence

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AirPlayPersistence.loadAutoStartOnBoot(context)) return
        context.startActivity(Intent(context, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
