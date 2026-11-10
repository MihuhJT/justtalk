package com.fayor.justtalk

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player as M3Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.abs

/** The opened "Смотреть вместе": video on top, who watches, controls, the chat underneath. */
data class WatchScreen(val chatId: Int, val watchId: Int) : Screen

/** What every player can do; positions in seconds. */
private interface VideoCtl {
    fun play(at: Double?)
    fun pause(at: Double?)
    fun seek(t: Double)
    fun position(cb: (Double) -> Unit)
    fun release()
}

/** YouTube and VK: their own embed in a WebView (the page comes from our server, see /player), driven through JS. */
@SuppressLint("SetJavaScriptEnabled")
private class WebCtl(ctx: Context, url: String, private val onReady: (Double, String) -> Unit, private val onState: (Int, Double) -> Unit,
                     private val onError: (String) -> Unit) : VideoCtl {
    private val main = Handler(Looper.getMainLooper())
    val view = WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(android.graphics.Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // the sync starts playback without a tap
        settings.mediaPlaybackRequiresUserGesture = false
        webViewClient = WebViewClient()
        webChromeClient = WebChromeClient()
        addJavascriptInterface(object {
            // called on a WebView thread: hop to the main one
            @JavascriptInterface fun onReady(duration: Double, title: String) { main.post { this@WebCtl.onReady(duration, title) } }
            @JavascriptInterface fun onState(state: Int, time: Double) { main.post { this@WebCtl.onState(state, time) } }
            @JavascriptInterface fun onError(code: String) { main.post { this@WebCtl.onError(code) } }
        }, "JT")
        loadUrl(url)
    }

    private fun js(code: String) = view.evaluateJavascript(code, null)
    override fun play(at: Double?) = js("cmd('play', ${at ?: "null"})")
    override fun pause(at: Double?) = js("cmd('pause', ${at ?: "null"})")
    override fun seek(t: Double) = js("cmd('seek', $t)")
    override fun position(cb: (Double) -> Unit) = view.evaluateJavascript("pos()") { r -> r?.toDoubleOrNull()?.takeIf { it >= 0 }?.let(cb) }
    override fun release() { view.loadUrl("about:blank"); view.destroy() }
}

/** Own videos uploaded to the server: ExoPlayer, our controls only. */
private class FileCtl(ctx: Context, url: String, onReady: (Double) -> Unit, onEnded: (Double) -> Unit, onError: () -> Unit) : VideoCtl {
    val player = ExoPlayer.Builder(ctx).build().apply {
        setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        addListener(object : M3Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == M3Player.STATE_READY) onReady(duration.coerceAtLeast(0) / 1000.0)
                if (state == M3Player.STATE_ENDED) onEnded(currentPosition / 1000.0)
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { onError() }
        })
        prepare()
    }
    override fun play(at: Double?) { at?.let { player.seekTo((it * 1000).toLong()) }; player.play() }
    override fun pause(at: Double?) { player.pause(); at?.let { player.seekTo((it * 1000).toLong()) } }
    override fun seek(t: Double) = player.seekTo((t * 1000).toLong())
    override fun position(cb: (Double) -> Unit) = cb(player.currentPosition / 1000.0)
    override fun release() = player.release()
}

/**
 * Keeps this phone's player where the room is. The server holds (playing, pos at server time);
 * remote changes are applied, my own taps are sent; echoes of my own events only refresh the state.
 */
private class Sync(private val send: (String, Double) -> Unit) {
    var ctl: VideoCtl? = null
    var ready = false
    var duration = 0.0
    var playing by mutableStateOf(false)
    private var pos = 0.0
    private var at = 0L                 // elapsedRealtime when [pos] was true
    /** native player events right after we moved it ourselves are ours, not the user's */
    private var quietUntil = 0L

    fun expected(): Double = pos + if (playing) (SystemClock.elapsedRealtime() - at) / 1000.0 else 0.0

