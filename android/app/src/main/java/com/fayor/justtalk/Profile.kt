package com.fayor.justtalk

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay

// sub-screens of the profile tab; Screen is sealed, so they must stay in this package
data object EditProfile : Screen
data object Privacy : Screen
data object Appearance : Screen

private val PALETTE_LABELS = listOf("black" to "Чёрная", "graphite" to "Графит", "light" to "Светлая")

/** Background of my own avatar, picked on the edit screen (0 = the usual gray). */
private val AVA_COLORS = listOf(null, Color(0xFF3A3F7A), Color(0xFF2F5B46), Color(0xFF6B3550), Color(0xFF6B5530))

private fun myAvaBg(store: Store) = AVA_COLORS.getOrNull(store.avaColor) ?: C.s2

/** Dark or light status bar icons, following the chosen theme. */
@Composable
fun SystemBarsLook() {
    val view = LocalView.current
    val light = C.light
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

/** "Сетка" chat background from Оформление. */
fun Modifier.gridBackground() = drawBehind {
    val step = 22.dp.toPx()
    var x = 0f
    while (x < size.width) { drawLine(C.line, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
    var y = 0f
    while (y < size.height) { drawLine(C.line, Offset(0f, y), Offset(size.width, y), 1f); y += step }
}

// ---------- pieces ----------

/** Back arrow, centered title, empty slot on the right (keeps the title centered). */
@Composable
internal fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "Назад", onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Ic.Back, "Назад", Modifier.size(22.dp), tint = C.ink)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { T(title, weight = FontWeight.SemiBold, maxLines = 1) }
        Spacer(Modifier.width(40.dp))
    }
}

@Composable
internal fun Label(text: String) = T(text, size = 12, color = C.mut, modifier = Modifier.padding(top = 4.dp))

@Composable
internal fun CardGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.s1).padding(vertical = 4.dp), content = content)
}

/** One row of a card group: tinted icon tile, label (with an optional hint) and whatever goes on the right. */
@Composable
internal fun CardRow(icon: ImageVector, label: String, first: Boolean = false, hint: String? = null, onClick: () -> Unit, end: @Composable () -> Unit) {
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(lerp(C.s2, C.acc, .22f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(15.dp), tint = C.acc)
        }
        Column(Modifier.weight(1f)) {
            T(label, size = 14)
            if (hint != null) T(hint, size = 11, color = C.mut)
        }
        end()
    }
}

@Composable
internal fun Value(text: String) = T(text, size = 13, color = C.mut)

@Composable
internal fun Toggle(on: Boolean) {
    val x by animateDpAsState(if (on) 19.dp else 3.dp, label = "knob")
    val track by animateColorAsState(if (on) C.acc else C.s2, label = "track")
    Box(Modifier.size(40.dp, 24.dp).clip(CircleShape).background(track)) {
        Box(Modifier.offset(x = x, y = 3.dp).size(18.dp).clip(CircleShape).background(if (on) C.onAcc else C.mut))
    }
}

@Composable
private fun ToggleRow(store: Store, key: String, icon: ImageVector, label: String, first: Boolean = false, hint: String? = null) {
    val on = store.flag(key)
    CardRow(icon, label, first, hint, onClick = { store.setFlag(key, !on) }) { Toggle(on) }
}

/** Pill on top of the screen that hides itself after two seconds. */
@Composable
internal fun ToastHost(store: Store, modifier: Modifier) {
    val text = store.toast
    LaunchedEffect(text) { if (text.isNotEmpty()) { delay(2000); store.toast = "" } }
    // keeps the last text while the pill slides away
    var shown by remember { mutableStateOf("") }
    if (text.isNotEmpty()) shown = text
    AnimatedVisibility(text.isNotEmpty(), modifier, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
        Box(Modifier.statusBarsPadding().padding(top = 8.dp).clip(CircleShape).background(C.ink).padding(horizontal = 16.dp, vertical = 9.dp)) {
            T(shown, size = 13, weight = FontWeight.SemiBold, color = C.bg)
        }
    }
}

