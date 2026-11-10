package com.fayor.justtalk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Typeface
import android.location.LocationManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yandex.mapkit.Animation
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraListener
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.InputListener
import com.yandex.mapkit.map.MapObjectTapListener
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import com.yandex.mapkit.map.Map as YMap
import java.lang.ref.WeakReference

// 0.11: a new spot or editing one (id = 0 → new); lat/lng = where the point starts
data class SpotEdit(val id: Int, val lat: Double, val lng: Double) : Screen

/** Ready categories; anything else in Spot.kind is the author's own one. */
val SPOT_KINDS = listOf("club" to "Клуб", "place" to "Место", "home" to "Квартира", "park" to "Парк", "cafe" to "Кафе", "study" to "Учёба")

fun kindLabel(kind: String) = SPOT_KINDS.firstOrNull { it.first == kind }?.second ?: kind

/** Moscow, until there is something better to show */
private val START = Point(55.751, 37.618)

private var mapKitStarted = false

/** MapKit is started lazily: only people who open Споты pay for it. */
private fun ensureMapKit(ctx: Context) {
    if (!mapKitStarted) { MapKitFactory.initialize(ctx); mapKitStarted = true }
}

/** A MapView tied to the screen's lifecycle: MapKit needs onStart/onStop or it shows nothing. */
@Composable
private fun rememberMapView(): MapView {
    val ctx = LocalContext.current
    ensureMapKit(ctx)
    val view = remember { MapView(ctx) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        fun start() { MapKitFactory.getInstance().onStart(); view.onStart() }
        fun stop() { view.onStop(); MapKitFactory.getInstance().onStop() }
        val obs = LifecycleEventObserver { _, e ->
            when (e) { Lifecycle.Event.ON_START -> start(); Lifecycle.Event.ON_STOP -> stop(); else -> {} }
        }
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) stop()
        }
    }
    view.mapWindow.map.isNightModeEnabled = !C.light
    return view
}

private fun YMap.flyTo(p: Point, zoom: Float = 15f) = move(CameraPosition(p, zoom, 0f, 0f), Animation(Animation.Type.SMOOTH, .4f), null)

/** Compact map marker: neutral by default, accent when selected, with a solid category glyph. */
private fun pinBitmap(count: String?, kind: String, color: Color, big: Boolean, density: Float): Bitmap {
    val d = ((if (big) 36 else 30) * density).toInt()
    val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val cx = d / 2f
    p.color = android.graphics.Color.WHITE
    c.drawCircle(cx, cx, cx, p)
    p.color = color.toArgb()
    c.drawCircle(cx, cx, cx - 1.5f * density, p)
    p.color = android.graphics.Color.WHITE
    if (count != null) {
        p.textSize = d * .39f
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        c.drawText(count, cx, cx - (p.descent() + p.ascent()) / 2f, p)
    } else {
        c.save()
        c.translate(cx, cx)
        c.scale(d / 32f, d / 32f)
        p.style = Paint.Style.FILL
        fun shape(vararg xy: Float) {
            val path = Path().apply { moveTo(xy[0], xy[1]); var i = 2; while (i < xy.size) { lineTo(xy[i], xy[i + 1]); i += 2 }; close() }
            c.drawPath(path, p)
        }
        when (kind) {
            "home" -> {
                shape(-8f, -1f, 0f, -8f, 8f, -1f, 6.6f, -1f, 6.6f, 7f, -6.6f, 7f, -6.6f, -1f)
                p.color = color.toArgb(); c.drawRoundRect(-1.6f, 2.2f, 1.6f, 7.2f, .5f, .5f, p)
            }
            "park" -> {
                c.drawRoundRect(-1.2f, 1f, 1.2f, 8f, .5f, .5f, p)
                c.drawCircle(0f, -2.4f, 5.2f, p)
                c.drawCircle(-4.2f, -.4f, 3.1f, p)
                c.drawCircle(4.2f, -.4f, 3.1f, p)
            }
            "cafe" -> {
                c.drawRoundRect(-7f, -5f, 4f, 3.2f, 1.4f, 1.4f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.1f
                c.drawArc(2.5f, -4.5f, 8f, 2.5f, -90f, 180f, false, p)
                p.style = Paint.Style.FILL
                c.drawRoundRect(-8f, 5f, 7f, 7f, 1f, 1f, p)
            }
            "study" -> {
                shape(-8f, -6f, -1f, -4.5f, -1f, 7f, -8f, 5.4f)
                shape(1f, -4.5f, 8f, -6f, 8f, 5.4f, 1f, 7f)
            }
            "club" -> {
                shape(-1f, -6f, 7f, -8f, 7f, -4.8f, -1f, -2.8f)
                c.drawRoundRect(-1f, -5f, 1f, 4f, .5f, .5f, p)
                c.drawRoundRect(5f, -6f, 7f, 2f, .5f, .5f, p)
                c.drawOval(-6f, 2f, 1f, 7f, p)
                c.drawOval(0f, 0f, 7f, 5f, p)
            }
            "place" -> {
                c.drawCircle(0f, -2.4f, 6f, p)
                shape(-5.2f, .5f, 5.2f, .5f, 0f, 8f)
                p.color = color.toArgb(); c.drawCircle(0f, -2.4f, 2.4f, p)
            }
            else -> shape(0f, -8f, 2.2f, -2.1f, 8f, -2.1f, 3.3f, 1.6f, 5f, 7.5f, 0f, 4f, -5f, 7.5f, -3.3f, 1.6f, -8f, -2.1f, -2.2f, -2.1f)
        }
        c.restore()
    }
    return bmp
}

@SuppressLint("MissingPermission")
private fun lastLocation(ctx: Context): Point? {
    val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
    return lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }?.let { Point(it.latitude, it.longitude) }
}

