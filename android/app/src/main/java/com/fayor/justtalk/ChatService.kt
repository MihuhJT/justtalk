package com.fayor.justtalk

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process (and so the WebSocket in Store) alive while the app is in the background.
 * No Google push: the server is our own mini-PC, so the phone simply stays connected.
 */
class ChatService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = NotificationCompat.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("JustTalk на связи")
            .setContentIntent(Notifier.openChatIntent(this, null))
            // not pinned: the notification can be swiped away and isn't kept above the others
            .setOngoing(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        Store.get(this).keepAlive()
        // if Android kills us for memory, it restarts the service later
        return START_STICKY
    }

    companion object {
        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ChatService::class.java))
            } catch (_: Exception) {
                // Android forbids starting from the background in some states; the next app open retries
            }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ChatService::class.java)) }
    }
}

/** Brings the connection back after a phone reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Store.get(ctx).loggedIn) ChatService.start(ctx)
    }
}
