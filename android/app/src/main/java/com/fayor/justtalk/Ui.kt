package com.fayor.justtalk

import androidx.activity.compose.BackHandler
import android.content.Intent
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import android.os.SystemClock
import androidx.compose.animation.core.snap
import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val base = TextStyle(fontFamily = Onest, color = C.ink, fontSize = 15.sp)

@Composable
internal fun T(text: String, size: Int = 15, weight: FontWeight = FontWeight.Normal, color: Color = C.ink, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier, style = base.copy(fontSize = size.sp, fontWeight = weight, color = color), maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/** How deep a screen is: going deeper slides in from the right, going back from the left. */
private fun depth(s: Screen) = when (s) {
    Screen.Login, SetPassword -> 0
    Screen.Chats, Screen.Profile, is Screen.Stub -> 1
    is Screen.GroupInfo, is WatchScreen -> 3
    is Screen.Person, is Screen.AddMembers -> 4
    else -> 2
}

@Composable
fun App(store: Store) {
    SystemBarsLook()
    Box(Modifier.fillMaxSize().background(C.bg)) {
        AnimatedContent(
            store.screen,
            modifier = Modifier.fillMaxSize().padding(top = if (Calls.callId != 0 && Calls.minimized) 56.dp else 0.dp),
            // the four tabs are one screen: switching tabs is the pager's job
            contentKey = { if (depth(it) == 1) "tabs" else it },
            transitionSpec = {
                val pops = setOf(EditProfile, Privacy, Appearance, ChangePassword, Kents)
                when {
                    // the chat already slid away under the finger; the spec may be asked more than once, so it's a time window, not a flag
                    SystemClock.uptimeMillis() - store.swipedBackAt < 700 -> EnterTransition.None togetherWith ExitTransition.None
                    // profile sub-screens grow out of the middle over the profile, which stays still underneath
                    targetState in pops && depth(initialState) == 1 ->
                        ((scaleIn(tween(240), initialScale = .85f) + fadeIn(tween(200))) togetherWith fadeOut(snap(delayMillis = 240)))
                            .apply { targetContentZIndex = 1f }
                    initialState in pops && depth(targetState) == 1 ->
                        (EnterTransition.None togetherWith (scaleOut(tween(200), targetScale = .85f) + fadeOut(tween(180))))
                            .apply { targetContentZIndex = -1f }
                    else -> {
                        val dir = if (depth(targetState) >= depth(initialState)) 1 else -1
                        (slideInHorizontally(tween(260)) { it * dir / 3 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(260)) { -it * dir / 3 } + fadeOut(tween(180)))
                    }
                }
            },
            label = "screen",
        ) { s ->
            Box(Modifier.fillMaxSize().background(C.bg)) {
                when (s) {
                    Screen.Login -> LoginScreen(store)
                    SetPassword -> SetPasswordScreen(store)
                    ChangePassword -> ChangePasswordScreen(store)
                    Kents -> KentsScreen(store)
                    is SpotEdit -> SpotEditScreen(store, s)
                    // the four tabs live in one pager, so they can be swiped
                    Screen.Chats, Screen.Profile, is Screen.Stub -> TabsHost(store, s)
                    BotSettings -> BotSettingsScreen(store)
                    Screen.NewChat -> PickScreen(store, null)
                    is Screen.AddMembers -> PickScreen(store, s.chatId)
                    is Screen.GroupInfo -> GroupInfoScreen(store, s.chatId)
                    EditProfile -> EditProfileScreen(store)
                    Privacy -> PrivacyScreen(store)
                    Appearance -> AppearanceScreen(store)
                    is Screen.Open -> ChatScreen(store, s.chatId)
                    is Screen.Person -> PersonScreen(store, s.userId, s.back)
                    is WatchScreen -> Unit // The persistent player is composed above navigation.
                }
            }
        }
        store.activeWatch?.let { room ->
            androidx.compose.runtime.key(room.watchId) {
                Box(Modifier.fillMaxSize().padding(top = if (Calls.callId != 0 && Calls.minimized) 56.dp else 0.dp)) {
                    WatchRoom(store, room.chatId, room.watchId)
                }
            }
        }
        ToastHost(store, Modifier.align(Alignment.TopCenter))
        LaunchedEffect(Unit) { store.checkWhatsNew() }
        if (store.whatsNew) WhatsNew { store.closeWhatsNew() }
        // drawn over any screen, so closing it returns exactly where you were
        store.viewing?.let { PhotoViewer(it) { store.viewing = null } }
        // 0.15: a call goes on over every screen; folded it's a green bar on top
        if (Calls.callId != 0) { if (Calls.minimized) CallBar(store, Modifier.align(Alignment.TopCenter)) else CallScreen(store) }
        Calls.ring?.let { IncomingCall(store, it) }
    }
}

// ---------- shared pieces ----------

/** [online] adds a green dot in the corner: the kent has the app open right now. */
@Composable
fun Avatar(name: String, size: Dp, ring: Boolean, bg: Color = C.s2, photo: String? = null, online: Boolean = false) {
    Box(Modifier.size(size)) {
        val inner = Modifier.clip(CircleShape).background(bg)
        Box(
            if (ring) Modifier.fillMaxSize().border(1.5.dp, C.acc, CircleShape).padding(3.dp).then(inner) else Modifier.fillMaxSize().then(inner),
            contentAlignment = Alignment.Center,
        ) {
            // the letter stays underneath while the photo loads
            T(name.take(1).uppercase(), size = (size.value * .38f).toInt(), weight = FontWeight.SemiBold)
            val img = photo?.let { rememberPhoto(it, if (size > 60.dp) 512 else 192) }
            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        AnimatedVisibility(online, Modifier.align(Alignment.BottomEnd), enter = scaleIn(), exit = scaleOut()) {
            Box(Modifier.size(size * .28f).clip(CircleShape).background(C.bg).padding(2.dp).clip(CircleShape).background(C.green))
        }
    }
}

@Composable
internal fun RoundButton(icon: ImageVector, label: String, size: Dp = 36.dp, bg: Color = C.s1, tint: Color = C.ink, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(bg).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(20.dp), tint = tint)
    }
}

@Composable
internal fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, keyboard: KeyboardOptions = KeyboardOptions.Default,
                   visual: VisualTransformation = VisualTransformation.None, onSend: () -> Unit = {}) {
    BasicTextField(
        value, onChange, modifier,
        textStyle = base.copy(color = C.ink),
        singleLine = true,
        visualTransformation = visual,
        keyboardOptions = keyboard,
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        cursorBrush = SolidColor(C.acc),
        decorationBox = { inner ->
            Box { if (value.isEmpty()) T(hint, color = C.mut); inner() }
        },
    )
}

