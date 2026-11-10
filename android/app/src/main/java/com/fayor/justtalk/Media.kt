package com.fayor.justtalk

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsets
import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.annotation.SuppressLint
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okio.source
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

// ---------- files ----------

object Files {
    const val MAX = 100L * 1024 * 1024

    /** Display name and size of a picked file. */
    fun info(ctx: Context, uri: Uri): Pair<String, Long> = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use { c ->
            c.moveToFirst()
            c.getString(0).orEmpty() to c.getLong(1)
        }
    }.getOrDefault("файл" to 0L)

    fun ext(name: String) = name.substringAfterLast('.', "bin").take(8)

    /** The file streamed straight from the content provider. */
    fun body(ctx: Context, uri: Uri, size: Long): RequestBody = object : RequestBody() {
        override fun contentType() = (ctx.contentResolver.getType(uri) ?: "application/octet-stream").toMediaType()
        override fun contentLength() = if (size > 0) size else -1
        override fun writeTo(sink: BufferedSink) {
            ctx.contentResolver.openInputStream(uri)!!.source().use { sink.writeAll(it) }
        }
    }

    /** Wraps a body and reports the share sent so far. */
    class Progress(private val inner: RequestBody, private val onProgress: (Float) -> Unit) : RequestBody() {
        override fun contentType() = inner.contentType()
        override fun contentLength() = inner.contentLength()
        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var sent = 0L
            val counting = object : ForwardingSink(sink) {
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    sent += byteCount
                    if (total > 0) onProgress(sent.toFloat() / total)
                }
            }.buffer()
            inner.writeTo(counting)
            counting.flush()
        }
    }

    private val http by lazy { okhttp3.OkHttpClient.Builder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build() }

    /**
     * Saves a file from the server into Загрузки/JustTalk (no storage permission needed through MediaStore).
     * Returns its uri, or null if it failed.
     */
    suspend fun download(ctx: Context, url: String, name: String, onProgress: (Float) -> Unit): Uri? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val resolver = ctx.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name.ifBlank { "file" })
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/JustTalk")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        val ok = runCatching {
            http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("http ${r.code}")
                val body = r.body!!
                val total = body.contentLength()
                resolver.openOutputStream(uri)!!.use { out ->
                    val input = body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }.isSuccess
        if (!ok) { resolver.delete(uri, null, null); return@withContext null }
        resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        uri
    }

    /** Opens a saved file in whatever app handles it. */
    fun open(ctx: Context, uri: Uri): Boolean = runCatching {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, ctx.contentResolver.getType(uri))
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

/** Where a message's media plays from: the local file while mine uploads, the server afterwards. */
fun mediaUrl(store: Store, src: String) = if (src.contains("://")) src else store.server.trimEnd('/') + "/media/" + src + "?t=" + store.token

// ---------- playback ----------

/** One voice message or circle plays at a time; [current] is the message key. */
object Player {
    var current by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)
    var position by mutableFloatStateOf(0f)
    private var mp: MediaPlayer? = null
    private var ticker: Job? = null
    private val scope = MainScope()

    fun toggle(ctx: Context, key: String, src: String, surface: Surface? = null, onVideoSize: (Int, Int) -> Unit = { _, _ -> }) {
        val p = mp
        if (current == key && p != null) {
            if (p.isPlaying) { p.pause(); playing = false } else { p.start(); playing = true }
            return
        }
        stop()
        current = key
        val player = MediaPlayer()
        mp = player
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        player.setOnVideoSizeChangedListener { _, w, h -> onVideoSize(w, h) }
        player.setOnPreparedListener { it.start(); playing = true; tick() }
        player.setOnCompletionListener { stop() }
        player.setOnErrorListener { _, _, _ -> stop(); true }
        try {
            player.setDataSource(ctx, Uri.parse(src))
            surface?.let(player::setSurface)
            player.prepareAsync()
        } catch (_: Exception) { stop() }
    }

    fun stop() {
        ticker?.cancel()
        mp?.release()
        mp = null; current = null; playing = false; position = 0f
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                mp?.let { if (it.duration > 0) position = it.currentPosition.toFloat() / it.duration }
                delay(50)
            }
        }
    }
}