    fun setState(p: Boolean, position: Double) { playing = p; pos = position; at = SystemClock.elapsedRealtime() }

    /** From the server: [mine] = an echo of my own tap, the player is already there. */
    fun remote(p: Boolean, position: Double, mine: Boolean) {
        setState(p, position)
        if (!mine) apply()
    }

    fun apply() {
        val c = ctl ?: return
        if (!ready) return
        quietUntil = SystemClock.elapsedRealtime() + 1500
        if (playing) c.play(expected()) else c.pause(expected())
    }

    // my own controls
    fun togglePlay() {
        val c = ctl ?: return
        c.position { now ->
            val next = !playing
            val target = if (next && duration > 0 && now >= duration - .3) 0.0 else now
            setState(next, target)
            quietUntil = SystemClock.elapsedRealtime() + 1500
            if (next) c.play(if (target != now) target else null) else c.pause(null)
            send(if (next) "play" else "pause", target)
        }
    }

    fun ended(t: Double) { setState(false, t); send("pause", t) }

    fun seekTo(t: Double) {
        val target = t.coerceAtLeast(0.0)
        setState(playing, target)
        quietUntil = SystemClock.elapsedRealtime() + 1500
        ctl?.seek(target)
        send("seek", target)
    }

    fun seekBy(d: Double) { ctl?.position { seekTo(it + d) } }

    /** YouTube / VK: someone tapped the embed's own play / pause; 1 = playing, 2 = paused (YouTube states). */
    fun native(state: Int, time: Double) {
        if (SystemClock.elapsedRealtime() < quietUntil) return
        when {
            state == 0 && playing -> ended(time)
            state == 1 && !playing -> { setState(true, time); send("play", time) }
            state == 2 && playing -> { setState(false, time); send("pause", time) }
            state == 1 && abs(time - expected()) > 3 -> { setState(true, time); send("seek", time) }
        }
    }

    /** Every few seconds: drifted more than 2.5 s from the room -> jump back in. */
    fun check() {
        val c = ctl ?: return
        if (!ready || !playing || SystemClock.elapsedRealtime() < quietUntil) return
        c.position { now -> if (abs(now - expected()) > 2.5) { quietUntil = SystemClock.elapsedRealtime() + 1500; c.seek(expected()) } }
    }
}