/** Bottom sheet over a dark scrim; tiles are (icon, label, action). An empty list shows "Пока никого". */
@Composable
internal fun Sheet(title: String, tiles: List<Triple<ImageVector, String, () -> Unit>>, onClose: () -> Unit) =
    // own window: stays above the floating tab bar
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) { SheetBody(title, tiles, onClose) }

@Composable
private fun SheetBody(title: String, tiles: List<Triple<ImageVector, String, () -> Unit>>, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(C.s1)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp).clip(CircleShape).background(C.s2))
            T(title, weight = FontWeight.Bold)
            if (tiles.isEmpty()) T("Пока никого", size = 11, color = C.mut)
            tiles.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (icon, label, action) ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = action),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(C.s2), contentAlignment = Alignment.Center) {
                                Icon(icon, null, Modifier.size(22.dp), tint = C.acc)
                            }
                            T(label, size = 12)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ---------- screens ----------

@Composable
fun ProfileScreen(store: Store) {
    var sheet by remember { mutableStateOf(false) }
    var allSheet by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { store.reloadMe() }
    val me = store.me
    val name = me?.name ?: ""
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = "Изменить") { store.screen = EditProfile }, contentAlignment = Alignment.Center) {
                    Icon(Ic.Edit, "Изменить", Modifier.size(20.dp), tint = C.ink)
                }
            }
            Box(Modifier.clip(CircleShape).clickable { store.screen = EditProfile }) {
                Avatar(name.ifEmpty { "?" }, 92.dp, store.avatarRing, myAvaBg(store), me?.photo())
            }
            T(name, size = 20, weight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            if (me != null) Handle(me, modifier = Modifier.padding(top = 4.dp))
            Text(
                me?.about?.ifBlank { null } ?: "Пусто",
                Modifier.padding(top = 4.dp).widthIn(max = 260.dp),
                style = base.copy(fontSize = 13.sp, color = C.mut, textAlign = TextAlign.Center),
            )
            if (me != null) PersonFacts(me)
            Spacer(Modifier.height(18.dp))
            CardGroup {
                val kents = store.users.count { it.kent }
                CardRow(Ic.User, "Кенты", first = true, hint = "Кто кент, а кто просто знакомый", onClick = { store.screen = Kents }) { Value("$kents ›") }
                // only while the Споты tab is switched on
                if ("map" in store.tabs) {
                    val mine = store.spots.count { it.ownerId == store.me?.id }
                    CardRow(Ic.Map, "Мои споты", onClick = { store.screen = Screen.Stub(Store.SPOTS_TAB) }) { Value("$mine ›") }
                }
            }
            Spacer(Modifier.height(12.dp))
            CardGroup {
                ToggleRow(store, "notify", Ic.Bell, "Уведомления", first = true)
                // Android shows a notification while the app stays connected; switching off its channel hides it, the connection stays
                val ctx = LocalContext.current
                CardRow(Ic.Bell, "Скрыть «JustTalk на связи»", hint = "Выключи этот тип уведомлений, связь и сообщения останутся", onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.CH_SERVICE))
                    }
                }) { Value("›") }
                CardRow(Ic.Lock, "Конфиденциальность", onClick = { store.screen = Privacy }) { Value("›") }
                CardRow(Ic.User, "JT бот", onClick = { store.botSettings() }) { Value("Характер ›") }
                CardRow(Ic.Lock, "Сменить пароль", onClick = { store.screen = ChangePassword }) { Value("›") }
                val look = PALETTE_LABELS.firstOrNull { it.first == store.palette }?.second ?: ""
                CardRow(Ic.Edit, "Оформление", onClick = { store.screen = Appearance }) { Value("$look ›") }
            }
            Box(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(14.dp)).clickable { sheet = true }.padding(12.dp)) {
                T("Выйти", size = 14, color = Color(0xFFFF5A5F))
            }
            Box(Modifier.clip(RoundedCornerShape(14.dp)).clickable { allSheet = true }.padding(horizontal = 12.dp, vertical = 4.dp)) {
                T("Выйти на всех устройствах", size = 13, color = C.mut)
            }
            Spacer(Modifier.navigationBarsPadding().height(100.dp))
        }
        if (sheet) Sheet("Точно выйти?", listOf(
            Triple(Ic.Back, "Выйти") { sheet = false; store.logout() },
            Triple(Ic.Check, "Остаться") { sheet = false },
        )) { sheet = false }
        // this phone stays signed in; any other phone with my account drops to the login screen
        if (allSheet) Sheet("Выйти на всех других устройствах?", listOf(
            Triple(Ic.Lock, "Выйти везде") { allSheet = false; store.logoutAll() },
            Triple(Ic.Check, "Отмена") { allSheet = false },
        )) { allSheet = false }
    }
}

