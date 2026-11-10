package com.fayor.justtalk

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.sqrt

// ---------- audio ----------
// Opus at 16 kHz through MediaCodec (Android 10+ has a software encoder); one 20 ms packet per frame.
// Each frame starts with a codec byte: 1 = Opus, 2 = raw 8 kHz PCM (fallback if a phone has no Opus encoder).

private const val RATE = 16_000
private const val FRAME = RATE / 50          // 20 ms of samples
private const val CODEC_OPUS: Byte = 1
private const val CODEC_PCM8: Byte = 2

private class OpusEncoder {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1)
        f.setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * 2 * 4)
        configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private var pts = 0L
    private val info = MediaCodec.BufferInfo()

    fun encode(pcm: ByteArray, out: (ByteArray) -> Unit) {
        val i = codec.dequeueInputBuffer(10_000)
        if (i >= 0) {
            codec.getInputBuffer(i)!!.apply { clear(); put(pcm) }
            codec.queueInputBuffer(i, 0, pcm.size, pts, 0)
            pts += 20_000
        }
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 0)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (o < 0) break
            // the first buffers are the codec's own headers, not sound
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val b = codec.getOutputBuffer(o)!!
                out(ByteArray(info.size).also { b.position(info.offset); b.get(it) })
            }
            codec.releaseOutputBuffer(o, false)
        }
    }

    fun release() = runCatching { codec.stop(); codec.release() }
}

/** One person on the other side: packets in, sound out through its own AudioTrack (Android mixes the tracks). */
private class Peer(val userId: Int, private val onLevel: (Int, Float) -> Unit) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = true
    private var lastLevel = 0L

    init { thread(name = "call-peer-$userId") { loop() } }

    fun offer(packet: ByteArray) {
        queue.offer(packet)
        // the network delivered a burst: drop the oldest instead of falling behind for good (cap ~200 ms)
        while (queue.size > 10) queue.poll()
    }

    fun stop() { running = false; queue.offer(ByteArray(0)) }

    private fun track(rate: Int) = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2, rate / 5))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build().also { it.play() }

    private fun opusDecoder(): MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1)
        // OpusHead: magic, version 1, 1 channel, pre-skip 0, input rate, gain 0, mapping 0
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusHead".toByteArray()).put(1).put(1).putShort(0).putInt(RATE).putShort(0).put(0)
        f.setByteBuffer("csd-0", flipped(head))
        // codec delay and seek pre-roll, in nanoseconds
        f.setByteBuffer("csd-1", flipped(ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0)))
        f.setByteBuffer("csd-2", flipped(ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80_000_000)))
        configure(f, null, null, 0)
        start()
    }

    private fun loop() {
        var dec: MediaCodec? = null
        var out: AudioTrack? = null
        var outRate = 0
        var pts = 0L
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val p = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (p.isEmpty()) break
                val pcm: ByteArray? = when (p[0]) {
                    CODEC_OPUS -> {
                        val d = dec ?: opusDecoder().also { dec = it }
                        val i = d.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            d.getInputBuffer(i)!!.apply { clear(); put(p, 1, p.size - 1) }
                            d.queueInputBuffer(i, 0, p.size - 1, pts, 0)
                            pts += 20_000
                        }
                        var got: ByteArray? = null
                        while (true) {
                            val o = d.dequeueOutputBuffer(info, 5_000)
                            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                val r = d.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                if (r != outRate) { out?.release(); out = track(r); outRate = r }
                                continue
                            }
                            if (o < 0) break
                            if (info.size > 0) got = ByteArray(info.size).also { d.getOutputBuffer(o)!!.apply { position(info.offset); get(it) } }
                            d.releaseOutputBuffer(o, false)
                        }
                        if (outRate == 0) { out = track(48_000); outRate = 48_000 }
                        got
                    }
                    CODEC_PCM8 -> {
                        if (outRate != 8_000) { out?.release(); out = track(8_000); outRate = 8_000 }
                        p.copyOfRange(1, p.size)
                    }
                    else -> null
                }
                if (pcm != null && pcm.isNotEmpty()) {
                    out?.write(pcm, 0, pcm.size)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLevel > 120) { lastLevel = now; onLevel(userId, level(pcm)) }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { dec?.stop(); dec?.release() }
            runCatching { out?.stop(); out?.release() }
            onLevel(userId, 0f)
        }
    }
}