private fun hasLocation(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** Can I change or delete it: the author, and @jt for cleaning up. */
private fun Store.canEdit(s: Spot) = s.ownerId == me?.id || me?.isOwner == true

// ---------- the tab ----------

/** [active]: this tab is the one on screen (the pager may compose it while sliding past). */
@Composable
fun SpotsScreen(store: Store, active: Boolean) {
    val ctx = LocalContext.current
    val map = rememberMapView()
    val density = ctx.resources.displayMetrics.density
    var filter by remember { mutableStateOf("all") }
    var picked by remember { mutableStateOf<Int?>(null) }
    var calling by remember { mutableStateOf<Spot?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val meId = store.me?.id
    // categories that really exist among the visible spots, in a stable order
    val kinds = store.spots.map { it.kind }.distinct().sortedBy { k -> SPOT_KINDS.indexOfFirst { it.first == k }.let { if (it < 0) 99 else it } }
    val shown = store.spots.filter { filter == "all" || (filter == "mine" && it.ownerId == meId) || it.kind == filter }
    val focus = store.spotFocus
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) lastLocation(ctx)?.let { map.mapWindow.map.flyTo(it) } ?: run { store.toast = "Не знаю, где ты: включи геолокацию" }
    }
    LaunchedEffect(Unit) {
        store.loadSpots()
        if (store.spotFocus == null) map.mapWindow.map.move(CameraPosition(if (hasLocation(ctx)) lastLocation(ctx) ?: START else START, 11f, 0f, 0f))
    }
    // a card tapped in a chat: fly there and open that spot
    LaunchedEffect(focus, active) {
        if (focus == null || !active) return@LaunchedEffect
        map.mapWindow.map.flyTo(Point(focus.lat, focus.lng), 16f)
        picked = focus.id
        store.spotFocus = null
        // a spot hidden from me still gets a pin for the moment
        if (store.spots.none { it.id == focus.id }) tempPin = focus
    }
    // listeners are held strongly here: MapKit keeps only weak references
    val tapListener = remember { MapObjectTapListener { obj, _ -> (obj.userData as? Int)?.let { picked = it }; true } }
    val tap = remember { WeakReference(tapListener) }
    val input = remember {
        object : InputListener {
            override fun onMapTap(m: YMap, p: Point) { picked = null }
            // a long press drops a new spot right there
            override fun onMapLongTap(m: YMap, p: Point) { store.screen = SpotEdit(0, p.latitude, p.longitude) }
        }
    }
    val inputRef = remember { WeakReference<InputListener>(input) }
    DisposableEffect(map) {
        map.mapWindow.map.addInputListener(inputRef)
        onDispose { map.mapWindow.map.removeInputListener(inputRef) }
    }
    // pins redrawn whenever the list, the filter, the pick or the accent changes
    val acc = C.acc
    LaunchedEffect(shown, picked, acc, tempPin) {
        val objects = map.mapWindow.map.mapObjects
        objects.clear()
        val all = shown.map { SpotRef(it.id, it.title, it.kind, it.lat, it.lng) } + listOfNotNull(tempPin?.takeIf { t -> shown.none { it.id == t.id } })
        all.forEach { s ->
            val big = s.id == picked
            // 0.15 «Я тут»: a green pin with the number of kents there right now
            val here = shown.firstOrNull { it.id == s.id }?.here?.size ?: 0
            val count = if (here > 0) "$here" else null
            objects.addPlacemark().apply {
                geometry = Point(s.lat, s.lng)
                setIcon(ImageProvider.fromBitmap(pinBitmap(count, s.kind, if (here > 0) C.green else if (big) acc else Color(0xFF303039), big, density)), IconStyle().setZIndex(if (big || here > 0) 1f else 0f))
                userData = s.id
                addTapListener(tap)
            }
        }
    }
    BackHandler(enabled = picked != null) { picked = null }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ map }, Modifier.fillMaxSize())
        MapCenterMark(Modifier.align(Alignment.Center))
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    T("Споты", size = 22, weight = FontWeight.Bold, modifier = Modifier.clip(CircleShape).background(C.glass).padding(horizontal = 14.dp, vertical = 6.dp))
                }
                Box(Modifier.clip(CircleShape).background(C.acc).clickable { choosing = true }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    T("Позвать на спот", size = 13, weight = FontWeight.SemiBold, color = C.onAcc)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassChip("Все", filter == "all") { filter = "all" }
                GlassChip("Мои", filter == "mine") { filter = "mine" }
                kinds.forEach { k -> GlassChip(kindLabel(k), filter == k) { filter = k } }
            }
        }
        // right column: where am I, add a spot at the center of the map
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundButton(Ic.Map, "Где я", size = 46.dp, bg = C.glass) {
                if (hasLocation(ctx)) lastLocation(ctx)?.let { map.mapWindow.map.flyTo(it) } ?: run { store.toast = "Не знаю, где ты: включи геолокацию" }
                else askLocation.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }
            RoundButton(Ic.Plus, "Новый спот", size = 46.dp, bg = C.acc, tint = C.onAcc) {
                val c = map.mapWindow.map.cameraPosition.target
                store.screen = SpotEdit(0, c.latitude, c.longitude)
            }
        }
        if (store.spots.isEmpty()) T("Зажми точку на карте или нажми +, чтобы добавить спот", size = 12, color = C.mut,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp, start = 24.dp, end = 24.dp)
                .clip(CircleShape).background(C.glass).padding(horizontal = 14.dp, vertical = 8.dp))
        val spot = store.spots.firstOrNull { it.id == picked }
        AnimatedVisibility(spot != null, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            // keeps the last spot while the card slides away
            var last by remember { mutableStateOf(spot) }
            if (spot != null) last = spot
            last?.let { s -> SpotCardSheet(store, s, onCall = { calling = s }) }
        }
    }
    if (choosing) SpotChooser(store) { s -> choosing = false; if (s != null) calling = s }
    calling?.let { s -> CallDialog(store, s) { calling = null } }
}