@Composable
internal fun LabeledInput(label: String, value: String, onChange: (String) -> Unit, hint: String, keyboard: KeyboardOptions = KeyboardOptions.Default,
                          visual: VisualTransformation = VisualTransformation.None) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.s1).padding(horizontal = 14.dp, vertical = 8.dp)) {
        T(label, size = 11, color = C.mut)
        Field(value, onChange, hint, Modifier.fillMaxWidth(), keyboard, visual)
    }
}

@Composable
internal fun Cta(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(CircleShape).background(if (enabled) C.acc else C.acc.copy(alpha = .35f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(text, weight = FontWeight.SemiBold, color = C.onAcc) }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(if (on) C.acc else C.s1).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        T(text, size = 12, color = if (on) C.onAcc else C.mut)
    }
}

@Composable
internal fun Segs(options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.s1).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEach { (id, label) ->
            val on = id == value
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) C.s2 else Color.Transparent).clickable { onPick(id) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { T(label, size = 13, weight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) C.ink else C.mut) }
        }
    }
}

private fun tabScreen(id: String): Screen = when (id) {
    "map" -> Screen.Stub(Store.SPOTS_TAB)
    "profile" -> Screen.Profile
    else -> Screen.Chats
}

private fun tabId(s: Screen) = when (s) {
    Screen.Profile -> "profile"
    is Screen.Stub -> "map"
    else -> "chats"
}

internal fun tabIcon(id: String) = when (id) { "map" -> Ic.Map; "profile" -> Ic.User; else -> Ic.Chat }
internal fun tabLabel(id: String) = when (id) { "map" -> "Споты"; "profile" -> "Профиль"; else -> "Чаты" }

/** The tabs in the order picked in Оформление, hidden ones left out: tap the bar or swipe; a short swipe is enough. */
@Composable
fun TabsHost(store: Store, shown: Screen = store.screen) {
    val tabs = store.tabs
    // [shown] is this copy's own screen: while it slides or fades away the store already points elsewhere,
    // and following that would scroll the pager back to the chats under the new screen
    val tab = tabs.indexOf(tabId(shown)).takeIf { it >= 0 } ?: tabs.indexOf("chats")
    val pager = rememberPagerState(initialPage = tab) { tabs.size }
    // while a tap moves the pager, the pages it passes must not become the current tab
    var jumping by remember { mutableIntStateOf(0) }
    LaunchedEffect(tab, tabs) {
        if (pager.currentPage == tab && !pager.isScrollInProgress) return@LaunchedEffect
        val id = jumping + 1
        jumping = id
        try {
            // Pager pre-jumps internally for distant pages, then performs one scroll animation.
            pager.animateScrollToPage(tab, animationSpec = tween(220))
        } finally {
            if (jumping == id) jumping = 0
        }
    }
    LaunchedEffect(pager.settledPage, jumping, tabs) {
        val settled = tabs.getOrNull(pager.settledPage) ?: return@LaunchedEffect
        if (jumping == 0 && depth(store.screen) == 1 && tabId(store.screen) != settled) store.screen = tabScreen(settled)
    }
    BackHandler(enabled = tabs.getOrNull(tab) != "chats") { store.screen = Screen.Chats }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            pager, Modifier.fillMaxSize(),
            key = { tabs[it] },
            flingBehavior = PagerDefaults.flingBehavior(pager, snapPositionalThreshold = .2f),
            // on Споты a swipe moves the map, not the tabs: leave only through the tab bar
            userScrollEnabled = tabs.getOrNull(pager.settledPage) != "map",
        ) { page ->
            when (tabs[page]) {
                "chats" -> ChatsScreen(store)
                "map" -> SpotsScreen(store, active = tabs.getOrNull(pager.settledPage) == "map")
                else -> ProfileScreen(store)
            }
        }
        TabBar(store, if (jumping != 0) tab else pager.targetPage, Modifier.align(Alignment.BottomCenter))
    }
}

/** Floating glass pill with the active tab in an accent circle. */
@Composable
internal fun TabBar(store: Store, active: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.navigationBarsPadding().padding(bottom = 16.dp).height(60.dp).clip(CircleShape).background(C.glass)
            .border(1.dp, C.glassBorder, CircleShape).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        store.tabs.forEachIndexed { i, id ->
            val on = i == active
            val bg by animateColorAsState(if (on) C.acc else C.acc.copy(alpha = 0f), tween(200), label = "tab")
            val tint by animateColorAsState(if (on) C.onAcc else C.ink, tween(200), label = "tabIcon")
            val w by animateDpAsState(if (on) 48.dp else 52.dp, tween(200), label = "tabW")
            Box(
                Modifier.size(w, 48.dp).clip(CircleShape).background(bg)
                    .clickable { store.screen = tabScreen(id) },
                contentAlignment = Alignment.Center,
            ) { Icon(tabIcon(id), tabLabel(id), Modifier.size(20.dp), tint = tint) }
        }
    }
}

private fun timeOf(sec: Long): String {
    val d = Date(sec * 1000)
    val today = SimpleDateFormat("yyyyMMdd", Locale.ROOT).let { it.format(d) == it.format(Date()) }
    return SimpleDateFormat(if (today) "HH:mm" else "dd.MM", Locale.ROOT).format(d)
}

@Composable
private fun Ticks(m: Msg, othersRead: Int, readTint: Color = C.acc) {
    when {
        m.id < 0 -> Icon(Ic.Clock, "отправляется", Modifier.size(12.dp), tint = C.mut)
        m.id <= othersRead -> Icon(Ic.Tick2, "прочитано", Modifier.size(15.dp, 10.dp), tint = readTint)
        else -> Icon(Ic.Tick1, "отправлено", Modifier.size(15.dp, 10.dp), tint = C.mut)
    }
}

// ---------- screens ----------

