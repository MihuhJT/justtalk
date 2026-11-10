package com.fayor.justtalk

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts

/** The only real activity; the launcher icons are aliases of it (see AppIcon). */
class HomeActivity : ComponentActivity() {
    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = Store.get(this)
        setContent { App(store) }
        // Android 13+ asks once; a refusal just means no message notifications
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        openFromNotification(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromNotification(intent)
    }

    private fun openFromNotification(intent: Intent?) {
        val chatId = intent?.getIntExtra(Notifier.EXTRA_CHAT, -1) ?: -1
        if (chatId > 0 && store.loggedIn) store.openChat(chatId)
        // 0.15: the ringing notification (tap = show the call screen, "Ответить" = answer right away)
        val callId = intent?.getIntExtra(CallNotify.EXTRA_CALL, -1) ?: -1
        if (callId > 0 && store.loggedIn) {
            CallNotify.cancelRing(this)
            store.fromCallNotification(callId, intent?.getBooleanExtra(CallNotify.EXTRA_ANSWER, false) == true)
        }
    }

    override fun onStart() {
        super.onStart()
        store.onStart()
    }

    override fun onStop() {
        super.onStop()
        store.onStop()
        // switched only once the app is in the background: changing launcher components can close the visible task
        AppIcon.sync(this, store.accent)
    }
}