@Composable
fun EditProfileScreen(store: Store) {
    BackHandler { store.screen = Screen.Profile }
    val me = store.me
    var name by remember { mutableStateOf(me?.name ?: "") }
    var username by remember { mutableStateOf(me?.username ?: "") }
    var about by remember { mutableStateOf(me?.about ?: "") }
    // only the digits are kept, DateMask draws the dots
    var birthday by remember { mutableStateOf(me?.birthday.orEmpty().filter(Char::isDigit)) }
    var gender by remember { mutableStateOf(me?.gender ?: "") }
    var avatarToCrop by remember { mutableStateOf<android.net.Uri?>(null) }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> avatarToCrop = uri }
    avatarToCrop?.let { uri -> AvatarCropDialog(uri, { avatarToCrop = null }) { jpeg -> store.setAvatar(jpeg); avatarToCrop = null } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    // "checking", "free", "taken" or "offline" (couldn't ask the server; it checks again on save)
    var check by remember { mutableStateOf("free") }
    val un = username.trim().lowercase().removePrefix("@")
    // "jt" is two letters but allowed for the creator; the server decides who that is
    val bad = un != "jt" && !Regex("^[a-z0-9_]{3,20}$").matches(un)
    // birthday is optional, but if started it must be a whole date
    val badDate = birthday.isNotEmpty() && birthday.length != 8
    LaunchedEffect(un) {
        if (bad || un == me?.username) { check = "free"; return@LaunchedEffect }
        check = "checking"
        delay(300) // wait until typing pauses
        check = when (store.usernameFree(un)) { true -> "free"; false -> "taken"; null -> "offline" }
    }
    val ok = !bad && !badDate && name.isNotBlank() && check != "taken" && check != "checking" && !busy

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding().padding(horizontal = 20.dp)) {
        Header("Профиль") { store.screen = Screen.Profile }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val photo = me?.photo()
                val pick = { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                Box(Modifier.clip(CircleShape).clickable(onClickLabel = "Поставить фото", onClick = pick)) {
                    Avatar(name.trim().ifEmpty { "?" }, 84.dp, store.avatarRing, myAvaBg(store), photo)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    T(if (photo == null) "Поставить фото" else "Сменить фото", size = 12, color = C.acc, modifier = Modifier.clickable(onClick = pick))
                    if (photo != null) T("Убрать", size = 12, color = C.red, modifier = Modifier.clickable { store.dropAvatar() })
                    else T("Сменить фон", size = 12, color = C.mut, modifier = Modifier.clickable { store.nextAvaColor() })
                }
            }
            LabeledInput("Имя · обязательно", name, { name = it.take(40); error = "" }, "Как тебя видят кенты")
            LabeledInput("Юзернейм · обязательно, уникальный", username, { username = it.take(21); error = "" }, "только a–z, 0–9 и _",
                KeyboardOptions(keyboardType = KeyboardType.Ascii))
            when {
                bad -> T("3–20 символов: латиница, цифры, _", size = 11, color = C.red)
                check == "checking" -> T("Проверяем @$un…", size = 11, color = C.mut)
                check == "taken" -> T(if (looksReserved(un)) "Ники с JT и JustTalk заняты приложением" else "@$un уже занят", size = 11, color = C.red)
                check == "offline" -> T("Нет связи, проверим при сохранении", size = 11, color = C.mut)
                else -> T("@$un свободен", size = 11, color = C.green)
            }
            LabeledInput("О себе · необязательно", about, { about = it.take(140) }, "Пара слов")
            LabeledInput("Дата рождения · необязательно", birthday, { birthday = it.filter(Char::isDigit).take(8) }, "ДД.ММ.ГГГГ",
                KeyboardOptions(keyboardType = KeyboardType.Number), DateMask)
            if (badDate) T("Допиши дату полностью или сотри", size = 11, color = C.red)
            Label("Пол · необязательно")
            Segs(listOf("" to "Не скажу", "m" to "Парень", "f" to "Девушка"), gender) { gender = it }
            if (error.isNotEmpty()) T(error, size = 13, color = C.red)
        }
        Box(Modifier.padding(top = 10.dp, bottom = 16.dp)) {
            Cta(if (busy) "Сохраняем…" else "Сохранить", enabled = ok) {
                busy = true
                store.saveProfile(name.trim(), un, about.trim(), withDots(birthday), gender) { err ->
                    busy = false
                    if (err != null) { error = err; if (err.contains("занят")) check = "taken" }
                }
            }
        }
    }
}