@Composable
fun LoginScreen(store: Store) {
    var invite by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // false = new account by invite code, true = existing account by @username + password
    var existing by rememberSaveable { mutableStateOf(false) }
    val goodName = Regex("^@?[a-zA-Z0-9_]{2,20}$").matches(username.trim())
    val ok = if (existing) goodName && password.length >= 6 else invite.length == 9 && name.isNotBlank() && goodName
    if (existing) Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        T("JustTalk", size = 34, weight = FontWeight.Bold)
        T("Вход в свой аккаунт. Все чаты подтянутся с сервера.", color = C.mut)
        Spacer(Modifier.height(10.dp))
        LabeledInput("Юзернейм", username, { username = it.take(21); store.error = "" }, "Твой юзернейм",
            KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next))
        LabeledInput("Пароль", password, { password = it.take(100); store.error = "" }, "••••••",
            KeyboardOptions(keyboardType = KeyboardType.Password), androidx.compose.ui.text.input.PasswordVisualTransformation())
        if (store.error.isNotEmpty() && !busy) T(store.error, size = 13, color = C.red)
        T("Забыл пароль — напиши JT, он сбросит.", size = 12, color = C.mut)
        Spacer(Modifier.height(6.dp))
        Cta(if (busy) "Входим…" else "Войти", enabled = ok && !busy) {
            busy = true
            store.login(username.trim().removePrefix("@").lowercase(), password) { busy = false }
        }
        Box(Modifier.fillMaxWidth().clip(CircleShape).clickable { existing = false; store.error = "" }.padding(12.dp), contentAlignment = Alignment.Center) {
            T("Я новенький, у меня код приглашения", size = 14, color = C.acc)
        }
    } else Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(40.dp))
        T("JustTalk", size = 34, weight = FontWeight.Bold)
        T("Только для своих. Код приглашения дал тот, кто позвал.", color = C.mut)
        Spacer(Modifier.height(10.dp))
        // an old error goes away as soon as something is changed
        LabeledInput("Код приглашения · 9 цифр", invite, { invite = it.filter(Char::isDigit).take(9); store.error = "" }, "000000000",
            KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
        LabeledInput("Имя · как тебя видят кенты", name, { name = it.take(40); store.error = "" }, "Твоё имя")
        LabeledInput("Юзернейм · уникальный", username, { username = it.take(21); store.error = "" }, "Твой юзернейм",
            KeyboardOptions(keyboardType = KeyboardType.Ascii))
        if (store.error.isNotEmpty() && !busy) T(store.error, size = 13, color = C.red)
        Spacer(Modifier.height(6.dp))
        Cta(if (busy) "Входим…" else "Войти", enabled = ok && !busy) {
            busy = true
            store.register(invite, name.trim(), username.trim().removePrefix("@")) { busy = false }
        }
        Box(Modifier.fillMaxWidth().clip(CircleShape).clickable { existing = true; store.error = "" }.padding(12.dp), contentAlignment = Alignment.Center) {
            T("У меня уже есть аккаунт", size = 14, color = C.acc)
        }
    }
}

@Composable
fun ChatsScreen(store: Store) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf("Все") }
    // no "Группы" chip until there is a group
    val filters = buildList { add("Все"); add("Личные"); if (store.chats.any { it.kind == "group" }) add("Группы"); if (store.chats.any { it.kind == "channel" }) add("Каналы") }
    val filter = if (picked in filters) picked else "Все"
    val shown = store.chats.filter {
        (filter == "Все" || (filter == "Личные" && it.kind in listOf("dm", "bot")) || (filter == "Группы" && it.kind == "group") || (filter == "Каналы" && it.kind == "channel")) &&
            (query.isBlank() || it.title.contains(query, ignoreCase = true) || it.last?.text?.contains(query, ignoreCase = true) == true)
    }
    // a chat that jumps to the top must be seen: Compose would otherwise keep the old first row in place
    val list = rememberLazyListState()
    val ctx = LocalContext.current
    LaunchedEffect(store.chats.firstOrNull()?.id) { if (list.firstVisibleItemIndex < 4) list.animateScrollToItem(0) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
            state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("Чаты", size = 26, weight = FontWeight.Bold)
                        if (!store.online) T("Подключение…", size = 12, color = C.mut)
                    }
                    Box(Modifier.padding(end = 8.dp).clip(CircleShape).clickable { store.openBot() }) { BotAvatar(40.dp) }
                    RoundButton(Ic.Plus, "Новый чат") { store.screen = Screen.NewChat }
                }
            }
            store.update?.let { (version, url) ->
                item { UpdateBanner(version, Updater.progress, onLater = store::skipUpdate, onGet = store::installUpdate) }
            }
            item {
                Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(C.s1).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Ic.Search, null, Modifier.size(16.dp), tint = C.mut)
                    Field(query, { query = it }, "Поиск", Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    filters.forEach { Chip(it, it == filter) { picked = it } }
                }
            }
            if (shown.isEmpty()) item {
                T(if (store.chats.isEmpty()) "Пока пусто. Нажми +, чтобы написать кенту." else "Ничего не нашлось",
                    color = C.mut, size = 13, modifier = Modifier.padding(vertical = 30.dp))
            }
            items(shown, key = { it.id }) { chat -> Box(Modifier.animateItem()) { ChatRow(store, chat) } }
            item { Spacer(Modifier.navigationBarsPadding().height(100.dp)) }
        }
    }
}

/** "Вышла 0.11": opens the APK link in the browser, which downloads it; "Позже" hides this version. */
@Composable
private fun UpdateBanner(version: String, progress: Float?, onLater: () -> Unit, onGet: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(androidx.compose.ui.graphics.lerp(C.s1, C.acc, .22f)).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                T("Вышла версия $version", weight = FontWeight.SemiBold)
                T(if (progress == null) "Обновится прямо тут, всё сохранится" else "Скачиваю… ${(progress * 100).toInt()}%", size = 12, color = C.mut)
            }
            if (progress == null) {
                T("Позже", size = 13, color = C.mut, modifier = Modifier.clip(CircleShape).clickable(onClick = onLater).padding(8.dp))
                Box(Modifier.clip(CircleShape).background(C.acc).clickable(onClick = onGet).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    T("Обновить", size = 13, weight = FontWeight.SemiBold, color = C.onAcc)
                }
            }
        }
        if (progress != null) {
            val shown by animateFloatAsState(progress, tween(200), label = "updateProgress")
            Box(Modifier.padding(end = 8.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(C.s2)) {
                Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(CircleShape).background(C.acc))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(store: Store, chat: Chat) {
    val last = chat.last
    val mine = last != null && last.senderId == store.me?.id
    var menu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    if (menu) ChatMenu(store, chat) { menu = false }
    // a chat with unread messages is tinted with a muted shade of my accent, so it stands out in the list
    val rowBg by animateColorAsState(if (chat.unread > 0) androidx.compose.ui.graphics.lerp(C.s1, C.acc, .22f) else C.s1, tween(250), label = "unreadRow")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(rowBg)
            .combinedClickable(onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }) { store.openChat(chat.id) }
            .padding(start = 9.dp, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (chat.kind == "bot") BotAvatar(46.dp)
        else Avatar(chat.title, 46.dp, store.avatarRing, photo = chat.photo(store.me?.id), online = chat.dmPartner(store)?.let { store.presenceOf(it.id).first } == true)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    T(chat.title, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (chat.muted) Icon(Ic.Bell, "без звука", Modifier.padding(start = 4.dp).size(12.dp), tint = C.mut)
                    if (chat.pinned > 0) Icon(Ic.Pin, "закреплён", Modifier.padding(start = 4.dp).size(12.dp), tint = C.mut)
                }
                if (last != null) T(timeOf(last.created), size = 11, color = C.mut)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val typing = store.typingUntil.containsKey(chat.id)
                val typer = if (chat.kind == "group") store.typingWho[chat.id]?.let { store.userName(it) + " " } ?: "" else ""
                T(
                    when {
                        typing -> "${typer}печатает…"
                        last == null -> "Пока пусто"
                        last.senderId == 0 -> last.text
                        mine -> "Вы: ${last.preview()}"
                        chat.kind == "group" -> "${store.userName(last.senderId)}: ${last.preview()}"
                        else -> last.preview()
                    },
                    size = 13, color = if (typing) C.acc else C.mut, maxLines = 1, modifier = Modifier.weight(1f),
                )
                when {
                    // unread count in an accent pill: a circle for one digit, wider for "12" or "99+"
                    chat.unread > 0 -> Box(
                        Modifier.height(20.dp).widthIn(min = 20.dp).clip(CircleShape).background(C.acc).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) { T(if (chat.unread > 99) "99+" else "${chat.unread}", size = 11, weight = FontWeight.SemiBold, color = C.onAcc, maxLines = 1) }
                    mine -> Ticks(last!!, chat.othersRead)
                }
            }
        }
    }
}