// through Buffer: older Androids have no ByteBuffer.flip() returning ByteBuffer
private fun flipped(b: ByteBuffer): ByteBuffer { (b as java.nio.Buffer).flip(); return b }

/** 0..1 loudness of 16-bit PCM, for the "speaking" ring. */
private fun level(pcm: ByteArray): Float {
    var sum = 0.0
    val n = pcm.size / 2
    for (i in 0 until n) {
        val s = (pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)
        sum += s.toDouble() * s
    }
    return (sqrt(sum / maxOf(n, 1)) / 6000.0).toFloat().coerceIn(0f, 1f)
}

/** Microphone -> server, server -> one Peer per speaker. Reconnects the audio socket by itself while the call lasts. */
private class CallAudio(private val ctx: Context, private val open: (WebSocketListener) -> WebSocket, private val onLevel: (Int, Float) -> Unit,
                        private val onConnected: (Boolean) -> Unit) {
    @Volatile var muted = false
    @Volatile private var running = true
    @Volatile private var ws: WebSocket? = null
    private val peers = ConcurrentHashMap<Int, Peer>()
    private val am = ctx.getSystemService(AudioManager::class.java)

    fun start() {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        connect()
        thread(name = "call-mic") { capture() }
    }

    private fun connect() {
        if (!running) return
        ws = open(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { onConnected(true) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                if (b.size < 6) return
                val uid = ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
                peers.getOrPut(uid) { Peer(uid, onLevel) }.offer(b.copyOfRange(4, b.size))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
        })
    }

    private fun lost(socket: WebSocket) {
        if (ws !== socket) return
        onConnected(false)
        // the server keeps my place for 20 s
        if (running) thread { SystemClock.sleep(1000); if (running && ws === socket) connect() }
    }

    @SuppressLint("MissingPermission")
    private fun capture() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 2 * 8))
        }.getOrNull() ?: return
        // the phone's own echo cancelling and noise cleanup, where it has them
        runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true }
        runCatching { NoiseSuppressor.create(rec.audioSessionId)?.enabled = true }
        runCatching { AutomaticGainControl.create(rec.audioSessionId)?.enabled = true }
        val enc = runCatching { OpusEncoder() }.getOrNull()
        val buf = ByteArray(FRAME * 2)
        var lastLevel = 0L
        try {
            rec.startRecording()
            while (running) {
                var got = 0
                while (got < buf.size && running) {
                    val n = rec.read(buf, got, buf.size - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < buf.size) continue
                if (muted) continue
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevel > 120) { lastLevel = now; onLevel(0, level(buf)) }
                val socket = ws ?: continue
                if (enc != null) enc.encode(buf) { pkt -> socket.send((byteArrayOf(CODEC_OPUS) + pkt).toByteString()) }
                else {
                    // no Opus on this phone: every second sample, 8 kHz raw
                    val half = ByteArray(1 + buf.size / 2).also { it[0] = CODEC_PCM8 }
                    for (i in 0 until buf.size / 4) { half[1 + i * 2] = buf[i * 4]; half[2 + i * 2] = buf[i * 4 + 1] }
                    socket.send(half.toByteString())
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { rec.stop() }
            rec.release()
            enc?.release()
        }
    }

    fun setSpeaker(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            else am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }

    fun stop() {
        running = false
        ws?.close(1000, null); ws = null
        peers.values.forEach { it.stop() }
        peers.clear()
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false }
        am.mode = AudioManager.MODE_NORMAL
    }
}

// ---------- state ----------

data class Ring(val callId: Int, val chatId: Int, val fromId: Int, val fromName: String)

/** The call this phone is in, and an incoming one ringing. Lives as long as the process, like the Store. */
object Calls {
    /** 0 = not in a call */
    var callId by mutableIntStateOf(0)
    var chatId by mutableIntStateOf(0)
    var connected by mutableStateOf(false)
    /** elapsedRealtime when someone else joined, 0 = still waiting */
    var talkingSince by mutableLongStateOf(0L)
    var muted by mutableStateOf(false)
    var speaker by mutableStateOf(false)
    var minimized by mutableStateOf(false)
    /** userId -> loudness 0..1; 0 = me */
    val levels = mutableStateMapOf<Int, Float>()
    var card by mutableStateOf<CallCard?>(null)
    var ring by mutableStateOf<Ring?>(null)
    /** the call (or -chatId for a new one) this phone asked to join: "call_joined" reaches all my phones, only this one connects */
    var pendingJoin = 0
    private var audio: CallAudio? = null
    private var tone: ToneGenerator? = null
    private var ringtone: Ringtone? = null
    private val main = MainScope()

