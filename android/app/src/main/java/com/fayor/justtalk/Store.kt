package com.fayor.justtalk

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import org.json.JSONObject
import java.util.UUID

sealed interface Screen {
    data object Login : Screen
    data object Chats : Screen
    data object NewChat : Screen
    data class GroupInfo(val chatId: Int) : Screen
    data class AddMembers(val chatId: Int) : Screen
    data object Profile : Screen
    data class Stub(val title: String) : Screen
    data class Open(val chatId: Int) : Screen
    /** someone's profile; back returns to the screen it was opened from */
    data class Person(val userId: Int, val back: Screen) : Screen
}

/** The whole app state; Compose reads these fields directly and redraws on change. */
class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("jt", Context.MODE_PRIVATE)
    private val notifier = Notifier(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var api = Api(prefs.getString("server", DEFAULT_SERVER)!!, prefs.getString("token", "")!!)
    private var socket: WebSocket? = null
    private var foreground = false
    private var retryDelay = 2000L

    var screen by mutableStateOf<Screen>(if (api.token.isEmpty()) Screen.Login else Screen.Chats)
    var me by mutableStateOf<User?>(null)
    var chats by mutableStateOf(listOf<Chat>())
    var users by mutableStateOf(listOf<User>())
    var blocked by mutableStateOf(listOf<User>())
    val messages = mutableStateMapOf<Int, List<Msg>>()
    /** chatId -> moment until which "печатает…" is shown */
    val typingUntil = mutableStateMapOf<Int, Long>()
    /** chatId -> who is typing there, for "Рома печатает…" in groups */
    val typingWho = mutableStateMapOf<Int, Int>()
    var online by mutableStateOf(false)
    var error by mutableStateOf("")
    /** userId -> (app open now, last seen in unix seconds); from user lists and live "presence" events */
    val presence = mutableStateMapOf<Int, Pair<Boolean, Long>>()
    /** chats whose oldest message is already loaded */
    private val historyEnd = mutableSetOf<Int>()
    private val loadingOlder = mutableSetOf<Int>()
    /** when the chat was last closed by a swipe: the screen already slid away, no second animation right after */
    var swipedBackAt = 0L
    /** clientId -> upload progress 0..1 of a file on its way */
    val progress = mutableStateMapOf<String, Float>()
    /** a newer app on the server: (version name, download url), null = up to date or skipped */
    val botBusy = mutableStateMapOf<Int, Boolean>()
    var botStyle by mutableStateOf("friendly")
    var botTarget by mutableStateOf(0)
    var botSettingsBack: Screen = Screen.Profile
    fun openBot() = scope.launch {
        try {
            val chat = api.botChat()
            chats = sorted(chats.filterNot { it.id == chat.id } + chat)
            loadBotSettings()
            openChat(chat.id)
        } catch (e: Exception) { toast = (e as? ApiError)?.message ?: "Не удалось открыть бота" }
    }
    fun loadBotSettings() = scope.launch {
        runCatching { api.botSettings() }.onSuccess { botStyle = it.optString("style", "friendly"); botTarget = it.optInt("target_chat_id") }
    }
    private val botSettingsLock = Mutex()
    fun saveBotSettings(style: String? = null, target: Int? = null) = scope.launch {
        botSettingsLock.withLock {
        try {
            val result = api.setBotSettings(style ?: botStyle, target ?: botTarget)
            botStyle = result.optString("style", "friendly"); botTarget = result.optInt("target_chat_id")
        } catch (e: Exception) { toast = (e as? ApiError)?.message ?: "Не получилось сохранить" }
        }
    }
    fun checkBotState(chatId: Int) = scope.launch { runCatching { api.botState(chatId) }.onSuccess { botBusy[chatId] = it } }
    fun retryBot(id: Int) = scope.launch { try { api.retryBot(id) } catch (e: Exception) { toast = e.message ?: "Не получилось повторить" } }
    fun sendBotDraft(id: Int) = cardAction { api.sendBotDraft(id) }
    fun botEqual(id: Int) = cardAction { api.botEqual(id) }
    fun botRemind(id: Int) = scope.launch {
        try { api.botRemind(id); toast = "Напомню завтра в 10:00 по Москве" }
        catch (e: Exception) { toast = e.message ?: "Не получилось поставить напоминание" }
    }
    fun botSettings() { botSettingsBack = screen; screen = BotSettings }
    var update by mutableStateOf<Pair<String, String>?>(null)

    // tabs: order and the hidden ones (only Споты can be hidden), from Оформление → Вкладки
    var tabOrder by mutableStateOf(prefs.getString("tabs", null)?.split(",")?.filter { it in ALL_TABS }?.takeIf { it.size == ALL_TABS.size } ?: ALL_TABS)
        private set
    var tabsOff by mutableStateOf(prefs.getStringSet("tabs_off", emptySet())!!.toSet())
        private set
    val tabs get() = tabOrder.filter { it !in tabsOff }

    fun moveTab(id: String, by: Int) {
        val i = tabOrder.indexOf(id)
        val j = (i + by).coerceIn(0, tabOrder.lastIndex)
        if (i < 0 || i == j) return
        tabOrder = tabOrder.toMutableList().apply { add(j, removeAt(i)) }
        prefs.edit().putString("tabs", tabOrder.joinToString(",")).apply()
    }

    fun setTabOn(id: String, on: Boolean) {
        if (id == "chats" || id == "profile") return
        tabsOff = if (on) tabsOff - id else tabsOff + id
        prefs.edit().putStringSet("tabs_off", tabsOff).apply()
    }
    /** photo open full screen (file name or uri), null = viewer closed */
    var viewing by mutableStateOf<String?>(null)

    var quickReaction by mutableStateOf(prefs.getString("quick_reaction", "❤️")!!.takeIf(::isSingleEmoji) ?: "❤️")
        private set
    fun chooseQuickReaction(value: String) { if (!isSingleEmoji(value)) return; quickReaction = value; prefs.edit().putString("quick_reaction", value).apply() }

    // personal look, chosen in Профиль → Оформление
    var bubbleStyle by mutableStateOf(prefs.getString("bubble", "gray")!!)
        private set
    var avatarRing by mutableStateOf(prefs.getBoolean("ring", true))
        private set

    init { Photos.init(ctx, api.base, api.token) }
    // profile: look, privacy switches and editing; switches without a backend live only in prefs
    var palette by mutableStateOf(prefs.getString("palette", "black")!!)
        private set
    var accent by mutableStateOf(prefs.getString("accent", "violet")!!)
        private set
    var chatBg by mutableStateOf(prefs.getString("chat_bg", "plain")!!)
        private set
    var avaColor by mutableStateOf(prefs.getInt("ava_color", 0))
        private set
    var profileVis by mutableStateOf(prefs.getString("profile_vis", "all")!!)
        private set
    /** Short message on top of the screen, like "Профиль сохранён". */
    var toast by mutableStateOf("")
    private val flags = mutableStateMapOf<String, Boolean>()

    init { C.apply(palette, accent) }

    fun pickPalette(v: String) { palette = v; prefs.edit().putString("palette", v).apply(); C.apply(palette, accent) }
    fun pickAccent(v: String) { accent = v; prefs.edit().putString("accent", v).apply(); C.apply(palette, accent) }
    fun pickChatBg(v: String) { chatBg = v; prefs.edit().putString("chat_bg", v).apply() }
    fun nextAvaColor() { avaColor = (avaColor + 1) % 5; prefs.edit().putInt("ava_color", avaColor).apply() }
    fun setVis(v: String) { profileVis = v; prefs.edit().putString("profile_vis", v).apply() }
    fun flag(key: String) = flags[key] ?: prefs.getBoolean("flag_$key", true)
    fun setFlag(key: String, v: Boolean) {
        flags[key] = v; prefs.edit().putBoolean("flag_$key", v).apply()
        if (key == "online") sendPresence()
    }

    private fun sorted(list: List<Chat>) = list.sortedWith(compareByDescending<Chat> { it.pinned }.thenByDescending { it.last?.id ?: 0 })

    fun setPinned(chatId: Int, pinned: Boolean) = scope.launch {
        try {
            val chat = api.chatSettings(chatId, pinned = pinned)
            chats = sorted(chats.map { if (it.id == chatId) chat else it })
            toast = if (pinned) "Закреплено" else "Откреплено"
        } catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    fun renameGroup(chatId: Int, title: String) = scope.launch {
        try { replaceChat(api.groupTitle(chatId, title.trim())) } catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    fun setGroupAvatar(chatId: Int, jpeg: ByteArray) = scope.launch {
        try { replaceChat(api.groupAvatar(chatId, jpeg)); toast = "Фото группы обновлено" } catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    suspend fun searchIn(chatId: Int, q: String): List<Msg> = runCatching { api.search(chatId, q) }.getOrDefault(listOf())

    /** Loads older pages until message [id] is in memory (for jumping to a search result). */
    suspend fun loadUntil(chatId: Int, id: Int) {
        while (chatId !in historyEnd && (messages[chatId].orEmpty().firstOrNull { it.id > 0 }?.id ?: 0) > id) {
            val oldest = messages[chatId].orEmpty().firstOrNull { it.id > 0 }?.id ?: return
            val list = runCatching { api.messages(chatId, before = oldest) }.getOrNull() ?: return
            if (list.size < PAGE) historyEnd += chatId
            val have = messages[chatId].orEmpty()
            messages[chatId] = list.filter { m -> have.none { it.id == m.id } } + have
        }
    }

    /** Copies a message into another chat and opens it. */
    fun forward(m: Msg, toChatId: Int) {
        sendEvent(JSONObject().put("type", "send").put("chat_id", toChatId).put("forward", m.id).put("client_id", UUID.randomUUID().toString()))
        openChat(toChatId)
    }

    /** Tapping the reaction I already put takes it back; there is one reaction per person. */
    fun react(m: Msg, emoji: String) {
        if (!isSingleEmoji(emoji)) return
        val myId = me?.id ?: return
        val mineNow = m.reactions.entries.firstOrNull { myId in it.value }?.key
        val next = if (mineNow == emoji) "" else emoji
        // shown at once, the server event confirms
        val r = m.reactions.mapValues { (_, ids) -> ids - myId }.toMutableMap()
        if (next.isNotEmpty()) r[next] = r[next].orEmpty() + myId
        setReactions(m.chatId, m.id, r.filterValues { it.isNotEmpty() })
        sendEvent(JSONObject().put("type", "react").put("chat_id", m.chatId).put("message_id", m.id).put("emoji", next))
    }

    private fun setReactions(chatId: Int, id: Int, r: Map<String, List<Int>>) {
        messages[chatId] = messages[chatId].orEmpty().map { if (it.id == id) it.copy(reactions = r) else it }
    }

    /** The newest app published on the server; shown as a banner unless this version was skipped. */
    private fun checkUpdate() = scope.launch {
        lastUpdateCheck = System.currentTimeMillis()
        val info = runCatching { api.latest() }.getOrNull() ?: return@launch
        val code = info.optInt("code")
        latestCode = code
        if (code > appCode && code != prefs.getInt("skip_update", 0)) update = info.optString("version") to (api.base.trimEnd('/') + info.optString("path"))
    }
    private var latestCode = 0
    // the process lives for days (ChatService keeps it), so "once per process" meant never again: ask again on opening, at most every 10 min
    private var lastUpdateCheck = 0L

    /** 0.11.2: the new APK downloads inside the app, then Android's own «Установить» screen opens. */
    fun installUpdate() {
        val (_, url) = update ?: return
        if (Updater.progress != null) return
        scope.launch {
            val file = Updater.download(ctx, url)
            if (file == null) { toast = "Не скачалось, проверь интернет"; return@launch }
            if (!Updater.install(ctx, file)) toast = "Не получилось открыть установку"
        }
    }

    /** Shown once after an update: the version's news. A fresh install skips it. */
    var whatsNew by mutableStateOf(false)
    fun checkWhatsNew() {
        val seen = prefs.getInt("whats_new", 0)
        if (seen >= appCode) return
        val info = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val fresh = info != null && info.firstInstallTime == info.lastUpdateTime
        if (fresh || !loggedIn) prefs.edit().putInt("whats_new", appCode).apply() else whatsNew = true
    }
    fun closeWhatsNew() { whatsNew = false; prefs.edit().putInt("whats_new", appCode).apply() }

    /** "Позже": this version is not offered again, the next one will be. */
    fun skipUpdate() {
        prefs.edit().putInt("skip_update", latestCode).apply()
        update = null
    }

    fun reloadMe() = scope.launch { runCatching { api.me() }.onSuccess { me = it } }

    // ---------- 0.11: password, login, kents, spots ----------

    /** After the update (or a fresh signup) there is no password yet: a one-time screen asks for it. */
    private fun askPasswordIfNeeded() {
        if (me?.hasPassword == false && screen != SetPassword) screen = SetPassword
    }

    /** [username] = the first-time screen may change it; [old] = the current password when changing it. */
    fun setPassword(password: String, old: String = "", username: String? = null, done: (String?) -> Unit) = scope.launch {
        try {
            me = api.setPassword(password, old, username)
            toast = if (old.isEmpty()) "Пароль сохранён" else "Пароль изменён"
            screen = if (old.isEmpty()) Screen.Chats else Screen.Profile
            done(null)
        } catch (e: ApiError) { done(e.message) }
    }

    /** Signing in on a new phone: the same account, chats come from the server. */
    fun login(username: String, password: String, done: () -> Unit) {
        error = ""
        scope.launch {
            try {
                val fresh = Api(DEFAULT_SERVER)
                val (token, user) = fresh.login(username, password)
                fresh.token = token
                api = fresh
                Photos.init(ctx, fresh.base, token)
                prefs.edit().putString("server", fresh.base).putString("token", token).apply()
                me = user
                screen = Screen.Chats
                ChatService.start(ctx); connect(); refresh()
            } catch (e: ApiError) { error = e.message.orEmpty() }
            done()
        }
    }

    /** кент or просто знакомый: only kents see my spots for kents and get «Позвать на спот». */
    fun setKent(user: User, kent: Boolean) = scope.launch {
        try {
            api.setKent(user.id, kent)
            users = if (users.any { it.id == user.id }) users.map { if (it.id == user.id) it.copy(kent = kent) else it } else users + user.copy(kent = kent)
        } catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    fun isKent(userId: Int) = users.any { it.id == userId && it.kent }

    var spots by mutableStateOf(listOf<Spot>())
    /** a place to show when the Споты tab opens: a tapped spot card in a chat */
    var spotFocus by mutableStateOf<SpotRef?>(null)

    fun loadSpots() = scope.launch { runCatching { api.spots() }.onSuccess { spots = it } }

    /**
     * Uploads the new photos (local uris), keeps the ones already on the server (file names), then saves.
     * id == 0 = a new spot.
     */
    fun saveSpot(id: Int, title: String, kind: String, lat: Double, lng: Double, about: String, photos: List<String>, vis: String,
                 done: (String?) -> Unit) = scope.launch {
        try {
            val names = photos.map { p ->
                if (!p.contains("://")) p
                else {
                    val (jpeg) = Photos.prepare(Uri.parse(p)) ?: throw ApiError("Не получилось открыть фото")
                    api.upload(jpeg.toRequestBody("image/jpeg".toMediaType()), "jpg")
                }
            }
            val spot = api.saveSpot(id, title, kind, lat, lng, about, names, vis)
            spots = spots.filterNot { it.id == spot.id } + spot
            spotFocus = SpotRef(spot.id, spot.title, spot.kind, spot.lat, spot.lng)
            toast = if (id == 0) "Спот добавлен" else "Спот сохранён"
            screen = Screen.Stub(SPOTS_TAB)
            done(null)
        } catch (e: ApiError) { done(e.message) }
    }

    fun deleteSpot(spot: Spot) = scope.launch {
        try { api.deleteSpot(spot.id); spots = spots.filterNot { it.id == spot.id }; toast = "Спот удалён" }
        catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    fun callToSpot(spot: Spot, text: String) = scope.launch {
        try {
            val n = api.callToSpot(spot.id, text.trim())
            toast = if (n == 0) "Некому писать: все кенты заблокированы" else "Позвал кентов: $n"
        } catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    /** A spot card in a chat: open the Споты tab on that place. */
    fun showSpot(ref: SpotRef) {
        spotFocus = ref
        if ("map" in tabsOff) setTabOn("map", true)
        screen = Screen.Stub(SPOTS_TAB)
    }

    // ---------- 0.15 Beta: cards, «Я тут», смотреть вместе, звонки ----------

    /** A message that came back from an API call; the socket echo may already be here, so no duplicates. */
    private fun putMsg(m: Msg) {
        messages[m.chatId] = messages[m.chatId].orEmpty().filterNot { it.id == m.id } + m
        chats = sorted(chats.map { if (it.id == m.chatId && (it.last?.id ?: 0) <= m.id) it.copy(last = m) else it })
    }

    /** New state of a card: the message that carries it is redrawn, wherever it is. */
    private fun putCard(card: Card) {
        for ((chatId, list) in messages.toMap()) {
            if (list.none { it.card?.let { c -> c::class == card::class && c.id == card.id } == true }) continue
            messages[chatId] = list.map { m -> if (m.card?.let { c -> c::class == card::class && c.id == card.id } == true) m.copy(card = card) else m }
        }
        chats = chats.map { c -> c.last?.card?.let { old -> if (old::class == card::class && old.id == card.id) c.copy(last = c.last.copy(card = card)) else null } ?: c }
        if (card is WatchCard) watchCards[card.id] = card
        if (card is CallCard) Calls.onCard(ctx, card, me?.id)
    }

    private fun cardAction(block: suspend () -> Card) = scope.launch {
        try { putCard(block()) } catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    /** Creating a card: the message goes out, [done] closes the sheet on success. */
    private fun create(done: (Boolean) -> Unit, block: suspend (String) -> Msg) = scope.launch {
        try { putMsg(block(UUID.randomUUID().toString())); done(true) } catch (e: ApiError) { toast = e.message.orEmpty(); done(false) }
    }

    fun createPoll(chatId: Int, question: String, options: List<String>, multi: Boolean, anon: Boolean, done: (Boolean) -> Unit) =
        create(done) { api.createPoll(chatId, question, options, multi, anon, it) }

    fun vote(p: PollCard, options: List<Int>) = cardAction { api.vote(p.id, options) }
    fun closePoll(p: PollCard) = cardAction { api.closePoll(p.id) }

    fun createGather(chatId: Int, title: String, at: Long, place: String, spotId: Int, note: String, done: (Boolean) -> Unit) =
        create(done) { api.createGather(chatId, title, at, place, spotId, note, it) }

    fun rsvp(g: GatherCard, status: String) = cardAction { api.rsvp(g.id, status) }
    fun cancelGather(g: GatherCard) = cardAction { api.cancelGather(g.id) }

    /** "Куда кидать" from the last split, so it isn't typed every time. */
    val lastSplitDetails get() = prefs.getString("split_details", "")!!

    fun createSplit(chatId: Int, title: String, total: Int, userIds: List<Int>, details: String, done: (Boolean) -> Unit) {
        prefs.edit().putString("split_details", details).apply()
        create(done) { api.createSplit(chatId, title, total, userIds, details, it) }
    }

    fun splitPaid(s: SplitCard, userId: Int, paid: Boolean) = cardAction { api.splitPaid(s.id, userId, paid) }

    fun checkin(spot: Spot?) = scope.launch {
        try {
            api.checkin(spot?.id ?: 0)
            toast = if (spot != null) "Кенты видят, что ты на «${spot.title}» (3 часа)" else "Отметка снята"
            loadSpots()
        } catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    // смотреть вместе
    /** latest state of watch cards opened from somewhere else than the chat list */
    val watchCards = mutableStateMapOf<Int, WatchCard>()
    /** watch id -> the open room's listener; a map, because the old room is disposed after the new one starts */
    val onWatch = mutableMapOf<Int, (JSONObject) -> Unit>()

    fun createWatch(chatId: Int, source: String, src: String, title: String, done: (Boolean) -> Unit) =
        create(done) { api.createWatch(chatId, source, src, title, it) }

    /** Own video: uploaded with progress (up to 1 GB), then the card. */
    fun createWatchFromFile(chatId: Int, uri: Uri, title: String, onProgress: (Float) -> Unit, done: (Boolean) -> Unit) {
        val (name, size) = Files.info(ctx, uri)
        if (size > 1024L * 1024 * 1024) { toast = "Видео больше 1 ГБ"; done(false); return }
        create(done) { clientId ->
            var last = 0f
            val media = api.uploadBig(Files.Progress(Files.body(ctx, uri, size)) { p ->
                if (p - last > .01f || p >= 1f) { last = p; scope.launch { onProgress(p) } }
            }, Files.ext(name).lowercase().ifBlank { "mp4" })
            api.createWatch(chatId, "file", media, title.ifBlank { name.substringBeforeLast('.') }, clientId)
        }
    }

    suspend fun loadWatch(watchId: Int): WatchCard? = runCatching { api.watch(watchId) as? WatchCard }.getOrNull()?.also { putCard(it) }

    fun sendWatch(watchId: Int, action: String, pos: Double) {
        socket?.send(JSONObject().put("type", "watch").put("watch_id", watchId).put("action", action).put("pos", pos).toString())
    }

    fun endWatch(watchId: Int) = scope.launch { runCatching { api.endWatch(watchId) } }

    var activeWatch by mutableStateOf<WatchScreen?>(null)
    var watchFloating by mutableStateOf(false)

    fun closeWatch() {
        val room = activeWatch
        activeWatch = null
        watchFloating = false
        if (screen is WatchScreen && room != null) openChat(room.chatId)
    }

    fun openWatch(chatId: Int, watchId: Int) {
        if (messages[chatId] == null) openChat(chatId)
        activeWatch = WatchScreen(chatId, watchId)
        watchFloating = false
        screen = WatchScreen(chatId, watchId)
    }

    // звонки
    fun startCall(chatId: Int) {
        if (Calls.callId != 0) { Calls.minimized = false; return }
        Calls.pendingJoin = -chatId
        sendEvent(JSONObject().put("type", "call_start").put("chat_id", chatId))
    }

    fun joinCall(callId: Int, chatId: Int) {
        Calls.stopRinging(ctx)
        Calls.pendingJoin = callId
        sendEvent(JSONObject().put("type", "call_join").put("call_id", callId))
    }

    fun declineCall() {
        val r = Calls.ring ?: return
        Calls.stopRinging(ctx)
        sendEvent(JSONObject().put("type", "call_decline").put("call_id", r.callId))
    }

    fun hangUp() {
        val id = Calls.callId
        if (id != 0) sendEvent(JSONObject().put("type", "call_leave").put("call_id", id))
        Calls.end(ctx)
    }

    fun toggleMute() = Calls.toggleMute { m -> sendEvent(JSONObject().put("type", "call_mute").put("call_id", Calls.callId).put("muted", m)) }

    /** Answer / open from the call notification. */
    fun fromCallNotification(callId: Int, answer: Boolean) {
        val r = Calls.ring
        val mic = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        // without the microphone allowed the ring screen stays, its button asks for it
        if (answer && mic && r != null && r.callId == callId) joinCall(r.callId, r.chatId)
        else if (Calls.callId == callId) Calls.minimized = false
    }

    private fun handleCall(ev: JSONObject) {
        when (ev.getString("type")) {
            "call_ring" -> {
                val chatId = ev.getInt("chat_id")
                val chat = chats.firstOrNull { it.id == chatId }
                if (chat?.muted == true && chat.kind == "group") return
                Calls.incoming(ctx, Ring(ev.getInt("call_id"), chatId, ev.getInt("from"), ev.optString("from_name")), foreground, chat?.title ?: ev.optString("from_name"))
            }
            "call_joined" -> {
                val callId = ev.getInt("call_id")
                val chatId = ev.getInt("chat_id")
                if (Calls.pendingJoin != callId && Calls.pendingJoin != -chatId) return
                Calls.pendingJoin = 0
                Calls.begin(ctx, callId, chatId) { l -> api.callAudio(callId, l) }
                // the card may have come before we knew the call id
                messages[chatId].orEmpty().firstNotNullOfOrNull { (it.card as? CallCard)?.takeIf { c -> c.id == callId } }?.let { Calls.onCard(ctx, it, me?.id) }
            }
            // picked up on my other phone: this one stops ringing
            "call_taken" -> if (Calls.ring?.callId == ev.getInt("call_id") && Calls.pendingJoin != ev.getInt("call_id")) Calls.stopRinging(ctx)
            "call_end" -> {
                val id = ev.getInt("call_id")
                val r = Calls.ring
                if (r?.callId == id) {
                    Calls.stopRinging(ctx)
                    // rang and nobody answered on this phone
                    val chat = chats.firstOrNull { it.id == r.chatId }
                    if (chat != null && flag("notify")) notifier.show(chat.id, chat.title, if (chat.kind == "group") r.fromName else null, "📞 Пропущенный звонок")
                }
                if (Calls.callId == id) Calls.end(ctx)
            }
        }
    }

    /** null = couldn't check (no connection), the server checks again on save anyway. */
    suspend fun usernameFree(username: String): Boolean? = runCatching { api.usernameFree(username) }.getOrNull()

    fun saveProfile(name: String, username: String, about: String, birthday: String, gender: String, done: (String?) -> Unit) = scope.launch {
        try {
            me = api.updateMe(name, username, about, birthday, gender)
            toast = "Профиль сохранён"
            screen = Screen.Profile
            done(null)
        } catch (e: ApiError) { done(e.message) }
    }
    /** Avatar from the gallery: shrunk to 512px, the server tells everyone online to reload. */
    fun setAvatar(jpeg: ByteArray) = scope.launch {
        try { me = api.setAvatar(jpeg); toast = "Аватарка обновлена" } catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    fun dropAvatar() = scope.launch {
        try { me = api.dropAvatar(); toast = "Аватарка убрана" } catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    /** Kents by the start of the @username; empty query = nobody. */
    suspend fun search(q: String): List<User> = if (q.trim().removePrefix("@").isEmpty()) listOf()
        else runCatching { api.searchUsers(q.trim()) }.getOrDefault(listOf())

    /** The dm with this person, if there is one (for renaming from his profile). */
    fun dmWith(userId: Int): Chat? = chats.firstOrNull { c -> c.kind == "dm" && c.members.any { it.id == userId } }
    // end profile

    val server get() = api.base
    val loggedIn get() = api.token.isNotEmpty()
    val token get() = api.token

    fun setBubble(v: String) { bubbleStyle = v; prefs.edit().putString("bubble", v).apply() }
    fun setRing(v: Boolean) { avatarRing = v; prefs.edit().putBoolean("ring", v).apply() }

    /** Activity on screen: refresh and make sure the socket is up. */
    fun onStart() {
        foreground = true
        sendPresence()
        (screen as? Screen.Open)?.let { notifier.cancel(it.chatId) }
        if (loggedIn) { ChatService.start(ctx); connect(); refresh() }
    }

    // the socket stays open in the background: ChatService keeps the process alive for notifications
    fun onStop() {
        foreground = false
        sendPresence()
    }

    /** App on screen or not: the server panel shows "в сети" only while it's open, not for the background socket. */
    private fun sendPresence() {
        socket?.send(JSONObject().put("type", "presence").put("active", foreground).put("show", flag("online")).toString())
    }

    /** Online now / last seen of a kent; the live map wins over what came with the user lists. */
    fun presenceOf(userId: Int): Pair<Boolean, Long> = presence[userId] ?: (false to 0L)

    private fun rememberPresence(list: List<User>) {
        list.forEach { presence[it.id] = it.online to it.lastSeen }
    }

    /** Called by ChatService after a reboot or when Android restarts it. */
    fun keepAlive() {
        if (loggedIn) { connect(); if (me == null) refresh() }
    }

    /** one id per username typed: a retry after a lost answer gets the account the first try made */
    private val regIds = mutableMapOf<String, String>()

    fun register(invite: String, name: String, username: String, done: () -> Unit) {
        error = ""
        scope.launch {
            try {
                val fresh = Api(DEFAULT_SERVER)
                val requestId = regIds.getOrPut(username.lowercase()) { UUID.randomUUID().toString() }
                val (token, user) = fresh.register(invite, name, username, requestId)
                fresh.token = token
                api = fresh
                Photos.init(ctx, fresh.base, token)
                prefs.edit().putString("server", fresh.base).putString("token", token).apply()
                me = user
                screen = Screen.Chats
                askPasswordIfNeeded()
                ChatService.start(ctx); connect(); refresh()
            } catch (e: ApiError) {
                error = e.message.orEmpty()
            }
            done()
        }
    }

    /** Профиль → «Выйти на всех устройствах»: this phone stays in with a new token, the others drop to the login screen. */
    fun logoutAll() = scope.launch {
        try {
            val fresh = api.logoutAll()
            api.token = fresh
            Photos.init(ctx, api.base, fresh)
            prefs.edit().putString("token", fresh).apply()
            // the server closed every socket of the old token, mine too
            socket?.close(1000, null); socket = null
            connect()
            toast = "Готово: остальные устройства вышли"
        } catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    fun logout() {
        closeWatch()
        ChatService.stop(ctx)
        notifier.cancelAll()
        socket?.close(1000, null); socket = null
        prefs.edit().remove("token").apply()
        api = Api(api.base)
        Photos.init(ctx, api.base, "")
        botBusy.clear(); botStyle = "friendly"; botTarget = 0
        me = null; chats = listOf(); messages.clear(); spots = listOf(); users = listOf()
        screen = Screen.Login
    }

    fun refresh() = scope.launch {
        try {
            if (me == null) { me = api.me(); askPasswordIfNeeded() }
            chats = api.chats()
            users = api.users()
            blocked = api.blocks()
            loadSpots()
            rememberPresence(chats.flatMap { it.members } + users)
            if (System.currentTimeMillis() - lastUpdateCheck > 10 * 60_000L) checkUpdate()
            error = ""
        } catch (e: ApiError) {
            if (e.message == "bad token") logout() else error = e.message.orEmpty()
        }
    }

    fun openChat(chatId: Int) {
        screen = Screen.Open(chatId)
        notifier.cancel(chatId)
        scope.launch {
            runCatching { api.messages(chatId) }.onSuccess { list ->
                // keep messages still on the way (the server doesn't know them yet) and older pages already loaded
                val old = messages[chatId].orEmpty()
                val first = list.firstOrNull()?.id ?: Int.MAX_VALUE
                messages[chatId] = old.filter { it.id in 1 until first } + list + old.filter { it.id < 0 }
                if (list.size < PAGE) historyEnd += chatId
                markRead(chatId)
            }
        }
    }

    /** Next 100 older messages when the chat is scrolled up to the oldest loaded one. */
    fun loadOlder(chatId: Int) {
        val oldest = messages[chatId].orEmpty().firstOrNull { it.id > 0 }?.id ?: return
        if (chatId in historyEnd || !loadingOlder.add(chatId)) return
        scope.launch {
            runCatching { api.messages(chatId, before = oldest) }.onSuccess { list ->
                if (list.size < PAGE) historyEnd += chatId
                val have = messages[chatId].orEmpty()
                messages[chatId] = list.filter { m -> have.none { it.id == m.id } } + have
            }
            loadingOlder -= chatId
        }
    }

    fun openDm(user: User) = scope.launch {
        try {
            val chat = api.openDm(user.id)
            if (chats.none { it.id == chat.id }) chats = listOf(chat) + chats
            openChat(chat.id)
        } catch (e: ApiError) { error = e.message.orEmpty() }
    }

    fun createChannel(title: String, userIds: List<Int>, done: () -> Unit) = scope.launch {
        try {
            val chat = api.createChannel(title, userIds)
            if (chats.none { it.id == chat.id }) chats = listOf(chat) + chats
            openChat(chat.id)
        } catch (e: ApiError) { error = e.message.orEmpty() }
        done()
    }

    fun createGroup(title: String, userIds: List<Int>, done: () -> Unit) = scope.launch {
        try {
            val chat = api.createGroup(title, userIds)
            if (chats.none { it.id == chat.id }) chats = listOf(chat) + chats
            openChat(chat.id)
        } catch (e: ApiError) { error = e.message.orEmpty() }
        done()
    }

    fun addMembers(chatId: Int, userIds: List<Int>, done: () -> Unit) = scope.launch {
        try {
            val chat = api.addMembers(chatId, userIds)
            chats = chats.map { if (it.id == chatId) chat else it }
            screen = Screen.GroupInfo(chatId)
        } catch (e: ApiError) { error = e.message.orEmpty() }
        done()
    }

    fun leave(chatId: Int) = scope.launch {
        try {
            api.leave(chatId)
            chats = chats.filterNot { it.id == chatId }
            messages.remove(chatId)
            screen = Screen.Chats
        } catch (e: ApiError) { error = e.message.orEmpty() }
    }

    // ---------- chat menu (UI in ChatMenu.kt) ----------

    private fun replaceChat(chat: Chat) { chats = chats.map { if (it.id == chat.id) chat else it } }

    fun renameChat(chatId: Int, alias: String) = scope.launch {
        try { replaceChat(api.chatSettings(chatId, alias = alias.trim())); toast = if (alias.isBlank()) "Имя сброшено" else "Переименовано" }
        catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    fun setMuted(chatId: Int, muted: Boolean) = scope.launch {
        try {
            replaceChat(api.chatSettings(chatId, muted = muted))
            if (muted) notifier.cancel(chatId)
            toast = if (muted) "Уведомления выключены" else "Уведомления включены"
        } catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    fun clearChat(chatId: Int) = scope.launch {
        try {
            api.clearChat(chatId)
            chats = chats.filterNot { it.id == chatId }
            messages.remove(chatId); notifier.cancel(chatId)
            toast = "Переписка удалена"
        } catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    fun setBlocked(user: User, block: Boolean) = scope.launch {
        try {
            if (block) api.block(user.id) else api.unblock(user.id)
            blocked = if (block) blocked + user else blocked.filterNot { it.id == user.id }
            toast = if (block) "${user.name} заблокирован" else "${user.name} разблокирован"
        } catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    fun isBlocked(userId: Int) = blocked.any { it.id == userId }

    fun removeKent(user: User, chatId: Int) = scope.launch {
        try {
            api.removeKent(user.id)
            chats = chats.filterNot { it.id == chatId }
            users = users.map { if (it.id == user.id) it.copy(removed = true) else it }
            messages.remove(chatId); notifier.cancel(chatId)
            toast = "${user.name} удалён из кентов"
        } catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    fun user(id: Int): User? = if (id == me?.id) me
        else users.firstOrNull { it.id == id } ?: chats.firstNotNullOfOrNull { c -> c.members.firstOrNull { it.id == id } }

    fun userName(id: Int): String = when (id) {
        -1 -> "JT бот"
        me?.id -> me!!.name
        else -> chats.firstNotNullOfOrNull { c -> c.members.firstOrNull { it.id == id } }?.name
            ?: users.firstOrNull { it.id == id }?.name ?: "Кент"
    }

    fun pinMessage(chatId: Int, messageId: Int) = scope.launch {
        try {
            api.pinMessage(chatId, messageId)
            chats = chats.map { if (it.id == chatId) it.copy(pinnedMessage = messageId) else it }
        } catch (e: ApiError) { toast = e.message.orEmpty() }
    }

    fun send(chatId: Int, text: String, replyTo: Int = 0) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val clientId = UUID.randomUUID().toString()
        val myId = me?.id ?: return
        // shown at once with a clock; replaced when the server echoes it back
        val temp = Msg(-(System.nanoTime() % Int.MAX_VALUE).toInt() - 1, chatId, myId, clean, System.currentTimeMillis() / 1000, clientId, replyTo = replyTo)
        messages[chatId] = messages[chatId].orEmpty() + temp
        sendEvent(JSONObject().put("type", "send").put("chat_id", chatId).put("text", clean).put("client_id", clientId).put("reply_to", replyTo))
    }

    // ---------- message actions (UI in MsgActions.kt) ----------

    fun editMessage(m: Msg, text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || clean == m.text) return
        sendEvent(JSONObject().put("type", "edit").put("chat_id", m.chatId).put("message_id", m.id).put("text", clean))
    }

    fun deleteMessage(m: Msg) {
        sendEvent(JSONObject().put("type", "delete").put("chat_id", m.chatId).put("message_id", m.id))
    }

    private fun onEdited(m: Msg) {
        messages[m.chatId] = messages[m.chatId].orEmpty().map { if (it.id == m.id) m else it }
        chats = chats.map { if (it.last?.id == m.id) it.copy(last = m) else it }
    }

    private fun onDeleted(chatId: Int, id: Int) {
        // replies to it keep their plate, now saying "Сообщение удалено"
        messages[chatId] = messages[chatId].orEmpty().filterNot { it.id == id }
            .map { if (it.replyTo == id) it.copy(quote = Quote(id, 0, "", true)) else it }
        // the server knows the new last message and unread count
        val chat = chats.firstOrNull { it.id == chatId } ?: return
        if (chat.last?.id == id || chat.unread > 0) refresh()
    }

    /** Shows the photo at once from the phone, then uploads; the server echo replaces the temp message. */
    fun sendPhoto(chatId: Int, uri: Uri, caption: String) {
        val myId = me?.id ?: return
        val clientId = UUID.randomUUID().toString()
        val temp = Msg(-(System.nanoTime() % Int.MAX_VALUE).toInt() - 1, chatId, myId, caption.trim(), System.currentTimeMillis() / 1000,
            clientId, photo = uri.toString())
        messages[chatId] = messages[chatId].orEmpty() + temp
        scope.launch {
            val ready = Photos.prepare(uri)
            if (ready == null) { dropTemp(chatId, clientId); error = "Не получилось открыть фото"; return@launch }
            val (jpeg, w, h) = ready
            messages[chatId] = messages[chatId].orEmpty().map { if (it.clientId == clientId && it.id < 0) it.copy(photoW = w, photoH = h) else it }
            try {
                api.sendPhoto(chatId, jpeg, w, h, caption.trim(), clientId)
            } catch (e: ApiError) {
                dropTemp(chatId, clientId); toast = e.message.orEmpty()
            }
        }
    }

    /** A picture from the clipboard: copied to the cache first, the other app's permission may not last. */
    fun sendPastedPhoto(chatId: Int, uri: Uri, caption: String) = scope.launch {
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val f = java.io.File(ctx.cacheDir, "paste-${System.currentTimeMillis()}")
                ctx.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
                f
            }.getOrNull()
        }
        if (file == null) toast = "Не получилось вставить фото" else sendPhoto(chatId, Uri.fromFile(file), caption)
    }

    /** messageId -> where the file was saved, so a second tap opens it */
    val saved = mutableStateMapOf<Int, Uri>()

    fun downloadFile(m: Msg) {
        val src = m.media ?: return
        val key = "dl${m.id}"
        if (key in progress) return
        progress[key] = 0f
        scope.launch {
            var last = 0f
            val uri = Files.download(ctx, mediaUrl(this@Store, src), m.mediaName) { p ->
                if (p - last > .02f || p >= 1f) { last = p; scope.launch { if (key in progress) progress[key] = p } }
            }
            progress.remove(key)
            if (uri == null) toast = "Не скачалось, нет связи"
            else { saved[m.id] = uri; toast = "Сохранено в Загрузки/JustTalk"; Files.open(ctx, uri) }
        }
    }

    /** Several photos from the picker: each is its own message, the caption goes with the first. */
    fun sendPhotos(chatId: Int, uris: List<Uri>, caption: String) = uris.forEachIndexed { i, u -> sendPhoto(chatId, u, if (i == 0) caption else "") }

    /** Any file from the phone up to 100 MB; streamed from the disk, never read into memory as a whole. */
    fun sendFile(chatId: Int, uri: Uri, caption: String, replyTo: Int = 0) {
        val (name, size) = Files.info(ctx, uri)
        if (size > Files.MAX) { toast = "Файл больше 100 МБ"; return }
        sendMedia(chatId, "file", Files.body(ctx, uri, size), Files.ext(name), uri.toString(), name = name, size = size, caption = caption, replyTo = replyTo)
    }

    fun sendVoice(chatId: Int, file: java.io.File, durationMs: Int, wave: String, replyTo: Int = 0) =
        sendMedia(chatId, "voice", file.asRequestBody("audio/mp4".toMediaType()), "m4a", Uri.fromFile(file).toString(),
            size = file.length(), duration = durationMs, wave = wave, replyTo = replyTo)

    fun sendCircle(chatId: Int, file: java.io.File, durationMs: Int, thumb: ByteArray?, replyTo: Int = 0) =
        sendMedia(chatId, "circle", file.asRequestBody("video/mp4".toMediaType()), "mp4", Uri.fromFile(file).toString(),
            size = file.length(), duration = durationMs, thumbJpeg = thumb, replyTo = replyTo)

    /** Shows the message at once from the local file, uploads it with progress, then posts the message. */
    private fun sendMedia(chatId: Int, kind: String, body: okhttp3.RequestBody, ext: String, local: String, name: String = "", size: Long = 0,
                          duration: Int = 0, wave: String = "", thumbJpeg: ByteArray? = null, caption: String = "", replyTo: Int = 0) {
        val myId = me?.id ?: return
        val clientId = UUID.randomUUID().toString()
        val temp = Msg(-(System.nanoTime() % Int.MAX_VALUE).toInt() - 1, chatId, myId, caption.trim(), System.currentTimeMillis() / 1000, clientId,
            replyTo = replyTo, media = local, mediaKind = kind, mediaName = name, mediaSize = size, duration = duration, wave = wave)
        messages[chatId] = messages[chatId].orEmpty() + temp
        progress[clientId] = 0f
        scope.launch {
            try {
                val thumb = thumbJpeg?.let { api.upload(it.toRequestBody("image/jpeg".toMediaType()), "jpg") }.orEmpty()
                var last = 0f
                val media = api.upload(Files.Progress(body) { p ->
                    // from the upload thread; a few updates are enough
                    if (p - last > .02f || p >= 1f) { last = p; scope.launch { if (clientId in progress) progress[clientId] = p } }
                }, ext)
                api.sendMedia(chatId, kind, media, name, duration, wave, thumb, caption.trim(), clientId, replyTo)
            } catch (e: ApiError) {
                dropTemp(chatId, clientId); toast = e.message.orEmpty()
            }
            progress.remove(clientId)
        }
    }

    // photos are not retried like text: they would need the file again, so a failed one just disappears with an error
    private fun dropTemp(chatId: Int, clientId: String) {
        messages[chatId] = messages[chatId].orEmpty().filterNot { it.id < 0 && it.clientId == clientId }
    }

    private var lastTyping = 0L
    fun typing(chatId: Int) {
        val now = System.currentTimeMillis()
        if (now - lastTyping < 2000) return
        lastTyping = now
        sendEvent(JSONObject().put("type", "typing").put("chat_id", chatId))
    }

    private fun markRead(chatId: Int) {
        val lastId = messages[chatId].orEmpty().lastOrNull { it.id > 0 }?.id ?: return
        chats = chats.map { if (it.id == chatId) it.copy(unread = 0) else it }
        sendEvent(JSONObject().put("type", "read").put("chat_id", chatId).put("message_id", lastId))
    }

    private fun sendEvent(ev: JSONObject) {
        if (socket?.send(ev.toString()) != true) error = "Нет связи, сообщение уйдёт после переподключения"
    }

    private val appVersion = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
    private val appCode = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt() }.getOrDefault(0)

    private fun connect() {
        if (socket != null || !loggedIn) return
        api.version = appVersion
        var mine: WebSocket? = null
        mine = api.connect(
            onEvent = { ev -> scope.launch { handle(ev) } },
            onOpen = { scope.launch { online = true; error = ""; retryDelay = 2000L; sendPresence(); resendPending() } },
            onClosed = {
                scope.launch {
                    // a socket closed by logout must not clear the next one
                    if (socket !== mine) return@launch
                    online = false
                    socket = null
                    // server down or no network: retry, waiting longer each time (up to 30 s) to save battery
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
                    if (loggedIn) { connect(); refresh() }
                }
            },
        )
        socket = mine
    }

    private fun resendPending() {
        messages.values.flatten().filter { it.id < 0 && it.photo == null && it.media == null }.forEach {
            socket?.send(JSONObject().put("type", "send").put("chat_id", it.chatId).put("text", it.text).put("client_id", it.clientId).put("reply_to", it.replyTo).toString())
        }
    }

    private fun notify(m: Msg) {
        if (m.senderId == me?.id || m.senderId == 0) return
        // the ring has its own screen / notification
        if (m.card is CallCard) return
        // Профиль → Уведомления
        if (!flag("notify")) return
        val chat = chats.firstOrNull { it.id == m.chatId } ?: return
        if (chat.muted) return
        val who = if (chat.kind == "group") userName(m.senderId) else null
        notifier.show(chat.id, chat.title, who, m.preview())
    }

    private fun handle(ev: JSONObject) {
        when (ev.getString("type")) {
            "message" -> {
                val m = ev.getJSONObject("message").toMsg()
                val clientId = ev.optString("client_id")
                val list = messages[m.chatId].orEmpty().filterNot { it.id == m.id || (it.id < 0 && it.clientId == clientId) }
                messages[m.chatId] = list + m
                if (m.senderId == typingWho[m.chatId]) { typingWho.remove(m.chatId); typingUntil.remove(m.chatId) }
                val known = chats.any { it.id == m.chatId }
                if (!known) {
                    // first message in a chat we don't know yet: load it, then notify
                    scope.launch { refresh().join(); notify(m) }
                    return
                }
                val viewing = foreground && (screen as? Screen.Open)?.chatId == m.chatId
                val mine = m.senderId == me?.id
                chats = sorted(chats.map {
                    if (it.id != m.chatId) it
                    else it.copy(last = m, unread = if (mine || viewing) 0 else it.unread + 1)
                })
                if (viewing && !mine) markRead(m.chatId)
                if (!viewing) notify(m)
            }
            // the dm is blocked one way or the other: drop the pending message and say why
            "bot_status" -> botBusy[ev.getInt("chat_id")] = ev.optBoolean("busy")
            "blocked" -> {
                dropTemp(ev.getInt("chat_id"), ev.optString("client_id"))
                toast = ev.optString("reason")
            }
            "edit" -> onEdited(ev.getJSONObject("message").toMsg())
            "delete" -> onDeleted(ev.getInt("chat_id"), ev.getInt("message_id"))
            // group created, members changed or I was removed: just reload the list
            "chat" -> refresh()
            "read" -> {
                val chatId = ev.getInt("chat_id")
                // in a group "read" means read by everyone, the server counts that
                if (chats.firstOrNull { it.id == chatId }?.kind == "group" && ev.getInt("user_id") != me?.id) { refresh(); return }
                val mid = ev.getInt("message_id")
                chats = chats.map {
                    when {
                        it.id != chatId -> it
                        ev.getInt("user_id") == me?.id -> it.copy(unread = 0)
                        else -> it.copy(othersRead = maxOf(it.othersRead, mid))
                    }
                }
            }
            "reactions" -> setReactions(ev.getInt("chat_id"), ev.getInt("message_id"), ev.getJSONObject("reactions").toReactions())
            "card" -> putCard(ev.getJSONObject("card").toCard())
            "watch" -> onWatch[ev.optInt("watch_id")]?.invoke(ev)
            "call_ring", "call_joined", "call_taken", "call_end" -> handleCall(ev)
            "spots" -> loadSpots()
            "presence" -> presence[ev.getInt("user_id")] = ev.optBoolean("online") to ev.optLong("last_seen")
            "typing" -> {
                val chatId = ev.getInt("chat_id")
                typingUntil[chatId] = System.currentTimeMillis() + 3000
                typingWho[chatId] = ev.getInt("user_id")
                scope.launch {
                    delay(3100)
                    if ((typingUntil[chatId] ?: 0) <= System.currentTimeMillis()) typingUntil.remove(chatId)
                }
            }
        }
    }

    companion object {
        // release: the tunnel to the server PC; debug: the server on the Mac (see build.gradle.kts)
        const val DEFAULT_SERVER = BuildConfig.SERVER
        /** the server sends history in pages of this size */
        private const val PAGE = 100
        val ALL_TABS = listOf("chats", "map", "profile")
        /** title of the Споты tab screen (Screen.Stub keeps tabs at depth 1) */
        const val SPOTS_TAB = "Споты"

        @Volatile private var instance: Store? = null

        /** One Store per process: the activity and ChatService share the same socket and state. */
        fun get(ctx: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(ctx.applicationContext).also { instance = it }
        }
    }
}