/** The other person of a dm, null for groups. */
fun Chat.dmPartner(store: Store): User? = if (kind == "dm") members.firstOrNull { it.id != store.me?.id } else null

private fun dayKey(sec: Long) = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(sec * 1000))

/** "Сегодня", "Вчера", "21 октября", "21 октября 2025" */
private fun dayLabel(sec: Long): String {
    val d = Date(sec * 1000)
    val now = System.currentTimeMillis()
    return when (dayKey(sec)) {
        dayKey(now / 1000) -> "Сегодня"
        dayKey(now / 1000 - 86_400) -> "Вчера"
        else -> {
            val sameYear = SimpleDateFormat("yyyy", Locale.ROOT).let { it.format(d) == it.format(Date(now)) }
            SimpleDateFormat(if (sameYear) "d MMMM" else "d MMMM yyyy", RU).format(d)
        }
    }
}

private val RU = Locale.forLanguageTag("ru")

/** "был в сети 5 мин назад", "была вчера в 21:40"; the verb follows the gender from the profile. */
private fun lastSeenLabel(u: User, seen: Long, now: Long): String {
    val was = when (u.gender) { "m" -> "был"; "f" -> "была"; else -> "был(а)" }
    val ago = now - seen
    val time = SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(seen * 1000))
    return when {
        ago < 60 -> "$was в сети только что"
        ago < 3600 -> "$was в сети ${ago / 60} мин назад"
        dayKey(seen) == dayKey(now) -> "$was сегодня в $time"
        dayKey(seen) == dayKey(now - 86_400) -> "$was вчера в $time"
        else -> "$was в сети ${dayLabel(seen)}"
    }
}