    fun begin(ctx: Context, callId: Int, chatId: Int, open: (WebSocketListener) -> WebSocket) {
        if (this.callId == callId) return
        stopAudio()
        stopRinging(ctx)
        this.callId = callId; this.chatId = chatId
        connected = false; talkingSince = 0L; muted = false; speaker = false; minimized = false
        levels.clear()
        audio = CallAudio(ctx.applicationContext, open,
            onLevel = { uid, l -> main.launch { levels[uid] = l } },
            onConnected = { ok -> main.launch { connected = ok } }).also { it.start() }
        CallService.start(ctx)
    }

    /** Fresh state of my call from the chat card: starts the timer, plays the "гудки" while nobody else is in. */
    fun onCard(ctx: Context, c: CallCard, meId: Int?) {
        if (c.id != callId) return
        card = c
        val others = c.inCall.any { it != meId }
        if (others && talkingSince == 0L) {
            talkingSince = SystemClock.elapsedRealtime()
            stopTone()
            playSignal(ToneGenerator.TONE_PROP_ACK)
        }
        if (!others && talkingSince == 0L && c.status == "active") startTone() else stopTone()
    }

    private fun startTone() {
        if (tone != null) return
        tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) } }.getOrNull()
    }

    private fun stopTone() { tone?.stopTone(); tone?.release(); tone = null }

    private fun playSignal(signal: Int) {
        val sound = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 65) }.getOrNull() ?: return
        sound.startTone(signal, 180)
        main.launch { kotlinx.coroutines.delay(350); sound.release() }
    }

    fun toggleMute(send: (Boolean) -> Unit) { muted = !muted; audio?.muted = muted; send(muted) }

    fun toggleSpeaker() { speaker = !speaker; audio?.setSpeaker(speaker) }

    private fun stopAudio() { audio?.stop(); audio = null; stopTone() }

    fun end(ctx: Context) {
        if (callId != 0) playSignal(ToneGenerator.TONE_PROP_NACK)
        stopAudio()
        callId = 0; chatId = 0; card = null; connected = false; talkingSince = 0L; levels.clear()
        CallService.stop(ctx)
    }

    /** An incoming call: in-app screen on top, plus a ringing notification when the app is in the background. */
    fun incoming(ctx: Context, r: Ring, foreground: Boolean, title: String) {
        if (callId != 0) return   // already talking: the card in the chat is enough
        ring = r
        if (foreground) {
            ringtone = runCatching { RingtoneManager.getRingtone(ctx, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)) }.getOrNull()
                ?.apply { if (Build.VERSION.SDK_INT >= 28) isLooping = true; play() }
            ctx.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 800), 0))
        } else CallNotify.ringing(ctx, r, title)
    }

    fun stopRinging(ctx: Context) {
        ring = null
        ringtone?.stop(); ringtone = null
        ctx.getSystemService(Vibrator::class.java)?.cancel()
        CallNotify.cancelRing(ctx)
    }
}

// ---------- notifications and the foreground service ----------

object CallNotify {
    private const val CH_RING = "calls_ring"
    private const val CH_CALL = "calls_now"
    const val ID_RING = 77
    const val ID_CALL = 78
    const val EXTRA_CALL = "call_id"
    const val EXTRA_ANSWER = "answer"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RING, "Входящие звонки", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 800)
        })
        nm.createNotificationChannel(NotificationChannel(CH_CALL, "Идущий звонок", NotificationManager.IMPORTANCE_LOW))
    }

    private fun open(ctx: Context, callId: Int, answer: Boolean): PendingIntent {
        val i = Intent(ctx, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_CALL, callId).putExtra(EXTRA_ANSWER, answer)
        return PendingIntent.getActivity(ctx, if (answer) 7701 else 7700, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(ctx: Context, what: String): PendingIntent =
        PendingIntent.getBroadcast(ctx, what.hashCode(), Intent(ctx, CallActionReceiver::class.java).setAction(what), PendingIntent.FLAG_IMMUTABLE)

    fun ringing(ctx: Context, r: Ring, title: String) {
        channels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_RING)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(r.fromName)
            .setContentText(if (title != r.fromName) "Звонит в «$title»" else "Звонок в JustTalk")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setTimeoutAfter(50_000)
            .setFullScreenIntent(open(ctx, r.callId, false), true)
            .setContentIntent(open(ctx, r.callId, false))
            .addAction(0, "Отклонить", action(ctx, CallActionReceiver.DECLINE))
            .addAction(0, "Ответить", open(ctx, r.callId, true))
            .build()
        // ring until answered: the sound repeats
        n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_RING, n) }
    }

    fun cancelRing(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(ID_RING)

    fun ongoing(ctx: Context, title: String) = NotificationCompat.Builder(ctx, CH_CALL)
        .setSmallIcon(R.drawable.ic_notify)
        .setContentTitle("Звонок")
        .setContentText(title)
        .setOngoing(true)
        .setUsesChronometer(true)
        .setContentIntent(open(ctx, Calls.callId, false))
        .addAction(0, "Завершить", action(ctx, CallActionReceiver.HANG_UP))
        .build()
}