/** Mirrors the server rule: names with JT or JustTalk belong to the app. */
private fun looksReserved(u: String): Boolean {
    val flat = u.filter(Char::isLetter)
    return "jt" in flat || listOf("justtalk", "justtolk", "justalk").any { it in flat }
}

/** "12032008" -> "12.03.2008" */
private fun withDots(d: String) = buildString { d.forEachIndexed { i, c -> if (i == 2 || i == 4) append('.'); append(c) } }

/** Shows typed digits as ДД.ММ.ГГГГ; the cursor skips over the dots. */
private object DateMask : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val len = text.text.length
        val map = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset + (if (offset > 2) 1 else 0) + (if (offset > 4) 1 else 0)
            override fun transformedToOriginal(offset: Int) =
                (offset - (if (offset > 2) 1 else 0) - (if (offset > 5) 1 else 0)).coerceIn(0, len)
        }
        return TransformedText(AnnotatedString(withDots(text.text)), map)
    }
}

/** "12.03.2008 · 17 лет · Парень": only what the person filled in. */
@Composable
fun PersonFacts(u: User) {
    val parts = buildList {
        if (u.birthday.isNotEmpty()) {
            add(u.birthday)
            ageOf(u.birthday)?.let { add(yearsLabel(it)) }
        }
        when (u.gender) { "m" -> add("Парень"); "f" -> add("Девушка") }
    }
    if (parts.isNotEmpty()) T(parts.joinToString(" · "), size = 13, color = C.mut, modifier = Modifier.padding(top = 6.dp))
}

private fun ageOf(birthday: String): Int? = runCatching {
    val (d, m, y) = birthday.split('.').map { it.toInt() }
    val now = java.util.Calendar.getInstance()
    val day = now.get(java.util.Calendar.DAY_OF_MONTH)
    val month = now.get(java.util.Calendar.MONTH) + 1
    now.get(java.util.Calendar.YEAR) - y - if (month < m || (month == m && day < d)) 1 else 0
}.getOrNull()

private fun yearsLabel(n: Int) = when {
    n % 10 == 1 && n % 100 != 11 -> "$n год"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "$n года"
    else -> "$n лет"
}

@Composable
fun PrivacyScreen(store: Store) {
    var sheet by remember { mutableStateOf(false) }
    BackHandler { store.screen = Screen.Profile }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Header("Конфиденциальность") { store.screen = Screen.Profile }
            Label("Кто видит мой профиль")
            Segs(listOf("all" to "Все кенты", "friends" to "Только друзья", "nobody" to "Никто"), store.profileVis, store::setVis)
            CardGroup {
                ToggleRow(store, "online", Ic.User, "Показывать «в сети»", first = true)
                ToggleRow(store, "read", Ic.Check, "Галочки «прочитано»", hint = "Выключишь — и сам не увидишь чужие")
            }
            CardGroup {
                CardRow(Ic.Lock, "Заблокированные", first = true, onClick = { sheet = true }) { Value("${store.blocked.size} ›") }
            }
            T("Переключатели выше пока хранятся только на этом телефоне.", size = 11, color = C.mut)
        }
        // tap a kent to unblock him
        if (sheet) Sheet("Заблокированные · нажми, чтобы разблокировать", store.blocked.map { u ->
            Triple(Ic.User, u.name) { store.setBlocked(u, false) }
        }) { sheet = false }
    }
}