/** The line under the name in a dm: "в сети", "был в сети …" or the @username when nothing is known. */
@Composable
private fun PresenceLine(store: Store, u: User) {
    // "5 мин назад" has to move on by itself
    val now by produceState(System.currentTimeMillis() / 1000) { while (true) { delay(30_000); value = System.currentTimeMillis() / 1000 } }
    val (on, seen) = store.presenceOf(u.id)
    when {
        on -> T("в сети", size = 12, color = C.acc, maxLines = 1)
        seen > 0 -> T(lastSeenLabel(u, seen, now), size = 12, color = C.mut, maxLines = 1)
        else -> Handle(u, size = 12)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(store: Store, chatId: Int) {
    if (store.chats.firstOrNull { it.id == chatId }?.kind == "bot") { BotChatScreen(store, chatId); return }
    LaunchedEffect(chatId) { store.checkBotState(chatId) }
    BackHandler { store.screen = Screen.Chats }
    val chat = store.chats.firstOrNull { it.id == chatId }
    val list = store.messages[chatId].orEmpty()
    val meId = store.me?.id
    val input = rememberTextFieldState()
    val text = input.text.toString()
    LaunchedEffect(input) {
        snapshotFlow { input.text.toString() }.collect { if (it.isNotBlank()) store.typing(chatId) }
    }
    val state = rememberLazyListState()
    // the list is reversed: it opens at the newest message and keeps it above the keyboard;
    // a new one scrolls into view if it's mine or I was already at the bottom
    val newest = list.lastOrNull()
    LaunchedEffect(newest?.clientId ?: newest?.id) {
        if (newest != null && (newest.senderId == meId || state.firstVisibleItemIndex <= 2)) state.animateScrollToItem(0)
    }
    // scrolled up to the oldest loaded message: fetch the page before it
    LaunchedEffect(chatId, state) {
        snapshotFlow {
            val info = state.layoutInfo
            if ((info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6) info.totalItemsCount else -1
        }.distinctUntilChanged().filter { it > 2 }.collect { store.loadOlder(chatId) }
    }
    val other = chat?.dmPartner(store)
    val acts = rememberMsgActions(chatId, state)
    MsgMenu(store, acts) { input.setTextAndPlaceCursorAtEnd(it) }
    acts.forward?.let { m -> ForwardPicker(store, m) { acts.forward = null } }
    val keyboard = LocalSoftwareKeyboardController.current
    var searching by remember(chatId) { mutableStateOf(false) }
    var rec by remember { mutableStateOf<Rec?>(null) }
    val replyTo = { acts.reply?.id ?: 0 }
    val endRec = { rec = null; acts.reply = null }
    rec?.let { r -> if (!r.circle) VoiceSession(store, chatId, r, replyTo, endRec) }
    // new messages that came while I was reading higher up: counted on the "down" button
    val atBottom by remember { derivedStateOf { state.firstVisibleItemIndex <= 2 } }
    var unseen by remember(chatId) { mutableIntStateOf(0) }
    LaunchedEffect(newest?.id) { if (newest != null && newest.id > 0 && newest.senderId != meId && !atBottom) unseen++ }
    LaunchedEffect(atBottom) { if (atBottom) unseen = 0 }

    // swipe right anywhere closes the chat; the chat list shows through underneath while dragging
    val swipe = remember { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxSize().onSizeChanged { widthPx = it.width.toFloat() }.pointerInput(chatId) {
            val width = size.width.toFloat()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                    // only rightwards: a left swipe on a message is "reply"
                    if (over > 0) change.consume()
                } ?: return@awaitEachGesture
                keyboard?.hide()
                val tracker = VelocityTracker()
                horizontalDrag(drag.id) { change ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val x = (swipe.value + change.positionChange().x).coerceAtLeast(0f)
                    scope.launch { swipe.snapTo(x) }
                    change.consume()
                }
                val fling = tracker.calculateVelocity().x
                scope.launch {
                    if (swipe.value > width * .3f || fling > 1200f) {
                        swipe.animateTo(width, tween(160))
                        store.swipedBackAt = SystemClock.uptimeMillis()
                        store.screen = Screen.Chats
                    } else swipe.animateTo(0f, tween(200))
                }
            }
        },
    ) {
        if (swipe.value > 0f) {
            TabsHost(store, Screen.Chats)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f * (1f - swipe.value / widthPx))))
        }
        Column(
            Modifier.fillMaxSize().offset { IntOffset(swipe.value.roundToInt(), 0) }.background(C.bg)
                .windowInsetsPadding(WindowInsets.systemBars).imePadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { if (searching) searching = false else store.screen = Screen.Chats }, contentAlignment = Alignment.Center) {
                    Icon(Ic.Back, "Назад", Modifier.size(22.dp), tint = C.ink)
                }
                if (searching) {
                    ChatSearch(store, chatId, Modifier.weight(1f)) { id ->
                        searching = false
                        scope.launch { store.loadUntil(chatId, id); acts.jumpTo(store, chatId, id) }
                    }
                    return@Row
                }
                val group = chat?.kind in listOf("group", "channel")
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable {
                        store.screen = if (group) Screen.GroupInfo(chatId) else other?.let { Screen.Person(it.id, Screen.Open(chatId)) } ?: return@clickable
                    },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Avatar(chat?.title ?: "?", 38.dp, store.avatarRing, photo = chat?.photo(meId), online = other != null && store.presenceOf(other.id).first)
                    Column(Modifier.weight(1f)) {
                        T(chat?.title ?: "", weight = FontWeight.SemiBold, maxLines = 1)
                        val typer = store.typingWho[chatId]
                        when {
                            store.typingUntil.containsKey(chatId) ->
                                T(if (group && typer != null) "${store.userName(typer)} печатает…" else "печатает…", size = 12, color = C.acc, maxLines = 1)
                            !store.online -> T("подключение…", size = 12, color = C.mut)
                            group -> {
                                val on = chat!!.members.count { it.id != meId && store.presenceOf(it.id).first }
                                T(membersLabel(chat.members.size) + if (on > 0) " · $on в сети" else "", size = 12, color = C.mut, maxLines = 1)
                            }
                            other != null -> PresenceLine(store, other)
                        }
                    }
                }
                val mic = rememberMicGate()
                if (chat?.kind != "channel") Box(Modifier.size(40.dp).clip(CircleShape).clickable { mic { store.startCall(chatId) } }, contentAlignment = Alignment.Center) {
                    Icon(Ic.Phone, "Позвонить", Modifier.size(20.dp), tint = if (Calls.chatId == chatId) C.green else C.ink)
                }
                Box(Modifier.size(40.dp).clip(CircleShape).clickable { searching = true }, contentAlignment = Alignment.Center) {
                    Icon(Ic.Search, "Поиск по чату", Modifier.size(20.dp), tint = C.ink)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
            chat?.pinnedMessage?.takeIf { it > 0 }?.let { pinnedId ->
                val pinned = list.firstOrNull { it.id == pinnedId }
                Row(Modifier.fillMaxWidth().background(C.s1).clickable {
                    scope.launch { store.loadUntil(chatId, pinnedId); acts.jumpTo(store, chatId, pinnedId) }
                }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Pin, null, Modifier.size(16.dp), tint = C.acc)
                    T(pinned?.preview() ?: "Закреплённое сообщение", modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1)
                    if (chat.kind != "channel" || chat.ownerId == meId) Box(Modifier.size(32.dp).clickable { store.pinMessage(chatId, 0) }, contentAlignment = Alignment.Center) { T("×", color = C.mut) }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().then(if (store.chatBg == "grid") Modifier.gridBackground() else Modifier).padding(horizontal = 12.dp),
                state = state,
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                if (store.botBusy[chatId] == true) item { BotThinking() }
                itemsIndexed(list.asReversed(), key = { _, m -> m.clientId ?: m.id }) { j, m ->
                    val i = list.size - 1 - j
                    val next = list.getOrNull(i + 1)
                    val prev = list.getOrNull(i - 1)
                    // a date plate above the first message of each day; it lives in the same item, so indexes stay put
                    val newDay = prev == null || dayKey(prev.created) != dayKey(m.created)
                    Column(Modifier.animateItem()) {
                        if (newDay) DateChip(dayLabel(m.created))
                        if (m.senderId == 0) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                T(m.text, size = 12, color = C.mut, modifier = Modifier.clip(CircleShape).background(C.s1).padding(horizontal = 12.dp, vertical = 5.dp))
                            }
                            return@Column
                        }
                        val mine = m.senderId == meId
                        // a run = consecutive messages from the same person on the same day
                        val lastInRun = next == null || next.senderId != m.senderId || dayKey(next.created) != dayKey(m.created)
                        val firstInRun = prev == null || newDay || prev.senderId != m.senderId
                        val author = if (chat?.kind == "group" && !mine && firstInRun) store.userName(m.senderId) else null
                        val gap = prev != null && firstInRun && !newDay && prev.senderId != 0
                        Bubble(store, m, mine, lastInRun, chat?.othersRead ?: 0, Modifier.padding(top = if (gap) 6.dp else 0.dp), author, acts) {
                            store.screen = Screen.Person(m.senderId, Screen.Open(chatId))
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            // reading higher up: a round "down" button, with the number of new messages since
            androidx.compose.animation.AnimatedVisibility(state.firstVisibleItemIndex > 3, Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 10.dp),
                enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                Box {
                    RoundButton(Ic.Down, "Вниз", size = 44.dp, bg = C.s2, tint = C.ink) { scope.launch { state.animateScrollToItem(0) } }
                    if (unseen > 0) Box(
                        Modifier.align(Alignment.TopCenter).offset(y = (-8).dp).height(20.dp).widthIn(min = 20.dp).clip(CircleShape).background(C.acc)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) { T(if (unseen > 99) "99+" else "$unseen", size = 11, weight = FontWeight.SemiBold, color = C.onAcc) }
                }
            }
            }
            if (chat?.kind == "channel" && chat.ownerId != meId) return@Column
            // system photo picker (up to 10 at once, no storage permission needed); the typed text goes along as a caption
            val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
                if (uris.isNotEmpty()) { store.sendPhotos(chatId, uris, text); input.clearText() }
            }
            val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) { store.sendFile(chatId, uri, text, replyTo()); input.clearText(); acts.reply = null }
            }
            var attach by remember { mutableStateOf(false) }
            // 0.15: which "new card" sheet is open
            var sheet by remember { mutableStateOf("") }
            if (attach) AttachMenu(onClose = { attach = false },
                onPhotos = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onFile = { pickFile.launch(arrayOf("*/*")) },
                onCard = { sheet = it })
            when (sheet) {
                "poll" -> NewPollSheet(store, chatId) { sheet = "" }
                "gather" -> NewGatherSheet(store, chatId) { sheet = "" }
                "split" -> NewSplitSheet(store, chatId) { sheet = "" }
                "watch" -> NewWatchSheet(store, chatId) { sheet = "" }
            }
            ComposeBar(store, acts) { input.clearText() }
            if (other != null && store.isBlocked(other.id)) {
                Row(Modifier.fillMaxWidth().padding(12.dp).clip(CircleShape).background(C.s1).clickable { store.setBlocked(other, false) }.padding(14.dp),
                    horizontalArrangement = Arrangement.Center) {
                    T("Ты заблокировал кента · ", color = C.mut); T("Разблокировать", color = C.acc, weight = FontWeight.SemiBold)
                }
                return@Column
            }
            ClipboardPhotoPill { uri -> store.sendPastedPhoto(chatId, uri, text); input.clearText() }
            val submit = { acts.submit(store, chatId, input.text.toString()); input.clearText() }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundButton(Ic.Clip, "Вложение", size = 48.dp, bg = C.glass, tint = C.ink) { attach = true }
                val recording = rec
                if (recording != null) RecordingBar(recording, Modifier.weight(1f)) else Row(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(C.glass).border(1.dp, C.glassBorder, RoundedCornerShape(24.dp))
                        .padding(start = 16.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicTextField(
                        input,
                        Modifier.weight(1f).padding(vertical = 7.dp)
                            // a picture pasted from the menu or inserted by the keyboard goes out as a photo
                            .contentReceiver { content ->
                                if (!content.hasMediaType(MediaType.Image)) return@contentReceiver content
                                val uris = mutableListOf<Uri>()
                                val rest = content.consume { item -> item.uri?.also { uris += it } != null }
                                uris.forEach { store.sendPastedPhoto(chatId, it, input.text.toString()) }
                                if (uris.isNotEmpty()) input.clearText()
                                rest
                            },
                        textStyle = base.copy(color = C.ink),
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        onKeyboardAction = { submit() },
                        cursorBrush = SolidColor(C.acc),
                        decorator = { inner -> Box { if (input.text.isEmpty()) T("Сообщение", color = C.mut); inner() } },
                    )
                }
                // tap: voice <-> circle; hold: record; hold and slide up: keep recording hands-free
                if (text.isNotBlank() && recording == null) {
                    RoundButton(Ic.Send, "Отправить", size = 48.dp, bg = C.acc, tint = C.onAcc) { submit() }
                } else {
                    RecordButton(store, chatId, replyTo, rec) { r -> keyboard?.hide(); rec = r }
                }
            }
        }
        rec?.let { r -> if (r.circle) CircleSession(store, chatId, r, replyTo, endRec) }
        // the lock hint floats above the record button without touching the layout around it
        LockHint(rec, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().imePadding().padding(end = 16.dp, bottom = 76.dp))
    }
}

