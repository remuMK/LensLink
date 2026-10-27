/*
 * CameraControlService.kt — keeps the	camera session (and the process)
 * alive in the background while bulk USB data streams through the app.
 *
 * The real work is owned by the app-scoped [com.mav.lenslink.camera.
 * CameraRepository]; this service only holds the foreground notification
 * that Android requires. Started/stopped around connect/disconnect.
 */
package com.mav.lenslink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.mav.lenslink.MainActivity
import com.mav.lenslink.R

class CameraControlService : Service() {

    companion object {
        private const val TAG = "CameraControlService"
        private const val CHANNEL_ID = "camera_link"
        private const val NOTIFICATION_ID = 0x1

        fun start(context: Context) {
            val intent = Intent(context, CameraControlService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraControlService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "camera foreground service destroyed")
        super.onDestroy()
    }

    private fun promoteForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_camera),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val launch = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 31)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notif_camera)
                    .setContentTitle(getString(R.string.notif_camera_title))
                    .setContentText(getString(R.string.notif_camera_text))
                    .setOngoing(true)
                    .setContentIntent(launch)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_notif_camera)
                    .setContentTitle(getString(R.string.notif_camera_title))
                    .setContentText(getString(R.string.notif_camera_text))
                    .setOngoing(true)
                    .setContentIntent(launch)
                    .build()
            }

        if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
