package com.fayor.justtalk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Photo loading without extra libraries: OkHttp keeps the files on disk (the server marks them immutable),
 * decoded bitmaps live in a small memory cache.
 */
object Photos {
    private lateinit var ctx: Context
    private lateinit var http: OkHttpClient
    private lateinit var base: String
    // 0.11.2: the server may close files to strangers, so photos are fetched with the kent's token
    private var token = ""
    // ~1/8 of the app memory for decoded photos, counted in bytes
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun init(context: Context, server: String, authToken: String) {
        ctx = context.applicationContext
        base = server.trimEnd('/')
        token = authToken
        if (!::http.isInitialized) {
            http = OkHttpClient.Builder().cache(Cache(File(ctx.cacheDir, "photos"), 200L * 1024 * 1024)).build()
        }
    }

    /** [src] is a server file name, or a content:// (file:// when pasted) uri while my own photo is still uploading. */
    suspend fun load(src: String, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$src@$maxSide"
        mem.get(key)?.let { return@withContext it }
        val bytes = runCatching {
            if (src.startsWith("content:") || src.startsWith("file:")) ctx.contentResolver.openInputStream(Uri.parse(src))!!.use { it.readBytes() }
            // 0.15: outside pictures (YouTube previews) come as full links
            else if (src.startsWith("http")) http.newCall(Request.Builder().url(src).build()).execute().use { r -> if (!r.isSuccessful) null else r.body?.bytes() }
            else http.newCall(Request.Builder().url("$base/media/$src").header("Authorization", token).build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.bytes()
            }
        }.getOrNull() ?: return@withContext null
        decode(bytes, maxSide)?.also { mem.put(key, it) }
    }

    // decode at roughly the size it is shown: a full 1600px photo in every bubble would eat memory
    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    suspend fun rotatedAvatar(uri: Uri, turns: Int): Bitmap? = withContext(Dispatchers.Default) {
        val image = load(uri.toString(), 1200) ?: return@withContext null
        if (turns % 4 == 0) image else Bitmap.createBitmap(image, 0, 0, image.width, image.height,
            Matrix().apply { postRotate((turns % 4) * 90f) }, true)
    }

    suspend fun cropAvatar(uri: Uri, zoom: Float, offset: Offset, previewPx: Float, turns: Int): ByteArray? = withContext(Dispatchers.Default) {
        val image = rotatedAvatar(uri, turns) ?: return@withContext null
        val side = minOf(image.width, image.height) / zoom
        val centerX = image.width / 2f - offset.x / previewPx * side
        val centerY = image.height / 2f - offset.y / previewPx * side
        val cropSize = side.toInt().coerceAtLeast(1)
        val x = (centerX - side / 2).toInt().coerceIn(0, image.width - cropSize)
        val y = (centerY - side / 2).toInt().coerceIn(0, image.height - cropSize)
        val crop = Bitmap.createBitmap(image, x, y, cropSize, cropSize)
        val out = ByteArrayOutputStream()
        Bitmap.createScaledBitmap(crop, 512, 512, true).compress(Bitmap.CompressFormat.JPEG, 88, out)
        out.toByteArray()
    }

    /** Picked photo -> JPEG up to [maxSide] px on the long side, turned upright (ImageDecoder applies EXIF). */
    suspend fun prepare(uri: Uri, maxSide: Int = 1600): Triple<ByteArray, Int, Int>? = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
                dec.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                // software bitmap: hardware ones can't be compressed
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            Triple(out.toByteArray(), bmp.width, bmp.height)
        }.getOrNull()
    }
}

@Composable
internal fun rememberPhoto(src: String, maxSide: Int): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, src, maxSide) { value = Photos.load(src, maxSide)?.asImageBitmap() }
    return bmp
}

/** Photo inside a bubble: space is reserved by the known aspect ratio, so the list doesn't jump. */
@Composable
fun PhotoBox(m: Msg, modifier: Modifier, onOpen: () -> Unit) {
    val src = m.photo ?: return
    val ratio = if (m.photoW > 0 && m.photoH > 0) (m.photoW.toFloat() / m.photoH).coerceIn(0.5f, 2f) else 1f
    val img = rememberPhoto(src, 720)
    Box(modifier.fillMaxWidth().aspectRatio(ratio).background(C.s2).pointerInput(src) { detectTapGestures { onOpen() } }) {
        if (img != null) Image(img, "Фото", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** Full-screen photo: pinch to zoom, drag when zoomed, double tap toggles 2x, back closes. */
@Composable
fun PhotoViewer(src: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    // the chat input keeps focus under the viewer; drop it so the keyboard goes away
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.clearFocus(); keyboard?.hide() }
    val img = rememberPhoto(src, 2048)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(src) { scale = 1f; offset = Offset.Zero }
    Box(
        Modifier.fillMaxSize().background(C.bg)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2f
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) {
            Image(
                img, "Фото",
                Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                contentScale = ContentScale.Fit,
            )
        }
        Box(
            Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.systemBars).padding(12.dp)
                .size(40.dp).clip(CircleShape).background(C.glass).pointerInput(Unit) { detectTapGestures { onClose() } },
            contentAlignment = Alignment.Center,
        ) { Icon(Ic.Back, "Закрыть", Modifier.size(22.dp), tint = C.ink) }
    }
}

/** Drag and pinch the circle before uploading an avatar. */
@Composable
fun AvatarCropDialog(uri: Uri, onDismiss: () -> Unit, onSave: (ByteArray) -> Unit) {
    var turns by remember(uri) { mutableIntStateOf(0) }
    val img by produceState<ImageBitmap?>(null, uri, turns) { value = Photos.rotatedAvatar(uri, turns)?.asImageBitmap() }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val sizePx = with(density) { 280.dp.toPx() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(C.s1, androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            T("Подгони аватарку", size = 18, weight = androidx.compose.ui.text.font.FontWeight.Bold)
            Box(Modifier.size(280.dp).clip(CircleShape).background(C.s2).pointerInput(uri) {
                detectTransformGestures { _, pan, delta, _ ->
                    zoom = (zoom * delta).coerceIn(1f, 4f)
                    val bound = (zoom - 1f) * sizePx / 2f
                    offset = Offset((offset.x + pan.x).coerceIn(-bound, bound), (offset.y + pan.y).coerceIn(-bound, bound))
                }
            }) {
                img?.let { image -> Image(image, "Аватарка", Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y
                }, contentScale = ContentScale.Crop) }
            }
            T("Раздвинь пальцы для увеличения, сдвинь фото для выбора области", size = 12, color = C.mut)
            Box(Modifier.clip(CircleShape).background(C.s2).clickable {
                turns = (turns + 1) % 4; zoom = 1f; offset = Offset.Zero
            }.padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Rotate, null, Modifier.size(20.dp), tint = C.ink)
                    T("Повернуть на 90°", modifier = Modifier.padding(start = 8.dp))
                }
            }
            Cta("Сохранить", enabled = img != null) {
                scope.launch { Photos.cropAvatar(uri, zoom, offset, sizePx, turns)?.let(onSave) }
            }
        }
    }
}