internal fun msgKey(m: Msg) = m.clientId ?: m.id.toString()

/** "1:07" */
internal fun durationLabel(ms: Int): String { val s = (ms + 500) / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

/** Voice message: play button, loudness bars that fill while playing, length. */
@Composable
fun VoiceBody(store: Store, m: Msg, ink: Color, accent: Color) {
    val ctx = LocalContext.current
    val key = msgKey(m)
    val mine = Player.current == key
    val shown = if (mine) Player.position else 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(accent).clickable { m.media?.let { Player.toggle(ctx, key, mediaUrl(store, it)) } },
            contentAlignment = Alignment.Center,
        ) { Icon(if (mine && Player.playing) Ic.Pause else Ic.Play, "Слушать", Modifier.size(18.dp), tint = if (accent == C.acc) C.onAcc else C.acc) }
        Column {
            Wave(m.wave, shown, ink, Modifier.width(150.dp).height(26.dp))
            T(durationLabel(if (mine) (m.duration * Player.position).toInt() else m.duration), size = 11, color = ink.copy(alpha = .65f))
        }
    }
}

@Composable
private fun Wave(wave: String, played: Float, ink: Color, modifier: Modifier) {
    val bars = wave.map { (it - '0').coerceIn(0, 9) }.ifEmpty { List(30) { 3 } }
    Canvas(modifier) {
        val w = size.width / bars.size
        bars.forEachIndexed { i, v ->
            val h = (size.height * (.15f + .85f * v / 9f))
            val on = i.toFloat() / bars.size < played
            drawRoundRect(
                if (on) ink else ink.copy(alpha = .35f),
                topLeft = Offset(i * w + w * .2f, (size.height - h) / 2), size = Size(w * .6f, h), cornerRadius = CornerRadius(w),
            )
        }
    }
}

/** Video circle: the first frame until tapped, then plays with sound and a progress ring. */
@Composable
fun CircleBody(store: Store, m: Msg) {
    val ctx = LocalContext.current
    val key = msgKey(m)
    val playingThis = Player.current == key
    var surface by remember { mutableStateOf<Surface?>(null) }
    var view by remember { mutableStateOf<TextureView?>(null) }
    val size = 220.dp
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).clip(CircleShape).background(C.s2).clickable {
            val src = m.media ?: return@clickable
            Player.toggle(ctx, key, mediaUrl(store, src), surface) { w, h -> view?.let { cropToSquare(it, w, h) } }
        }) {
            AndroidView({ c ->
                TextureView(c).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) { surface = Surface(t) }
                        override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean {
                            if (Player.current == key) Player.stop()
                            surface = null; return true
                        }
                        override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
                    }
                    view = this
                }
            }, Modifier.fillMaxSize())
            // the poster sits above the video until it starts
            if (!playingThis) {
                val img = m.thumb.ifEmpty { null }?.let { rememberPhoto(it, 480) }
                if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.align(Alignment.Center).size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                    Icon(Ic.Play, "Смотреть", Modifier.size(20.dp), tint = Color.White)
                }
            }
        }
        if (playingThis) {
            val p = Player.position
            Canvas(Modifier.size(size)) {
                drawArc(C.acc, -90f, 360f * p, false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        T(durationLabel(if (playingThis) (m.duration * Player.position).toInt() else m.duration), size = 11, color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, bottom = 10.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = .45f)).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/** A TextureView stretches the video to its own square; this scales it back so the middle is cropped instead. */
private fun cropToSquare(v: TextureView, w: Int, h: Int) {
    if (w <= 0 || h <= 0) return
    v.post {
        val m = Matrix()
        val cx = v.width / 2f
        val cy = v.height / 2f
        if (w > h) m.setScale(w.toFloat() / h, 1f, cx, cy) else m.setScale(1f, h.toFloat() / w, cx, cy)
        v.setTransform(m)
    }
}

/** File in a bubble: icon, name, size; tap saves it to Загрузки. While mine uploads, the size shows progress. */
@Composable
fun FileBody(store: Store, m: Msg, ink: Color, accent: Color) {
    val ctx = LocalContext.current
    // my upload on its way, or a download in progress
    val up = m.clientId?.let { store.progress[it] } ?: store.progress["dl${m.id}"]
    val saved = store.saved[m.id]
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = m.id > 0 && m.media != null && up == null) {
            if (saved != null && Files.open(ctx, saved)) return@clickable
            store.downloadFile(m)
        }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(accent), contentAlignment = Alignment.Center) {
            Icon(Ic.File, null, Modifier.size(20.dp), tint = if (accent == C.acc) C.onAcc else C.acc)
        }
        Column(Modifier.widthIn(max = 190.dp)) {
            T(m.mediaName.ifBlank { "Файл" }, weight = FontWeight.Medium, color = ink, maxLines = 2)
            T(when {
                up != null -> "${(up * 100).roundToInt()}% из ${sizeLabel(m.mediaSize)}"
                saved != null -> "${sizeLabel(m.mediaSize)} · открыть"
                else -> "${sizeLabel(m.mediaSize)} · скачать"
            }, size = 12, color = ink.copy(alpha = .65f))
        }
    }
}