/** Keeps the microphone working while the screen is off or the app is in the background. */
class CallService : Service() {
    private var proximity: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CallNotify.channels(this)
        val store = Store.get(this)
        val title = store.chats.firstOrNull { it.id == Calls.chatId }?.title ?: "JustTalk"
        try {
            ServiceCompat.startForeground(this, CallNotify.ID_CALL, CallNotify.ongoing(this, title), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        // the screen goes dark at the ear, like a normal call
        if (proximity == null) proximity = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "jt:call").apply { runCatching { acquire(4 * 3600_000L) } }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { proximity?.release() }
        proximity = null
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) = runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, CallService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CallService::class.java)) }
    }
}

/** "Отклонить" on the ringing notification and "Завершить" on the call one. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val store = Store.get(ctx)
        when (intent.action) {
            DECLINE -> store.declineCall()
            HANG_UP -> store.hangUp()
        }
    }

    companion object {
        const val DECLINE = "com.fayor.justtalk.DECLINE"
        const val HANG_UP = "com.fayor.justtalk.HANG_UP"
    }
}

// ---------- UI ----------

/** Runs [action] once the microphone is allowed (asks the first time). */
@Composable
fun rememberMicGate(): ((() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var waiting by remember { mutableStateOf<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) waiting?.invoke() else Store.get(ctx).toast = "Без микрофона звонить не получится"
        waiting = null
    }
    return { action ->
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { waiting = action; ask.launch(Manifest.permission.RECORD_AUDIO) }
    }
}

@Composable
private fun CallButton(icon: ImageVector, label: String, bg: Color, tint: Color = Color.White, size: Int = 64, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(bg).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size((size * .4f).dp), tint = tint)
        }
        T(label, size = 12, color = C.mut)
    }
}

/** A face that glows while that person speaks. */
@Composable
private fun Speaker(store: Store, userId: Int, size: Int) {
    val meId = store.me?.id
    val lvl = Calls.levels[if (userId == meId) 0 else userId] ?: 0f
    val u = store.user(userId)
    val muted = userId in (Calls.card?.muted ?: emptyList())
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size((size + 12).dp).clip(CircleShape).border((2 + lvl * 4).dp, if (lvl > .08f && !muted) C.green else Color.Transparent, CircleShape).padding(6.dp),
            contentAlignment = Alignment.Center,
        ) { Avatar(u?.name ?: store.userName(userId), size.dp, false, photo = u?.photo()) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (muted) Icon(Ic.MicOff, "без звука", Modifier.size(12.dp), tint = C.red)
            T(if (userId == meId) "Я" else store.userName(userId), size = 13, color = C.ink, maxLines = 1)
        }
    }
}