@Composable
fun AppearanceScreen(store: Store) {
    BackHandler { store.screen = Screen.Profile }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Header("Оформление") { store.screen = Screen.Profile }
        Label("Тема")
        Segs(PALETTE_LABELS, store.palette, store::pickPalette)
        Label("Акцент")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ACCENTS.forEach { a ->
                val on = a.id == store.accent
                Box(
                    Modifier.size(34.dp).border(if (on) 2.dp else 1.dp, if (on) C.ink else C.line, CircleShape).padding(3.dp)
                        .clip(CircleShape).background(if (a.id == "mono" && C.light) Color(0xFF111114) else a.color).clickable(onClickLabel = a.label) { store.pickAccent(a.id) },
                )
            }
        }
        Label("Реакция по двойному нажатию")
        var customQuick by remember { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            REACTIONS.forEach { emoji ->
                Box(Modifier.size(42.dp).clip(CircleShape).background(if (emoji == store.quickReaction) C.acc.copy(alpha = .3f) else C.s1).clickable {
                    customQuick = ""; store.chooseQuickReaction(emoji)
                }, contentAlignment = Alignment.Center) { T(emoji, size = 22) }
            }
        }
        if (store.quickReaction !in REACTIONS) T("Выбрано: ${store.quickReaction}", size = 13, color = C.acc)
        Row(Modifier.fillMaxWidth().clip(CircleShape).background(C.s1).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(customQuick, { input ->
                customQuick = emojiInput(input)
                if (customQuick.isNotEmpty()) store.chooseQuickReaction(customQuick)
            }, "Или любой эмодзи", Modifier.weight(1f))
        }
        Label("Свои сообщения")
        Segs(listOf("gray" to "Серые", "accent" to "Цвет акцента", "outline" to "Контур"), store.bubbleStyle, store::setBubble)
        Label("Обводка аватарки")
        Segs(listOf("yes" to "Да", "no" to "Нет"), if (store.avatarRing) "yes" else "no") { store.setRing(it == "yes") }
        Label("Вкладки · порядок и какие показывать")
        CardGroup {
            store.tabOrder.forEachIndexed { i, id ->
                val fixed = id == "chats" || id == "profile"
                val on = id !in store.tabsOff
                CardRow(tabIcon(id), tabLabel(id), first = i == 0, hint = if (fixed) "Всегда на месте" else null,
                    onClick = { if (!fixed) store.setTabOn(id, !on) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Box(Modifier.size(32.dp).clip(CircleShape).clickable(enabled = i > 0) { store.moveTab(id, -1) }, contentAlignment = Alignment.Center) {
                            Icon(Ic.Up, "Выше", Modifier.size(16.dp), tint = if (i > 0) C.ink else C.line)
                        }
                        Box(Modifier.size(32.dp).clip(CircleShape).clickable(enabled = i < store.tabOrder.lastIndex) { store.moveTab(id, 1) }, contentAlignment = Alignment.Center) {
                            Icon(Ic.Down, "Ниже", Modifier.size(16.dp), tint = if (i < store.tabOrder.lastIndex) C.ink else C.line)
                        }
                        if (!fixed) Box(Modifier.padding(start = 6.dp)) { Toggle(on) }
                    }
                }
            }
        }
        Label("Фон чата")
        Segs(listOf("plain" to "Однотонный", "grid" to "Сетка"), store.chatBg, store::pickChatBg)
        // live preview of the choices above, on the chat background so "their" bubble stands out
        Column(
            Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(18.dp)).background(C.bg).border(1.dp, C.s2, RoundedCornerShape(18.dp))
                .then(if (store.chatBg == "grid") Modifier.gridBackground() else Modifier).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar("Контакт", 32.dp, store.avatarRing)
                T("Контакт", size = 14, weight = FontWeight.SemiBold)
            }
            val now = System.currentTimeMillis() / 1000
            Bubble(store, Msg(1, 0, 0, "Как тебе новая тема?", now), false, true, 0, Modifier)
            Bubble(store, Msg(2, 0, 0, "Огонь", now), true, true, 2, Modifier)
        }
        Spacer(Modifier.height(16.dp))
    }
}