/** Search inside one chat: results from the server, newest first; a tap jumps to the message. */
@Composable
private fun ChatSearch(store: Store, chatId: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    var q by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(listOf<Msg>()) }
    LaunchedEffect(q) { delay(300); found = if (q.isBlank()) listOf() else store.searchIn(chatId, q) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(C.s1).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Ic.Search, null, Modifier.size(16.dp), tint = C.mut)
            Field(q, { q = it }, "Поиск по чату", Modifier.weight(1f).focusRequester(focus))
        }
        if (q.isNotBlank()) Popup(offset = IntOffset(0, 130)) {
            Column(
                Modifier.widthIn(max = 340.dp).heightIn(max = 420.dp).clip(RoundedCornerShape(18.dp)).background(C.s2)
                    .verticalScroll(rememberScrollState()).padding(6.dp),
            ) {
                if (found.isEmpty()) T("Ничего не нашлось", size = 13, color = C.mut, modifier = Modifier.padding(12.dp))
                found.forEach { m ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(m.id) }.padding(10.dp)) {
                        Row {
                            T(store.userName(m.senderId), size = 12, weight = FontWeight.SemiBold, color = C.acc, modifier = Modifier.weight(1f))
                            T(dayLabel(m.created) + ", " + SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(m.created * 1000)), size = 11, color = C.mut)
                        }
                        T(m.preview(), size = 14, maxLines = 2)
                    }
                }
            }
        }
    }
}

/** The clip button: photos, files, and since 0.15 the live cards. */
@Composable
private fun AttachMenu(onClose: () -> Unit, onPhotos: () -> Unit, onFile: () -> Unit, onCard: (String) -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.s1).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AttachTile(Ic.Photo, "Фото", "до 10 сразу", Modifier.weight(1f)) { onClose(); onPhotos() }
                AttachTile(Ic.File, "Файл", "до 100 МБ", Modifier.weight(1f)) { onClose(); onFile() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AttachTile(Ic.Calendar, "Сбор", "кто идёт", Modifier.weight(1f)) { onClose(); onCard("gather") }
                AttachTile(Ic.Poll, "Опрос", "голосуем", Modifier.weight(1f)) { onClose(); onCard("poll") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AttachTile(Ic.Money, "Скидываемся", "кто сколько", Modifier.weight(1f)) { onClose(); onCard("split") }
                AttachTile(Ic.Tv, "Смотреть", "вместе, синхронно", Modifier.weight(1f)) { onClose(); onCard("watch") }
            }
        }
    }
}

@Composable
private fun AttachTile(icon: ImageVector, title: String, hint: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(C.s2).clickable(onClick = onClick).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(C.acc.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = C.acc)
        }
        T(title, weight = FontWeight.SemiBold, maxLines = 1)
        T(hint, size = 11, color = C.mut, maxLines = 1)
    }
}