/** The whole-screen call: who is in, timer, mute / speaker / hang up. Back folds it into a bar over the app. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallScreen(store: Store) {
    BackHandler { Calls.minimized = true }
    val chat = store.chats.firstOrNull { it.id == Calls.chatId }
    val tick by rememberTick()
    val card = Calls.card
    val meId = store.me?.id
    val people = (card?.inCall ?: listOfNotNull(meId)).sortedBy { it != meId }
    val status = when {
        !Calls.connected -> "Соединяю…"
        Calls.talkingSince == 0L -> if (chat?.kind == "group") "Ждём кентов…" else "Гудки…"
        else -> durationLabel((tick - Calls.talkingSince).toInt())
    }
    Box(
        Modifier.fillMaxSize().background(C.bg).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(C.s1).clickable { Calls.minimized = true }, contentAlignment = Alignment.Center) {
                    Icon(Ic.Back, "Свернуть звонок", Modifier.size(20.dp), tint = C.ink)
                }
            }
            Spacer(Modifier.height(20.dp))
            T(chat?.title ?: "Звонок", size = 24, weight = FontWeight.Bold)
            T(status, size = 15, color = if (Calls.talkingSince > 0) C.green else C.mut)
            Spacer(Modifier.weight(1f))
            if (chat?.kind == "group" || people.size > 2) FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                people.forEach { Speaker(store, it, 72) }
            } else {
                val other = chat?.dmPartner(store)
                if (other != null) Speaker(store, other.id, 132)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallButton(if (Calls.muted) Ic.MicOff else Ic.Mic, if (Calls.muted) "Включить" else "Микрофон",
                    if (Calls.muted) C.ink else C.s2, if (Calls.muted) C.bg else C.ink) { store.toggleMute() }
                CallButton(Ic.Speaker, if (Calls.speaker) "Динамик вкл" else "Динамик", if (Calls.speaker) C.ink else C.s2, if (Calls.speaker) C.bg else C.ink) {
                    Calls.toggleSpeaker()
                }
                CallButton(Ic.PhoneDown, "Выйти из звонка", C.red) { store.hangUp() }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Compact call controls in a reserved row above every app screen. */
@Composable
fun CallBar(store: Store, modifier: Modifier) {
    val tick by rememberTick()
    val title = store.chats.firstOrNull { it.id == Calls.chatId }?.title ?: "Звонок"
    val status = when {
        !Calls.connected -> "Соединение…"
        Calls.talkingSince == 0L -> "Ждём ответа…"
        else -> durationLabel((tick - Calls.talkingSince).toInt())
    }
    Row(
        modifier.statusBarsPadding().padding(horizontal = 16.dp).height(56.dp).widthIn(max = 288.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.weight(1f).height(44.dp).clip(CircleShape).background(C.green)
            .clickable(onClickLabel = "Вернуться в звонок") { Calls.minimized = false }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Ic.Phone, null, Modifier.size(18.dp), tint = Color.White)
            Column(Modifier.weight(1f)) {
                T(title, size = 13, weight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                T(status, size = 11, color = Color.White.copy(alpha = .85f), maxLines = 1)
            }
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (Calls.muted) C.ink else C.s2)
            .clickable(onClickLabel = if (Calls.muted) "Включить микрофон" else "Выключить микрофон") { store.toggleMute() }, contentAlignment = Alignment.Center) {
            Icon(if (Calls.muted) Ic.MicOff else Ic.Mic, if (Calls.muted) "Включить микрофон" else "Выключить микрофон",
                Modifier.size(20.dp), tint = if (Calls.muted) C.bg else C.ink)
        }
        Box(Modifier.size(44.dp).clip(CircleShape).background(C.red)
            .clickable(onClickLabel = "Выйти из звонка") { store.hangUp() }, contentAlignment = Alignment.Center) {
            Icon(Ic.PhoneDown, "Выйти из звонка", Modifier.size(20.dp), tint = Color.White)
        }
    }
}

/** Someone calls: answer or decline, over whatever screen is open. */
@Composable
fun IncomingCall(store: Store, r: Ring) {
    val chat = store.chats.firstOrNull { it.id == r.chatId }
    val caller = store.user(r.fromId)
    val mic = rememberMicGate()
    val pulse = rememberInfiniteTransition(label = "ring")
    val s by pulse.animateFloat(1f, 1.08f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "ringScale")
    Box(
        Modifier.fillMaxSize().background(C.bg).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(60.dp))
            Box(Modifier.scale(s)) { Avatar(caller?.name ?: r.fromName, 132.dp, true, photo = caller?.photo()) }
            Spacer(Modifier.height(20.dp))
            T(r.fromName, size = 26, weight = FontWeight.Bold)
            T(if (chat?.kind == "group") "зовёт в звонок «${chat.title}»" else "звонит тебе", size = 15, color = C.mut)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallButton(Ic.PhoneDown, "Отклонить", C.red, size = 72) { store.declineCall() }
                CallButton(Ic.Phone, "Ответить", C.green, size = 72) { mic { store.joinCall(r.callId, r.chatId) } }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
