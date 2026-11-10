package com.fayor.justtalk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person

/** Message notifications: one per chat, new lines stack up in it until the chat is opened. */
class Notifier(private val ctx: Context) {
    private val nm = NotificationManagerCompat.from(ctx)
    /** chatId -> lines shown so far, so a notification grows instead of being replaced */
    private val lines = mutableMapOf<Int, MutableList<Pair<String, String>>>()

    init {
        val sys = ctx.getSystemService(NotificationManager::class.java)
        sys.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Сообщения", NotificationManager.IMPORTANCE_HIGH))
        // the always-on service notification: silent and hidden as low as Android allows
        sys.createNotificationChannel(NotificationChannel(CH_SERVICE, "Фоновая связь", NotificationManager.IMPORTANCE_MIN))
    }

    fun show(chatId: Int, title: String, who: String?, text: String) {
        if (!nm.areNotificationsEnabled()) return
        val list = lines.getOrPut(chatId) { mutableListOf() }
        list += (who ?: title) to text
        // MessagingStyle shows "Рома: текст" lines and groups chats nicely on the lock screen
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Я").build())
            .setConversationTitle(if (who != null) title else null)
            .setGroupConversation(who != null)
        list.takeLast(7).forEach { (name, line) ->
            style.addMessage(line, System.currentTimeMillis(), Person.Builder().setName(name).build())
        }
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notify)
            .setStyle(style)
            .setContentIntent(openChatIntent(ctx, chatId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try { nm.notify(TAG, chatId, n) } catch (_: SecurityException) { }
    }

    fun cancel(chatId: Int) { lines.remove(chatId); nm.cancel(TAG, chatId) }

    fun cancelAll() { lines.keys.toList().forEach(::cancel) }

    companion object {
        const val CH_MESSAGES = "messages"
        const val CH_SERVICE = "service"
        const val EXTRA_CHAT = "chat_id"
        // a tag keeps chat ids apart from the service notification id (1)
        private const val TAG = "chat"

        fun openChatIntent(ctx: Context, chatId: Int?): PendingIntent {
            val i = Intent(ctx, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (chatId != null) i.putExtra(EXTRA_CHAT, chatId)
            // request code = chatId so each chat keeps its own extra
            return PendingIntent.getActivity(ctx, chatId ?: 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