private fun timeLabel(s: Double): String {
    val t = s.toInt().coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

@Composable
fun WatchRoom(store: Store, chatId: Int, watchId: Int) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    var full by remember { mutableStateOf(false) }
    val floating = store.watchFloating
    val minimize = {
        full = false
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        store.watchFloating = true
        store.openChat(chatId)
    }
    val close = {
        if (full) { full = false; activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        else minimize()
    }
    BackHandler(enabled = !floating, onBack = close)
    val card = store.messages[chatId].orEmpty().firstNotNullOfOrNull { (it.card as? WatchCard)?.takeIf { w -> w.id == watchId } } ?: store.watchCards[watchId]
    var duration by remember { mutableDoubleStateOf(0.0) }
    var now by remember { mutableDoubleStateOf(0.0) }
    var title by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val sync = remember { Sync { action, pos -> store.sendWatch(watchId, action, pos) } }
    LaunchedEffect(card?.ended) { if (card?.ended == true) store.closeWatch() }

    // join the room, get the state, listen to the others
    DisposableEffect(watchId) {
        store.onWatch[watchId] = { ev -> sync.remote(ev.optBoolean("playing"), ev.optDouble("pos", 0.0), ev.optInt("by") == store.me?.id) }
        store.sendWatch(watchId, "join", 0.0)
        onDispose {
            store.onWatch.remove(watchId)
            store.sendWatch(watchId, "leave", 0.0)
            sync.ctl?.release(); sync.ctl = null
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    LaunchedEffect(watchId) {
        store.loadWatch(watchId)?.let { w -> sync.setState(w.playing, w.pos); sync.apply() }
        while (true) {
            delay(1000)
            sync.ctl?.position { now = it }
            sync.check()
        }
    }

    var showFloatingControls by remember { mutableStateOf(false) }
    LaunchedEffect(showFloatingControls, floating) {
        if (showFloatingControls && floating) { delay(6000); showFloatingControls = false }
    }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
        val density = LocalDensity.current
        val maxWidthDp = (maxWidth - 24.dp).coerceAtLeast(1.dp)
        val maxHeightDp = maxHeight
        val maxWindowWidth = minOf(maxWidthDp, maxHeightDp * (16f / 9f))
        val minWindowWidth = minOf(200.dp, maxWindowWidth)
        var windowWidth by remember { mutableStateOf(280.dp) }
        val width = windowWidth.coerceIn(minWindowWidth, maxWindowWidth)
        val windowHeight = width * (9f / 16f)
        var x by remember { mutableStateOf(12f) }
        var y by remember { mutableStateOf(100f) }
        val maxX = with(density) { (maxWidth - width).toPx().coerceAtLeast(0f) }
        val maxY = with(density) { (maxHeight - windowHeight).toPx().coerceAtLeast(0f) }
        val gestures = Modifier.semantics {
            contentDescription = "Плавающее видео"
            customActions = listOf(
                CustomAccessibilityAction("Увеличить") { windowWidth = (windowWidth + 32.dp).coerceIn(minWindowWidth, maxWindowWidth); true },
                CustomAccessibilityAction("Уменьшить") { windowWidth = (windowWidth - 32.dp).coerceIn(minWindowWidth, maxWindowWidth); true },
                CustomAccessibilityAction("Показать кнопки") { showFloatingControls = true; true },
            )
        }.pointerInput(maxWindowWidth, maxHeight) {
            detectTransformGestures { _, pan, zoom, _ ->
                showFloatingControls = false
                windowWidth = (windowWidth.coerceIn(minWindowWidth, maxWindowWidth) * zoom).coerceIn(minWindowWidth, maxWindowWidth)
                val boundsX = with(density) { (maxWidth - windowWidth).toPx().coerceAtLeast(0f) }
                val boundsY = with(density) { (maxHeight - windowWidth * (9f / 16f)).toPx().coerceAtLeast(0f) }
                x = (x + pan.x).coerceIn(0f, boundsX)
                y = (y + pan.y).coerceIn(0f, boundsY)
            }
        }.pointerInput(Unit) {
            detectTapGestures(onTap = { showFloatingControls = !showFloatingControls })
        }
        val frame = if (floating) Modifier.offset { IntOffset(x.coerceIn(0f, maxX).roundToInt(), y.coerceIn(0f, maxY).roundToInt()) }
            .width(width).shadow(12.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).border(1.dp, C.line, RoundedCornerShape(16.dp))
            else Modifier.fillMaxSize()
        Column(frame.background(if (full || floating) Color.Black else C.bg)) {
            if (!full && !floating) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = close), contentAlignment = Alignment.Center) {
                    Icon(Ic.Back, "Назад", Modifier.size(22.dp), tint = C.ink)
                }
                Column(Modifier.weight(1f)) {
                    T(card?.title?.ifBlank { null } ?: title.ifBlank { "Смотрим вместе" }, weight = FontWeight.SemiBold, maxLines = 1)
                    T(sourceLabel(card?.source ?: "") + " · " + (store.chats.firstOrNull { it.id == chatId }?.title ?: ""), size = 12, color = C.mut, maxLines = 1)
                }
                T("В окно", size = 12, color = C.acc,
                    modifier = Modifier.clip(CircleShape).clickable(onClickLabel = "Плавающее окно", onClick = minimize).padding(8.dp))
                if (card != null && card.ownerId == store.me?.id) T("Закончить", size = 13, color = C.red,
                    modifier = Modifier.clip(CircleShape).clickable { store.endWatch(watchId); store.closeWatch() }.padding(8.dp))
            }
            // the player
            Box(if (full) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize().background(Color.Black))
                if (card != null) {
                    val base = store.server.trimEnd('/')
                    when (card.source) {
                        "youtube", "vk" -> AndroidView(
                            factory = { c ->
                                val url = if (card.source == "youtube") "$base/player/yt?v=${card.src}"
                                else card.src.split("_").let { p -> "$base/player/vk?oid=${p[0]}&id=${p.getOrElse(1) { "" }}&hash=${p.getOrElse(2) { "" }}" }
                                WebCtl(c, url,
                                    onReady = { d, t -> duration = d; sync.duration = d; if (t.isNotBlank()) title = t; sync.ready = true; sync.apply() },
                                    onState = { st, t -> sync.native(st, t) },
                                    onError = { e ->
                                        error = when {
                                            card.source != "youtube" -> "VK Видео не открылось: возможно, видео закрыто"
                                            // 101 / 150 / 152: the owner allows it only on youtube.com (often music videos)
                                            e in setOf("101", "150", "152") -> "Автор разрешил смотреть это видео только на самом YouTube. Возьмите другое"
                                            else -> "YouTube не грузится ($e). Возможно, нужен VPN"
                                        }
                                    },
                                ).also { sync.ctl = it }.view
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> AndroidView(
                            factory = { c ->
                                val fc = FileCtl(c, mediaUrl(store, card.src),
                                    onReady = { d -> duration = d; sync.duration = d; if (!sync.ready) { sync.ready = true; sync.apply() } },
                                    onEnded = { t -> sync.ended(t) },
                                    onError = { error = "Видео не загрузилось. Проверь подключение и доступность файла" })
                                sync.ctl = fc
                                (android.view.LayoutInflater.from(c).inflate(R.layout.watch_player, null) as PlayerView).apply {
                                    player = fc.player; setShutterBackgroundColor(android.graphics.Color.BLACK)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (error.isNotEmpty()) T(error, size = 13, color = Color.White, modifier = Modifier.padding(20.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = .7f)).padding(12.dp))
                if (floating) {
                    Box(Modifier.fillMaxSize().then(gestures))
                    if (showFloatingControls) {
                        Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f)).clickable {
                                showFloatingControls = false
                                store.watchFloating = false; store.screen = WatchScreen(chatId, watchId)
                                full = true; activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }, contentAlignment = Alignment.Center) { Icon(Ic.Expand, "На весь экран", Modifier.size(20.dp), tint = Color.White) }
                            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f)).clickable { store.closeWatch() },
                                contentAlignment = Alignment.Center) { Icon(Ic.Close, "Закрыть видео", Modifier.size(20.dp), tint = Color.White) }
                        }
                        T("Смотрят: ${card?.viewers?.distinct()?.size ?: 0}", size = 11, color = Color.White,
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
            // controls: everyone's taps move everyone
            if (!floating) Controls(sync, now, duration, full, card?.viewers?.distinct()?.size ?: 0) {
                full = !full
                activity?.requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            if (!full && !floating) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val viewers = card?.viewers.orEmpty()
                    if (viewers.isNotEmpty()) Faces(store, viewers, 26.dp)
                    T("Смотрят: ${viewers.distinct().size}", size = 12, color = C.mut,
                        modifier = Modifier.weight(1f))
                    if (Calls.callId == 0) T("Созвониться", size = 13, color = C.green, weight = FontWeight.Medium,
                        modifier = Modifier.clip(CircleShape).clickable { store.startCall(chatId) }.padding(8.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
                MiniChat(store, chatId, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Controls(sync: Sync, now: Double, duration: Double, full: Boolean, viewers: Int, onFull: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(if (full) Color.Black else C.bg).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (full) T("Смотрят: $viewers", size = 12, color = Color.White)
        if (duration > 0) {
            var width by remember { mutableStateOf(1) }
            var drag by remember { mutableStateOf<Double?>(null) }
            val shown = drag ?: now
            Box(
                Modifier.fillMaxWidth().height(22.dp).onSizeChanged { width = it.width }
                    .seekGestures(duration, { width }, { drag = it }, { t -> drag = null; sync.seekTo(t) }),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(C.s2))
                Box(Modifier.fillMaxWidth((shown / duration).toFloat().coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(C.acc))
            }
            Row { T(timeLabel(shown), size = 11, color = C.mut, modifier = Modifier.weight(1f)); T(timeLabel(duration), size = 11, color = C.mut) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            T("−10", size = 14, weight = FontWeight.SemiBold, color = if (full) Color.White else C.ink,
                modifier = Modifier.clip(CircleShape).clickable { sync.seekBy(-10.0) }.padding(12.dp))
            Box(Modifier.size(52.dp).clip(CircleShape).background(C.acc).clickable { sync.togglePlay() }, contentAlignment = Alignment.Center) {
                Icon(if (sync.playing) Ic.Pause else Ic.Play, if (sync.playing) "Пауза" else "Играть", Modifier.size(22.dp), tint = C.onAcc)
            }
            T("+10", size = 14, weight = FontWeight.SemiBold, color = if (full) Color.White else C.ink,
                modifier = Modifier.clip(CircleShape).clickable { sync.seekBy(10.0) }.padding(12.dp))
            Spacer(Modifier.size(8.dp))
            T(if (full) "С чатом" else "На весь экран", size = 12, color = C.mut, modifier = Modifier.clip(CircleShape).clickable(onClick = onFull).padding(8.dp))
        }
    }
}

/** Tap or drag along the bar to choose a moment; the seek is sent on release. */
private fun Modifier.seekGestures(duration: Double, width: () -> Int, onDrag: (Double) -> Unit, onDone: (Double) -> Unit): Modifier {
    fun at(x: Float) = (x / width().coerceAtLeast(1)).coerceIn(0f, 1f) * duration
    var last = 0.0
    return pointerInput(duration) {
        detectHorizontalDragGestures(
            onDragStart = { o -> last = at(o.x); onDrag(last) },
            onDragEnd = { onDone(last) },
            onDragCancel = { onDone(last) },
        ) { change, _ -> last = at(change.position.x); onDrag(last) }
    }.pointerInput(duration, "tap") { detectTapGestures { o -> onDone(at(o.x)) } }
}

/** The chat under the video: same bubbles, a simple input. */
@Composable
private fun MiniChat(store: Store, chatId: Int, modifier: Modifier) {
    val list = store.messages[chatId].orEmpty()
    val meId = store.me?.id
    val chat = store.chats.firstOrNull { it.id == chatId }
    val state = rememberLazyListState()
    LaunchedEffect(list.lastOrNull()?.id) { if (list.isNotEmpty()) state.animateScrollToItem(0) }
    var text by remember { mutableStateOf("") }
    Column(modifier) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = state, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Spacer(Modifier.height(6.dp)) }
            items(list.takeLast(60).asReversed(), key = { it.clientId ?: it.id }) { m ->
                if (m.senderId == 0) T(m.text, size = 12, color = C.mut, modifier = Modifier.fillMaxWidth().padding(4.dp))
                else Bubble(store, m, m.senderId == meId, true, chat?.othersRead ?: 0, Modifier,
                    if (chat?.kind == "group" && m.senderId != meId) store.userName(m.senderId) else null)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(C.glass).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Field(text, { text = it.take(4000) }, "Комментарий…", Modifier.fillMaxWidth(),
                    androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                    onSend = { store.send(chatId, text); text = "" })
            }
            RoundButton(Ic.Send, "Отправить", size = 44.dp, bg = if (text.isBlank()) C.s2 else C.acc, tint = C.onAcc) { store.send(chatId, text); text = "" }
        }
    }
}
