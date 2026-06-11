package com.digipalvega

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Auto-launches the player on device boot.
 *
 * Handles three boot intents:
 *  - BOOT_COMPLETED          — standard boot (all Android versions)
 *  - LOCKED_BOOT_COMPLETED   — direct-boot mode (FireOS 8 fires this early in boot)
 *  - QUICKBOOT_POWERON       — HTC/Amazon fast-boot variant
 *
 * Why BootLaunchService instead of startActivity() directly:
 * Android 10+ (API 29) and FireOS 7/8 block background startActivity() for
 * non-system apps — the broadcast fires but the activity start is silently
 * dropped. Starting a foreground service from a receiver IS allowed, and the
 * service can then open the activity from a foreground context.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED" &&
            action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }

        val serviceIntent = Intent(context, BootLaunchService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            // Fallback: try direct activity start (may silently fail on API 29+
            // but better than nothing if the service itself fails to start).
            try {
                val launch = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(launch)
            } catch (e2: Exception) {
                // Nothing more we can do.
            }
        }
    }
}