/** A small ring over a pending voice or circle while it uploads. */
@Composable
fun UploadRing(store: Store, m: Msg) {
    val p = m.clientId?.let { store.progress[it] } ?: return
    Canvas(Modifier.size(18.dp)) {
        drawArc(C.mut.copy(alpha = .3f), 0f, 360f, false, style = Stroke(2.dp.toPx()))
        drawArc(C.acc, -90f, 360f * p.coerceAtLeast(.03f), false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ---------- recording ----------

/** State of a recording in progress, drawn by the input row and the circle overlay. */
class Rec(val circle: Boolean) {
    var started by mutableLongStateOf(0L)        // 0 = not started yet (camera warming up)
    var locked by mutableStateOf(false)
    var dragX by mutableFloatStateOf(0f)          // finger shift left, for "slide to cancel"
    var dragY by mutableFloatStateOf(0f)          // finger shift up, for the lock
    /** a finish asked for before the recorder was ready: true = send */
    var pending: Boolean? = null
    val elapsed get() = if (started == 0L) 0L else SystemClock.elapsedRealtime() - started
    val limit get() = if (circle) 30_000L else 180_000L
}

/** Voice: AAC in .m4a, mono, small; samples loudness for the bars. */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    val file = File(ctx.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
    private val levels = mutableListOf<Int>()

    fun start(): Boolean = runCatching {
        rec = MediaRecorder(ctx).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(48_000)
            setOutputFile(file)
            prepare()
            start()
        }
    }.isSuccess

    fun sample() { rec?.let { levels += runCatching { it.maxAmplitude }.getOrDefault(0) } }

    /** false = too short or broken: nothing to send. */
    fun stop(): Boolean {
        val ok = runCatching { rec?.stop() }.isSuccess
        rec?.release(); rec = null
        return ok && file.length() > 0
    }

    fun discard() { stop(); file.delete() }

    /** 40 bars as digits 0-9, scaled to the loudest moment. */
    fun wave(): String {
        if (levels.isEmpty()) return ""
        val n = 40
        val chunks = List(n) { i -> levels.subList(i * levels.size / n, maxOf((i + 1) * levels.size / n, i * levels.size / n + 1).coerceAtMost(levels.size)) }
        val peaks = chunks.map { it.maxOrNull() ?: 0 }
        val top = (peaks.maxOrNull() ?: 1).coerceAtLeast(1)
        return peaks.joinToString("") { (it * 9 / top).toString() }
    }
}

/**
 * The button right of the input: tap switches voice / circle, hold records,
 * hold and slide up locks (recording goes on without the finger), slide left cancels.
 */
@Composable
fun RecordButton(store: Store, chatId: Int, replyTo: () -> Int, rec: Rec?, onRec: (Rec?) -> Unit) {
    val ctx = LocalContext.current
    var circleMode by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val lockAt = with(density) { 70.dp.toPx() }
    val cancelAt = with(density) { 110.dp.toPx() }
    val current by rememberUpdatedState(rec)
    // where the button is on the screen: the finger is measured in screen terms, so a layout shift under it isn't a swipe
    var origin by remember { mutableStateOf(Offset.Zero) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    fun granted(circle: Boolean) = listOfNotNull(Manifest.permission.RECORD_AUDIO, if (circle) Manifest.permission.CAMERA else null)
        .all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    Box(
        Modifier.size(48.dp).onGloballyPositioned { origin = it.positionInWindow() }.clip(CircleShape).background(if (rec != null) C.red else C.glass)
            .pointerInput(chatId) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // a short tap switches the mode, holding starts recording
                    val up = withTimeoutOrNull(220) { waitForUpOrCancellation() }
                    if (up != null) { if (current == null) circleMode = !circleMode; return@awaitEachGesture }
                    if (current != null) return@awaitEachGesture
                    if (!granted(circleMode)) {
                        perms.launch(listOfNotNull(Manifest.permission.RECORD_AUDIO, if (circleMode) Manifest.permission.CAMERA else null).toTypedArray())
                        return@awaitEachGesture
                    }
                    val r = Rec(circleMode)
                    onRec(r)
                    val start = down.position + origin
                    while (true) {
                        val ev = awaitPointerEvent()
                        val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // finger up: send, unless locked (then the buttons decide)
                            if (!r.locked) finish(r, send = true)
                            break
                        }
                        val total = change.position + origin - start
                        change.consume()
                        if (r.locked) continue
                        r.dragX = (-total.x).coerceAtLeast(0f)
                        r.dragY = (-total.y).coerceAtLeast(0f)
                        if (r.dragY > lockAt) { r.locked = true; r.dragX = 0f; r.dragY = 0f }
                        else if (r.dragX > cancelAt) { finish(r, send = false); break }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (rec?.circle ?: circleMode) Ic.Camera else Ic.Mic, if (circleMode) "Кружок" else "Голосовое", Modifier.size(22.dp),
            tint = if (rec != null) Color.White else C.ink)
    }
}

/** Set by the recorders: what to do when the finger lifts or a button is pressed. */
private val finishers = mutableMapOf<Rec, (Boolean) -> Unit>()

fun finish(r: Rec, send: Boolean) {
    val f = finishers.remove(r)
    if (f != null) f(send) else r.pending = send
}

/** Registers how a recording ends; runs it at once if the finger already lifted. */
private fun onFinish(r: Rec, f: (Boolean) -> Unit) {
    finishers[r] = f
    r.pending?.let { finish(r, it) }
}

/** Runs a voice recording while [rec] is set; registers how it ends. */
@Composable
fun VoiceSession(store: Store, chatId: Int, rec: Rec, replyTo: () -> Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val recorder = remember(rec) { VoiceRecorder(ctx) }
    DisposableEffect(rec) {
        if (!recorder.start()) { store.toast = "Микрофон занят"; onDone() } else rec.started = SystemClock.elapsedRealtime()
        onFinish(rec) { send ->
            val ms = rec.elapsed.toInt()
            if (send && ms >= 700 && recorder.stop()) store.sendVoice(chatId, recorder.file, ms, recorder.wave(), replyTo())
            else recorder.discard()
            onDone()
        }
        onDispose { if (finishers.remove(rec) != null) recorder.discard() }
    }
    LaunchedEffect(rec) {
        while (true) {
            delay(100)
            recorder.sample()
            if (rec.elapsed >= rec.limit) { finish(rec, send = true); break }
        }
    }
}

/** Full-screen circle recorder: round camera preview, time ring, camera flip. */
@SuppressLint("MissingPermission")
@Composable
fun CircleSession(store: Store, chatId: Int, rec: Rec, replyTo: () -> Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var front by remember { mutableStateOf(true) }
    val preview = remember { PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val capture = remember {
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
            .setTargetVideoEncodingBitRate(1_200_000)
            .build()
        VideoCapture.Builder(recorder).setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).build()
    }
    val file = remember { File(ctx.cacheDir, "circle-${System.currentTimeMillis()}.mp4") }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var torchOn by remember { mutableStateOf(false) }
    var send by remember { mutableStateOf(false) }

    // (re)binds on flip; the recording is persistent, so it goes on with the other camera
    LaunchedEffect(front) {
        val cams = ProcessCameraProvider.awaitInstance(ctx)
        provider = cams
        val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        cams.unbindAll()
        runCatching {
            camera = cams.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, p, capture)
            zoomRatio = 1f
            camera?.cameraControl?.setZoomRatio(zoomRatio)
            if (front) torchOn = false else if (torchOn) camera?.cameraControl?.enableTorch(true)
        }.onFailure { store.toast = "Камера недоступна"; onDone(); return@LaunchedEffect }
        if (recording == null) {
            recording = capture.output.prepareRecording(ctx, FileOutputOptions.Builder(file).build())
                .withAudioEnabled().asPersistentRecording()
                .start(ContextCompat.getMainExecutor(ctx)) { e ->
                    when (e) {
                        is VideoRecordEvent.Start -> rec.started = SystemClock.elapsedRealtime()
                        is VideoRecordEvent.Finalize -> {
                            val ms = (e.recordingStats.recordedDurationNanos / 1_000_000).toInt()
                            if (send && !e.hasError() && ms >= 700) store.sendCircle(chatId, file, ms, firstFrame(file), replyTo()) else file.delete()
                            cams.unbindAll()
                            onDone()
                        }
                    }
                }
        }
    }
    DisposableEffect(rec) {
        onFinish(rec) { s ->
            send = s
            // the Finalize event sends (or drops) the file and closes the overlay
            val r = recording
            if (r != null) r.stop() else onDone()
        }
        onDispose {
            if (finishers.remove(rec) != null) send = false
            camera?.cameraControl?.enableTorch(false)
            recording?.stop()
            provider?.unbindAll()
        }
    }
    LaunchedEffect(rec) {
        while (true) {
            delay(100)
            if (rec.elapsed >= rec.limit) { finish(rec, send = true); break }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .75f))
            // Read the pinch on the recording overlay itself. PreviewView's child intercepts
            // Android touch events, so a listener attached to PreviewView never sees them.
            .pointerInput(camera) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val active = camera
                            val state = active?.cameraInfo?.zoomState?.value
                            val factor = event.calculateZoom()
                            if (active != null && state != null && factor.isFinite() && factor != 1f) {
                                zoomRatio = (zoomRatio * factor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                                active.cameraControl.setZoomRatio(zoomRatio)
                            }
                        }
                        // Keep the chat underneath inert while the recording overlay is open.
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val size = 280.dp
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            AndroidView({ preview }, Modifier.size(size).clip(CircleShape))
            val now by rememberTick()
            val p = (if (rec.started == 0L) 0f else (now - rec.started).toFloat() / rec.limit).coerceIn(0f, 1f)
            Canvas(Modifier.size(size + 10.dp)) {
                drawArc(Color.White.copy(alpha = .2f), 0f, 360f, false, style = Stroke(4.dp.toPx()))
                drawArc(C.red, -90f, 360f * p, false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Box(
            Modifier.align(Alignment.Center).offset { IntOffset(0, 200.dp.roundToPx()) }
                .size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = .15f)).clickable {
                    camera?.cameraControl?.enableTorch(false); torchOn = false; front = !front
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Ic.Flip, "Сменить камеру", Modifier.size(22.dp), tint = Color.White) }
        if (!front && camera?.cameraInfo?.hasFlashUnit() == true) Box(
            Modifier.align(Alignment.Center).offset { IntOffset(70.dp.roundToPx(), 200.dp.roundToPx()) }
                .size(48.dp).clip(CircleShape).background(if (torchOn) C.acc else Color.White.copy(alpha = .15f))
                .clickable { torchOn = !torchOn; camera?.cameraControl?.enableTorch(torchOn) },
            contentAlignment = Alignment.Center,
        ) { Icon(Ic.Flash, "Вспышка", Modifier.size(23.dp), tint = Color.White) }
        // the recording bar (time, Отмена, Отправить) is drawn again above the dim, in the same place as in the chat, so it stays bright
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.systemBars).imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.size(48.dp))
            RecordingBar(rec, Modifier.weight(1f))
            Box(Modifier.size(48.dp).clip(CircleShape).background(C.red).clickable { finish(rec, send = true) }, contentAlignment = Alignment.Center) {
                Icon(Ic.Send, "Отправить кружок", Modifier.size(22.dp), tint = Color.White)
            }
        }
    }
}