/** a spot from a chat card the reader can't see in his list */
private var tempPin by mutableStateOf<SpotRef?>(null)

@Composable
private fun GlassChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(if (on) C.acc else C.glass).border(1.dp, if (on) C.acc else C.glassBorder, CircleShape)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)) {
        T(text, size = 13, color = if (on) C.onAcc else C.ink, maxLines = 1)
    }
}

/** The picked spot over the map, above the tab bar. */
@Composable
private fun SpotCardSheet(store: Store, s: Spot, onCall: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 90.dp).clip(RoundedCornerShape(26.dp))
            .background(C.s1).clickable(enabled = false) {}.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(s.title, size = 18, weight = FontWeight.Bold, maxLines = 2)
                val who = if (s.ownerId == store.me?.id) "мой" else s.ownerName
                T(listOf(kindLabel(s.kind), who, if (s.vis == "me") "только мне" else "для кентов").joinToString(" · "), size = 12, color = C.mut, maxLines = 1)
            }
            if (store.canEdit(s)) RoundButton(Ic.Edit, "Изменить", bg = C.s2) { store.screen = SpotEdit(s.id, s.lat, s.lng) }
        }
        if (s.about.isNotBlank()) T(s.about, size = 14, maxLines = 4)
        // 0.15 «Я тут»: who is here now, and my own mark
        val meId = store.me?.id
        val imHere = s.here.any { it.userId == meId }
        if (s.here.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Faces(store, s.here.map { it.userId }, 24.dp)
            T("тут сейчас: " + s.here.joinToString { if (it.userId == meId) "я" else it.name }, size = 13, color = C.green, maxLines = 2)
        }
        Box(
            Modifier.fillMaxWidth().height(40.dp).clip(CircleShape).background(if (imHere) C.s2 else C.green.copy(alpha = .18f))
                .clickable { store.checkin(if (imHere) null else s) },
            contentAlignment = Alignment.Center,
        ) { T(if (imHere) "Я ушёл" else "📍 Я тут", weight = FontWeight.SemiBold, color = if (imHere) C.ink else C.green) }
        if (s.photos.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.photos.forEach { name -> Thumb(name, 76) { store.viewing = name } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(44.dp).clip(CircleShape).background(C.acc).clickable(onClick = onCall), contentAlignment = Alignment.Center) {
                T("Позвать кентов", weight = FontWeight.SemiBold, color = C.onAcc)
            }
            // any maps app on the phone (Яндекс Карты, Навигатор, Google) offers to build the route
            Box(Modifier.weight(1f).height(44.dp).clip(CircleShape).background(C.s2).clickable {
                val uri = Uri.parse("geo:${s.lat},${s.lng}?q=${s.lat},${s.lng}(" + Uri.encode(s.title) + ")")
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { store.toast = "Нет приложения карт" }
            }, contentAlignment = Alignment.Center) { T("Маршрут", weight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun Thumb(src: String, size: Int, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(14.dp)).background(C.s2).clickable(onClick = onClick)) {
        rememberPhoto(src, 300)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** «Позвать на спот» from the top button: first pick which one. */
@Composable
private fun SpotChooser(store: Store, onPick: (Spot?) -> Unit) {
    Dialog(onDismissRequest = { onPick(null) }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(22.dp)).background(C.s1).padding(8.dp)) {
            T("На какой спот зовёшь?", weight = FontWeight.SemiBold, modifier = Modifier.padding(10.dp))
            if (store.spots.isEmpty()) T("Спотов пока нет: добавь первый на карте", size = 13, color = C.mut, modifier = Modifier.padding(10.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                store.spots.sortedBy { it.ownerId != store.me?.id }.forEach { s ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(s) }.padding(10.dp)) {
                        T(s.title, weight = FontWeight.Medium, maxLines = 1)
                        T(kindLabel(s.kind) + if (s.ownerId == store.me?.id) "" else " · ${s.ownerName}", size = 12, color = C.mut, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Sends «еду туда» with the spot card to every kent (not every contact); the text can be changed. */
@Composable
private fun CallDialog(store: Store, s: Spot, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val kents = store.users.count { it.kent }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.s1).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T("Позвать на «${s.title}»", weight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, top = 10.dp))
            T(if (kents == 0) "У тебя пока нет кентов. Отметь их в Профиль → Кенты." else "Напишем каждому кенту в личку: $kents", size = 12, color = C.mut,
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.s2).padding(horizontal = 16.dp, vertical = 13.dp)) {
                Field(text, { text = it.take(500) }, "Еду на «${s.title}», подтягивайся!")
            }
            if (kents == 0) MenuPill("Открыть кентов") { onClose(); store.screen = Kents }
            else MenuPill("Позвать") { store.callToSpot(s, text); onClose() }
        }
    }
}

/** The card inside a chat bubble: tap opens the spot on the map. */
@Composable
fun SpotCard(ref: SpotRef, ink: Color, accent: Color, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Row(
        modifier.padding(bottom = 6.dp).widthIn(min = 200.dp).clip(RoundedCornerShape(16.dp)).background(ink.copy(alpha = .08f)).clickable(onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
            Icon(Ic.Map, null, Modifier.size(18.dp), tint = if (accent == C.acc) C.onAcc else C.acc)
        }
        Column {
            T(ref.title, weight = FontWeight.SemiBold, color = ink, maxLines = 1)
            T(kindLabel(ref.kind) + " · открыть на карте", size = 12, color = ink.copy(alpha = .65f), maxLines = 1)
        }
    }
}

// ---------- editor ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpotEditScreen(store: Store, edit: SpotEdit) {
    val old = store.spots.firstOrNull { it.id == edit.id }
    val back = { store.screen = Screen.Stub(Store.SPOTS_TAB) }
    BackHandler(onBack = back)
    var title by remember { mutableStateOf(old?.title ?: "") }
    // a preset id, or "own" while typing a custom category into [own]
    var kind by remember { mutableStateOf(old?.kind?.let { k -> if (SPOT_KINDS.any { it.first == k }) k else "own" } ?: "place") }
    var own by remember { mutableStateOf(old?.kind?.takeIf { k -> SPOT_KINDS.none { it.first == k } } ?: "") }
    var about by remember { mutableStateOf(old?.about ?: "") }
    // server names for photos already saved, content:// uris for new ones
    var photos by remember { mutableStateOf(old?.photos ?: listOf()) }
    var vis by remember { mutableStateOf(old?.vis ?: "kents") }
    var point by remember { mutableStateOf(Point(edit.lat, edit.lng)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val left = 5 - photos.size
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        photos = (photos + uris.map { it.toString() }).take(5)
    }
    val category = if (kind == "own") own.trim() else kind
    val ok = title.isNotBlank() && category.isNotEmpty() && !busy

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
        Box(Modifier.padding(horizontal = 20.dp)) { Header(if (old == null) "Новый спот" else "Спот", back) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // the point is the middle of this little map: move the map under the pin
            Box(Modifier.fillMaxWidth().height(190.dp).padding(top = 6.dp).clip(RoundedCornerShape(20.dp))) {
                PointPicker(point) { point = it }
                MapCenterMark(Modifier.align(Alignment.Center))
                T("Двигай карту, точка — в центре", size = 11, color = C.ink,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).clip(CircleShape).background(C.glass).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            LabeledInput("Название", title, { title = it.take(60); error = "" }, "Название спота")
            Label("Категория")
            // wraps to a second line, so «Своя…» is always in sight
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SPOT_KINDS.forEach { (id, label) -> GlassChip(label, kind == id) { kind = id } }
                GlassChip("Своя…", kind == "own") { kind = "own" }
            }
            if (kind == "own") LabeledInput("Своя категория", own, { own = it.take(24) }, "Гаражи, крыша, качалка…")
            LabeledInput("Описание · необязательно", about, { about = it.take(500) }, "Как найти, когда лучше приходить")
            Label("Фото · до 5")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                photos.forEach { p ->
                    Box {
                        Thumb(p, 76) { store.viewing = p }
                        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape).background(Color.Black.copy(alpha = .6f))
                            .clickable { photos = photos - p }, contentAlignment = Alignment.Center) {
                            Icon(Ic.Close, "Убрать", Modifier.size(12.dp), tint = Color.White)
                        }
                    }
                }
                if (left > 0) Box(Modifier.size(76.dp).clip(RoundedCornerShape(14.dp)).background(C.s1)
                    .clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, contentAlignment = Alignment.Center) {
                    Icon(Ic.Plus, "Добавить фото", Modifier.size(22.dp), tint = C.mut)
                }
            }
            Label("Кто видит")
            Segs(listOf("kents" to "Мои кенты", "me" to "Только я"), vis) { vis = it }
            if (error.isNotEmpty()) T(error, size = 13, color = C.red)
            if (old != null) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (confirmDelete) C.s1 else Color.Transparent)
                .clickable { if (confirmDelete) { store.deleteSpot(old); back() } else confirmDelete = true }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                T(if (confirmDelete) "Точно удалить? Нажми ещё раз" else "Удалить спот", color = C.red)
            }
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)) {
            Cta(if (busy) "Сохраняем…" else "Сохранить", enabled = ok) {
                busy = true
                store.saveSpot(edit.id, title.trim(), category, point.latitude, point.longitude, about.trim(), photos, vis) { err ->
                    busy = false
                    if (err != null) error = err
                }
            }
        }
    }
}

/** Small map whose center is the chosen point. */
@Composable
private fun PointPicker(start: Point, onMove: (Point) -> Unit) {
    val map = rememberMapView()
    val listener = remember { CameraListener { _, pos, _, finished -> if (finished) onMove(pos.target) } }
    val ref = remember { WeakReference(listener) }
    DisposableEffect(map) {
        map.mapWindow.map.move(CameraPosition(start, 16f, 0f, 0f))
        map.mapWindow.map.addCameraListener(ref)
        onDispose { map.mapWindow.map.removeCameraListener(ref) }
    }
    AndroidView({ map }, Modifier.fillMaxSize())
}

/** The crosshair stays at the camera target used by the add-spot button. */
@Composable
private fun MapCenterMark(modifier: Modifier = Modifier) {
    val accent = C.acc
    androidx.compose.foundation.Canvas(modifier.size(28.dp)) {
        drawCircle(Color.White, 4.dp.toPx())
        drawCircle(accent, 2.dp.toPx())
    }
}