/** "Переслать": pick the chat the copy goes to. */
@Composable
private fun ForwardPicker(store: Store, m: Msg, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).clip(RoundedCornerShape(22.dp)).background(C.s1).padding(8.dp)) {
            T("Переслать в…", weight = FontWeight.SemiBold, modifier = Modifier.padding(10.dp))
            LazyColumn {
                items(store.chats, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onClose(); store.forward(m, c.id) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(c.title, 38.dp, store.avatarRing, photo = c.photo(store.me?.id))
                        T(c.title, weight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun DateChip(label: String) {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        T(label, size = 12, weight = FontWeight.Medium, color = C.mut,
            modifier = Modifier.clip(CircleShape).background(C.s1).padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

/**
 * "Вставить фото из буфера" above the input while the clipboard holds a picture.
 * Only the clip's description is read until the tap, so Android shows no "pasted from clipboard" notice for nothing.
 */
@Composable
private fun ClipboardPhotoPill(onPaste: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val clip = remember { ctx.getSystemService(ClipboardManager::class.java) }
    // timestamp of the picture in the clipboard (0 = none) and of the last one sent or dismissed
    var stamp by remember { mutableLongStateOf(0L) }
    var done by rememberSaveable { mutableLongStateOf(-1L) }
    val check = {
        val d = runCatching { clip?.primaryClipDescription }.getOrNull()
        stamp = if (d != null && d.hasMimeType("image/*")) d.timestamp else 0L
    }
    // Android lets only the focused window look at the clipboard
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { if (focused) check() }
    DisposableEffect(clip) {
        val l = ClipboardManager.OnPrimaryClipChangedListener { check() }
        clip?.addPrimaryClipChangedListener(l)
        onDispose { clip?.removePrimaryClipChangedListener(l) }
    }
    AnimatedVisibility(stamp != 0L && stamp != done, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp).clip(CircleShape).background(C.s1)
                .clickable {
                    val uri = runCatching { clip?.primaryClip?.getItemAt(0)?.uri }.getOrNull()
                    done = stamp
                    if (uri != null) onPaste(uri)
                }
                .padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Ic.Photo, null, Modifier.size(18.dp), tint = C.acc)
            T("Вставить фото из буфера", size = 14, color = C.acc, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(Modifier.size(42.dp).clip(CircleShape).clickable { done = stamp }, contentAlignment = Alignment.Center) {
                T("✕", size = 14, color = C.mut)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Bubble(store: Store, m: Msg, mine: Boolean, lastInGroup: Boolean, othersRead: Int, modifier: Modifier, author: String? = null, acts: MsgActions? = null, onAuthor: () -> Unit = {}) {
    if (m.bot != null) { BotReply(store, m, modifier); return }
    // capsules with a cut corner on the last bubble of a run
    val r = 24.dp
    val cut = 5.dp
    val shape = when {
        !lastInGroup -> RoundedCornerShape(r)
        mine -> RoundedCornerShape(r, r, cut, r)
        else -> RoundedCornerShape(r, r, r, cut)
    }
    val style = if (mine) store.bubbleStyle else "them"
    val circle = m.mediaKind == "circle"
    // a circle has no bubble of its own, the video is the shape
    val bg = when {
        circle -> Color.Transparent
        style == "accent" -> C.acc; style == "outline" -> Color.Transparent; style == "gray" -> C.s2; else -> C.s1
    }
    val ink = if (style == "accent" && !circle) C.onAcc else C.ink
    val accent = if (style == "accent" && !circle) C.onAcc else C.acc
    val photo = m.photo != null
    val card = m.card
    val framed = photo || circle
    // blinks after a tap on a quote that points here
    val flash = if (acts != null && acts.flash == m.id) C.acc.copy(alpha = .18f) else Color.Transparent
    Box(Modifier.fillMaxWidth().background(flash, RoundedCornerShape(12.dp)).msgGestures(acts, m, store), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            // IntrinsicSize.Max: the bubble is as wide as its widest line, and a quote plate stretches to that width
            modifier.then(
                when {
                    photo -> Modifier.width(250.dp)
                    circle -> Modifier.width(220.dp)
                    card is CallCard -> Modifier.widthIn(min = 220.dp, max = 290.dp)
                    card != null -> Modifier.width(290.dp)
                    else -> Modifier.widthIn(max = 290.dp).width(IntrinsicSize.Max)
                },
            ).clip(shape).background(bg)
                .then(if (style == "outline" && !circle) Modifier.border(1.5.dp, C.acc, shape) else Modifier)
                // a photo sits almost edge to edge, with a thin frame of the bubble color
                .padding(horizontal = if (framed) 4.dp else 14.dp, vertical = if (framed) 4.dp else 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            val inner = if (framed) Modifier.padding(horizontal = 10.dp) else Modifier
            if (author != null) T(author, size = 12, weight = FontWeight.SemiBold, color = C.acc,
                modifier = inner.align(Alignment.Start).clickable(onClick = onAuthor).padding(top = if (framed) 4.dp else 0.dp, bottom = if (framed) 4.dp else 0.dp))
            if (m.fwdName.isNotBlank()) Row(inner.align(Alignment.Start).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Ic.Forward, null, Modifier.size(13.dp), tint = accent)
                T("Переслано от ${m.fwdName}", size = 12, color = accent, maxLines = 1)
            }
            if (!circle) Box(inner.fillMaxWidth()) { QuoteInBubble(store, acts, m, ink, accent) }
            m.spot?.let { ref -> SpotCard(ref, ink, accent, Modifier.align(Alignment.Start)) { store.showSpot(ref) } }
            card?.let { Box(Modifier.align(Alignment.Start).padding(top = 2.dp, bottom = 4.dp)) { CardBody(store, m, it, ink, accent) } }
            if (photo) PhotoBox(m, Modifier.clip(RoundedCornerShape(20.dp))) { store.viewing = m.photo }
            when (m.mediaKind) {
                "voice" -> Box(Modifier.align(Alignment.Start)) { VoiceBody(store, m, ink, accent) }
                "circle" -> CircleBody(store, m)
                "file" -> Box(Modifier.align(Alignment.Start)) { FileBody(store, m, ink, accent) }
            }
            // a card's text is only the fallback for apps before 0.15
            if (m.text.isNotBlank() && card == null) BotMentionText(m.text, ink, accent, inner.align(Alignment.Start).padding(top = if (framed || m.media != null) 6.dp else 0.dp))
            else if (photo) Spacer(Modifier.height(2.dp))
            if (m.reactions.isNotEmpty()) FlowRow(
                inner.align(Alignment.Start).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                m.reactions.forEach { (emoji, who) ->
                    val minePut = store.me?.id in who
                    Row(
                        Modifier.clip(CircleShape).background(if (minePut) accent.copy(alpha = .3f) else ink.copy(alpha = .1f))
                            .clickable(enabled = m.id > 0) { store.react(m, emoji) }.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        T(emoji, size = 14)
                        if (who.size > 1 || store.chats.firstOrNull { it.id == m.chatId }?.kind == "group") T("${who.size}", size = 12, color = ink)
                    }
                }
            }
            Row(inner.padding(bottom = if (framed) 4.dp else 0.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (m.id < 0 && m.media != null) UploadRing(store, m)
                EditedMark(m, ink.copy(alpha = .65f))
                T(timeOf(m.created), size = 10, color = ink.copy(alpha = .65f))
                // accent ticks would vanish on an accent bubble
                if (mine) Ticks(m, othersRead, if (style == "accent" && !circle) C.onAcc else C.acc)
            }
        }
    }
}

private fun membersLabel(n: Int) = when {
    n % 10 == 1 && n % 100 != 11 -> "$n участник"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "$n участника"
    else -> "$n участников"
}

@Composable
internal fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Ic.Back, "Назад", Modifier.size(22.dp), tint = C.ink)
        }
        T(title, weight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun CheckMark(on: Boolean) {
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(if (on) C.acc else Color.Transparent)
            .border(1.5.dp, if (on) C.acc else C.mut, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Icon(Ic.Tick1, null, Modifier.size(13.dp, 9.dp), tint = C.onAcc) }
}

/** New chat (addTo == null): one kent opens a dm, several make a group. Otherwise adds kents to group addTo. */
@Composable
fun PickScreen(store: Store, addTo: Int?) {
    val back = { store.screen = if (addTo == null) Screen.Chats else Screen.GroupInfo(addTo) }
    BackHandler(onBack = back)
    var picked by remember { mutableStateOf(listOf<User>()) }
    var channel by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // without a query: only kents I already have a dm with (at least one message); the rest by @username
    var found by remember { mutableStateOf(listOf<User>()) }
    LaunchedEffect(query) { delay(250); found = store.search(query) }
    val talked = store.chats.filter { it.kind == "dm" && it.last != null }.mapNotNull { c -> c.members.firstOrNull { it.id != store.me?.id } }
    val shownUsers = if (query.removePrefix("@").isBlank()) talked else found
    val already = addTo?.let { id -> store.chats.firstOrNull { it.id == id }?.members?.map { it.id } }.orEmpty()
    val candidates = (picked + shownUsers.filter { f -> picked.none { it.id == f.id } }).filter { it.id !in already }
    val n = picked.size
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TopBar(if (addTo == null) "Новый чат" else "Добавить кентов", back)
        if (addTo == null) Segs(listOf("chat" to "Чат или группа", "channel" to "Канал"), if (channel) "channel" else "chat") { channel = it == "channel" }
        Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(C.s1).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Ic.Search, null, Modifier.size(16.dp), tint = C.mut)
            Field(query, { query = it }, "@юзернейм кента", Modifier.weight(1f), KeyboardOptions(keyboardType = KeyboardType.Ascii))
        }
        if (addTo == null && (n > 1 || channel)) LabeledInput(if (channel) "Название канала" else "Название группы", title, { title = it.take(60) }, "Например, Катка в субботу")
        if (candidates.isEmpty()) T(if (query.removePrefix("@").isBlank()) "Введи @ и первые буквы юзернейма кента" else "Никого не нашлось",
            color = C.mut, size = 13, modifier = Modifier.padding(vertical = 20.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(candidates, key = { it.id }) { u ->
                val on = picked.any { it.id == u.id }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { picked = if (on) picked.filterNot { it.id == u.id } else picked + u }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(u.name, 42.dp, store.avatarRing, photo = u.photo())
                    Column(Modifier.weight(1f)) {
                        T(u.name, weight = FontWeight.SemiBold)
                        Handle(u)
                    }
                    CheckMark(on)
                }
            }
        }
        if (store.error.isNotEmpty()) T(store.error, size = 13, color = C.red)
        Box(Modifier.padding(bottom = 16.dp)) {
            when {
                addTo != null -> Cta(if (n == 0) "Выбери кентов" else "Добавить · $n", enabled = n > 0 && !busy) {
                    busy = true; store.addMembers(addTo, picked.map { it.id }) { busy = false }
                }
                channel -> Cta("Создать канал", enabled = title.isNotBlank() && !busy) {
                    busy = true; store.createChannel(title.trim(), picked.map { it.id }) { busy = false }
                }
                n <= 1 -> Cta(if (n == 0) "Выбери кентов" else "Написать ${picked[0].name}", enabled = n == 1) {
                    store.openDm(picked[0])
                }
                else -> Cta("Создать группу · ${n + 1}", enabled = title.isNotBlank() && !busy) {
                    busy = true; store.createGroup(title.trim(), picked.map { it.id }) { busy = false }
                }
            }
        }
    }
}

@Composable
fun GroupInfoScreen(store: Store, chatId: Int) {
    BackHandler { store.openChat(chatId) }
    val chat = store.chats.firstOrNull { it.id == chatId } ?: return
    var confirm by remember { mutableStateOf(false) }
    // anyone in the group may change its name and photo, everyone sees a line about it
    var renaming by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var avatarToCrop by remember { mutableStateOf<Uri?>(null) }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> avatarToCrop = uri }
    avatarToCrop?.let { uri -> AvatarCropDialog(uri, { avatarToCrop = null }) { jpeg -> store.setGroupAvatar(chatId, jpeg); avatarToCrop = null } }
    if (renaming) Dialog(onDismissRequest = { renaming = false }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.s1).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.s2).padding(horizontal = 16.dp, vertical = 13.dp)) {
                Field(title, { title = it.take(60) }, "Название группы")
            }
            MenuPill("Сохранить") { if (title.isNotBlank()) store.renameGroup(chatId, title); renaming = false }
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 20.dp)) {
        TopBar(if (chat.kind == "channel") "Канал" else "Группа") { store.openChat(chatId) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.clip(CircleShape).clickable(enabled = chat.kind != "channel" || chat.ownerId == store.me?.id, onClickLabel = "Фото группы") {
                        pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Avatar(chat.title, 92.dp, store.avatarRing, photo = chat.photo(store.me?.id)) }
                    T(chat.title, size = 20, weight = FontWeight.Bold)
                    T(membersLabel(chat.members.size), size = 13, color = C.mut)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (chat.kind != "channel" || chat.ownerId == store.me?.id) T(if (chat.avatar.isEmpty()) "Поставить фото" else "Сменить фото", size = 12, color = C.acc, modifier = Modifier.clickable {
                            pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        })
                        if (chat.kind != "channel" || chat.ownerId == store.me?.id) T("Переименовать", size = 12, color = C.acc, modifier = Modifier.clickable { title = chat.title; renaming = true })
                    }
                }
            }
            if (chat.kind != "channel" || chat.ownerId == store.me?.id) item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { store.screen = Screen.AddMembers(chatId) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(C.acc), contentAlignment = Alignment.Center) {
                        Icon(Ic.Plus, null, Modifier.size(20.dp), tint = C.onAcc)
                    }
                    T("Добавить кентов", color = C.acc, weight = FontWeight.SemiBold)
                }
            }
            items(chat.members.sortedBy { it.id != store.me?.id }, key = { it.id }) { u ->
                val isMe = u.id == store.me?.id
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled = !isMe) { store.screen = Screen.Person(u.id, Screen.GroupInfo(chatId)) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(u.name, 42.dp, store.avatarRing, photo = u.photo())
                    Column(Modifier.weight(1f)) {
                        T(if (isMe) "${u.name} (ты)" else u.name, weight = FontWeight.SemiBold)
                        Handle(u)
                    }
                }
            }
            if (chat.kind != "channel" || chat.ownerId != store.me?.id) item {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(if (confirm) C.s1 else Color.Transparent)
                    .clickable { if (confirm) store.leave(chatId) else confirm = true }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    T(if (confirm) "Точно выйти? Нажми ещё раз" else if (chat.kind == "channel") "Покинуть канал" else "Выйти из группы", color = Color(0xFFFF5A5F))
                }
            }
        }
    }
}