/** Redraws a few times a second while recording. */
@Composable
internal fun rememberTick() = androidx.compose.runtime.produceState(SystemClock.elapsedRealtime()) { while (true) { delay(100); value = SystemClock.elapsedRealtime() } }

/** First frame of a recorded circle as a small JPEG poster. */
private fun firstFrame(file: File): ByteArray? = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(file.path)
        val bmp = r.getFrameAtTime(0) ?: return null
        val side = minOf(bmp.width, bmp.height)
        val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(sq, 360, 360, true)
        ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    } finally { r.release() }
}.getOrNull()

/**
 * Replaces the input while recording: red dot and time; holding — "‹ влево — отмена" and a lock hint above;
 * locked — Отмена and Отправить buttons.
 */
@Composable
fun RecordingBar(rec: Rec, modifier: Modifier) {
    val now by rememberTick()
    val time = durationLabel(if (rec.started == 0L) 0 else (now - rec.started).toInt())
    Row(
        modifier.height(48.dp).clip(CircleShape).background(C.glass).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val blink = (now / 500) % 2 == 0L
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (blink) C.red else C.red.copy(alpha = .3f)))
        T(time, weight = FontWeight.Medium)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (rec.locked) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                T("Отмена", color = C.red, modifier = Modifier.clickable { finish(rec, send = false) })
                Box(Modifier.size(34.dp).clip(CircleShape).background(C.acc).clickable { finish(rec, send = true) }, contentAlignment = Alignment.Center) {
                    Icon(Ic.Send, "Отправить", Modifier.size(18.dp), tint = C.onAcc)
                }
            } else T("‹ влево — отмена", size = 13, color = C.mut, modifier = Modifier.offset { IntOffset(-rec.dragX.roundToInt() / 2, 0) })
        }
    }
}

/** Lock hint above the record button while holding: slide up to keep recording hands-free. */
@Composable
fun LockHint(rec: Rec?, modifier: Modifier) {
    AnimatedVisibility(rec != null && !rec.locked, modifier, enter = fadeIn(), exit = fadeOut()) {
        val up = rec?.dragY ?: 0f
        Column(
            Modifier.offset { IntOffset(0, -up.roundToInt() / 2) }.width(40.dp).clip(CircleShape).background(C.s1).padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Ic.Lock, "Закрепить запись", Modifier.size(18.dp), tint = C.ink)
            Icon(Ic.Up, null, Modifier.size(14.dp), tint = C.mut)
        }
    }
}
