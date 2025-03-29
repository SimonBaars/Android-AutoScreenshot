package com.simonbrs.autoscreenshot.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

/**
 * Receiver that starts the ScreenshotService when the device boots.
 * Only starts the service if it was running when the device was shut down.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "AutoScreenshotPrefs"
        private const val KEY_SERVICE_RUNNING = "service_running"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed")
            
            // Check if service was running before device shutdown
            val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val wasRunning = prefs.getBoolean(KEY_SERVICE_RUNNING, false)
            
            if (wasRunning) {
                Log.d(TAG, "Service was running before shutdown, launching MainActivity")
                
                // We need to start the MainActivity to request the projection permission
                val launchIntent = Intent(context, Class.forName("com.simonbrs.autoscreenshot.MainActivity"))
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchIntent.putExtra("AUTO_START_SERVICE", true) // Signal that we should auto-start
                context.startActivity(launchIntent)
            } else {
                Log.d(TAG, "Service was not running before shutdown")
            }
        }
    }
} 