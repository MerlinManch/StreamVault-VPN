package com.streamvault.app.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.streamvault.app.MainActivity
import com.streamvault.app.R

private const val CHANNEL = "wireguard_vpn"
private const val NOTIFICATION_ID = 9417

// Both services share a notification ID; Android retains it until both have stopped.
internal fun Service.showWireGuardNotification() {
    val manager = getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) {
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.wg_title), NotificationManager.IMPORTANCE_LOW))
    }
    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val disconnect = PendingIntent.getService(this, 1,
        Intent(this, WireGuardForegroundService::class.java).setAction(WireGuardForegroundService.DISCONNECT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setContentTitle(getString(R.string.wg_title))
        .setContentText(getString(R.string.wg_notification))
        .setContentIntent(open).setOngoing(true)
        .addAction(0, getString(R.string.wg_disconnect), disconnect).build()
    if (Build.VERSION.SDK_INT >= 34) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
        startForeground(NOTIFICATION_ID, notification)
    }
}
