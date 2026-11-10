package com.fayor.justtalk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** removed = I deleted this kent: hidden from my lists until I write to him again. */
data class User(
    val id: Int, val name: String, val username: String, val about: String = "", val removed: Boolean = false,
    /** avatar = server file name or ""; birthday = "ДД.ММ.ГГГГ" or ""; gender = "m", "f" or "" */
    val avatar: String = "", val birthday: String = "", val gender: String = "",
    /** app open on screen right now; lastSeen = unix seconds, 0 = hidden or never */
    val online: Boolean = false, val lastSeen: Long = 0,
    /** 0.11: only in my own profile — false = the app asks to set a password; kent = I marked him as кент, not просто знакомый */
    val hasPassword: Boolean = true, val kent: Boolean = false, val corresponded: Boolean = false,
)

/** "jt" belongs to the creator and is shown as a bold, highlighted @JT. */
val User.isOwner get() = username == "jt"
fun User.handle() = if (isOwner) "@JT" else "@$username"

/** id < 0 means "still on the way to the server"; clientId matches it to the server echo. */
data class BotMeta(val requestedBy: Int, val requestedName: String, val trace: List<String>, val requestId: Int, val error: Boolean, val targetChatId: Int = 0)

data class Msg(
    val id: Int, val chatId: Int, val senderId: Int, val text: String, val created: Long, val clientId: String? = null,
    /** server file name, or a content:// uri while my photo uploads */
    val photo: String? = null, val photoW: Int = 0, val photoH: Int = 0,
    // message actions (MsgActions.kt): replyTo = 0 when not a reply
    val replyTo: Int = 0, val edited: Boolean = false, val quote: Quote? = null,
    // 0.10: voice / circle / file (media = server file name, or a local uri while it uploads), forwards, reactions
    val media: String? = null, val mediaKind: String = "", val mediaName: String = "", val mediaSize: Long = 0,
    val duration: Int = 0, val wave: String = "", val thumb: String = "",
    val fwdName: String = "",
    /** emoji -> who put it */
    val reactions: Map<String, List<Int>> = emptyMap(),
    /** 0.11: «Позвать на спот» — a copy of the spot, so the card works even if the spot is hidden from me */
    val spot: SpotRef? = null,
    /** 0.15: a live card (poll, сбор, скидываемся, смотрим вместе, звонок); see Cards.kt */
    val card: Card? = null,
    val bot: BotMeta? = null,
)

data class SpotRef(val id: Int, val title: String, val kind: String, val lat: Double, val lng: Double)

/** A place on the map; kind = a preset id (SPOT_KINDS) or the author's own category; vis = "kents" or "me". */
data class Spot(
    val id: Int, val ownerId: Int, val ownerName: String, val title: String, val kind: String,
    val lat: Double, val lng: Double, val about: String, val photos: List<String>, val vis: String, val created: Long,
    /** 0.15 «Я тут»: who is there right now (me and those who marked me as кент) */
    val here: List<Here> = emptyList(),
)

data class Here(val userId: Int, val name: String, val avatar: String, val at: Long)

fun JSONObject.toSpot() = Spot(
    getInt("id"), getInt("owner_id"), optString("owner_name"), getString("title"), getString("kind"),
    getDouble("lat"), getDouble("lng"), optString("about"),
    optJSONArray("photos")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(), optString("vis", "kents"), optLong("created"),
    here = optJSONArray("here")?.let { a -> List(a.length()) { a.getJSONObject(it).let { h -> Here(h.getInt("user_id"), h.optString("name"), h.optString("avatar"), h.optLong("at")) } } }.orEmpty(),
)

/** One line for the chat list and notifications. */
fun Msg.preview(): String = when {
    card != null -> card.label()
    photo != null && text.isBlank() -> "📷 Фото"
    photo != null -> "📷 $text"
    mediaKind == "voice" -> "🎤 Голосовое"
    mediaKind == "circle" -> "⏺ Кружок"
    mediaKind == "file" -> "📎 " + (text.ifBlank { mediaName.ifBlank { "Файл" } })
    spot != null -> "📍 ${spot.title}"
    else -> text
}

/** "2,4 МБ" */
fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.ROOT, "%.1f МБ", bytes / 1048576.0).replace('.', ',')
    bytes >= 1024 -> "${bytes / 1024} КБ"
    else -> "$bytes Б"
}

data class Chat(
    val id: Int,
    val kind: String,
    val title: String,
    val members: List<User>,
    val last: Msg?,
    val unread: Int,
    val othersRead: Int,
    // personal settings from the long-press menu; title already shows the alias
    val alias: String = "",
    val muted: Boolean = false,
    /** group photo (server file name) and the moment I pinned the chat, 0 = not pinned */
    val avatar: String = "",
    val pinned: Long = 0,
    val pinnedMessage: Int = 0,
    val ownerId: Int = 0,
    val hasHistory: Boolean = false,
)

class ApiError(message: String) : Exception(message)

fun JSONObject.toUser() = User(
    getInt("id"), getString("name"), getString("username"), optString("about"), optBoolean("removed"),
    optString("avatar"), optString("birthday"), optString("gender"),
    optBoolean("online"), optLong("last_seen"),
    // absent = someone else's profile or an old server: never ask for a password because of that
    hasPassword = optBoolean("has_password", true), kent = optBoolean("kent"), corresponded = optBoolean("corresponded"),
)

fun User.photo(): String? = avatar.ifEmpty { null }

/** Avatar photo of a chat: the other person's in a dm, the group's own photo otherwise. */
fun Chat.photo(meId: Int?): String? = if (kind == "dm") members.firstOrNull { it.id != meId }?.avatar?.ifEmpty { null } else avatar.ifEmpty { null }

fun JSONObject.toMsg() = Msg(
    getInt("id"), getInt("chat_id"), getInt("sender_id"), getString("text"), getLong("created"),
    photo = if (isNull("photo")) null else optString("photo").ifEmpty { null },
    photoW = optInt("photo_w"), photoH = optInt("photo_h"),
    replyTo = optInt("reply_to"), edited = optBoolean("edited"), quote = optJSONObject("reply")?.toQuote(),
    media = if (isNull("media")) null else optString("media").ifEmpty { null },
    mediaKind = optString("media_kind"), mediaName = optString("media_name"), mediaSize = optLong("media_size"),
    duration = optInt("duration"), wave = optString("wave"), thumb = optString("thumb"),
    fwdName = optString("fwd_name"),
    reactions = optJSONObject("reactions")?.toReactions() ?: emptyMap(),
    spot = optJSONObject("spot")?.let { SpotRef(it.getInt("id"), it.getString("title"), it.optString("kind"), it.getDouble("lat"), it.getDouble("lng")) },
    card = optJSONObject("card")?.toCard(),
    bot = optJSONObject("bot")?.let { b -> BotMeta(b.optInt("requested_by"), b.optString("requested_name"), b.optJSONArray("trace")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(), b.optInt("request_id"), b.optBoolean("error"), b.optInt("target_chat_id")) },
)

fun JSONObject.toReactions(): Map<String, List<Int>> =
    keys().asSequence().associateWith { k -> getJSONArray(k).let { a -> List(a.length()) { a.getInt(it) } } }

fun JSONObject.toChat() = Chat(
    id = getInt("id"),
    kind = getString("kind"),
    title = getString("title"),
    members = getJSONArray("members").let { a -> List(a.length()) { a.getJSONObject(it).toUser() } },
    last = optJSONObject("last")?.toMsg(),
    unread = getInt("unread"),
    othersRead = getInt("others_read"),
    alias = optString("alias"),
    muted = optBoolean("muted"),
    avatar = optString("avatar"),
    pinned = optLong("pinned"),
    pinnedMessage = optInt("pinned_message"),
    ownerId = optInt("owner_id"),
    hasHistory = optBoolean("has_history"),
)

/** Thin client for main.py: blocking OkHttp calls moved to the IO dispatcher. */
class Api(val base: String, var token: String = "") {
    /** sent with the socket so the server panel shows who runs which version */
    var version = ""
    // the tunnel over mobile internet can take a few seconds to answer the first request
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** [retry]: safe to repeat (reads, register with its request id), so a network hiccup is tried again twice. */
    private suspend fun call(method: String, path: String, body: JSONObject? = null, retry: Boolean = method == "GET"): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(base.trimEnd('/') + path)
            .header("Authorization", token)
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
            .build()
        val res = execute(req, retry)
        res.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError(errorText(r, text))
            text
        }
    }

    private suspend fun execute(req: Request, retry: Boolean): Response {
        if (retry) repeat(2) { attempt ->
            try { return http.newCall(req).execute() } catch (e: Exception) { delay(1500L * (attempt + 1)) }
        }
        return try { http.newCall(req).execute() } catch (e: Exception) { throw ApiError("Нет связи с сервером") }
    }

    // FastAPI puts the reason in "detail": a string for our errors, a list for validation errors
    private fun errorText(r: Response, text: String): String = runCatching {
        when (val d = JSONObject(text).get("detail")) {
            is String -> d
            else -> "Ошибка ${r.code}"
        }
    }.getOrDefault("Ошибка ${r.code}")

    suspend fun botChat(): Chat = JSONObject(call("POST", "/bot/chat", JSONObject())).toChat()
    suspend fun botSettings(): JSONObject = JSONObject(call("GET", "/bot/settings"))
    suspend fun setBotSettings(style: String, target: Int): JSONObject = JSONObject(call("POST", "/bot/settings", JSONObject().put("style", style).put("target_chat_id", target)))
    suspend fun botState(chatId: Int): Boolean = JSONObject(call("GET", "/bot/state/$chatId")).optBoolean("busy")
    suspend fun retryBot(messageId: Int) { call("POST", "/bot/retry/$messageId", JSONObject()) }
    suspend fun sendBotDraft(id: Int): Card = JSONObject(call("POST", "/bot/drafts/$id/send", JSONObject())).toCard()
    suspend fun botEqual(splitId: Int): Card = JSONObject(call("POST", "/bot/splits/$splitId/equal", JSONObject())).toCard()
    suspend fun botRemind(splitId: Int) { call("POST", "/bot/splits/$splitId/remind", JSONObject()) }

    suspend fun register(invite: String, name: String, username: String, requestId: String): Pair<String, User> {
        val body = JSONObject().put("invite", invite).put("name", name).put("username", username).put("request_id", requestId)
        val o = JSONObject(call("POST", "/register", body, retry = true))
        return o.getString("token") to o.getJSONObject("user").toUser()
    }

    suspend fun me(): User = JSONObject(call("GET", "/me")).toUser()

    // profile
    suspend fun usernameFree(username: String): Boolean = JSONObject(call("GET", "/username/$username")).getBoolean("free")

    suspend fun updateMe(name: String, username: String, about: String, birthday: String, gender: String): User =
        JSONObject(call("POST", "/me", JSONObject().put("name", name).put("username", username).put("about", about)
            .put("birthday", birthday).put("gender", gender))).toUser()

    suspend fun searchUsers(q: String): List<User> =
        JSONArray(call("GET", "/users/search?q=" + URLEncoder.encode(q, "UTF-8"))).let { a -> List(a.length()) { a.getJSONObject(it).toUser() } }

    suspend fun setAvatar(jpeg: ByteArray): User = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base.trimEnd('/') + "/me/avatar").header("Authorization", token)
            .post(jpeg.toRequestBody("image/jpeg".toMediaType())).build()
        val res = try { upload.newCall(req).execute() } catch (e: Exception) { throw ApiError("Фото не загрузилось, нет связи") }
        res.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError(errorText(r, text))
            JSONObject(text).toUser()
        }
    }

    suspend fun dropAvatar(): User = JSONObject(call("DELETE", "/me/avatar")).toUser()

    suspend fun users(): List<User> = JSONArray(call("GET", "/users")).let { a -> List(a.length()) { a.getJSONObject(it).toUser() } }

    suspend fun chats(): List<Chat> = JSONArray(call("GET", "/chats")).let { a -> List(a.length()) { a.getJSONObject(it).toChat() } }

    suspend fun openDm(userId: Int): Chat = JSONObject(call("POST", "/chats/dm", JSONObject().put("user_id", userId))).toChat()

    suspend fun createChannel(title: String, userIds: List<Int>): Chat =
        JSONObject(call("POST", "/chats/channel", JSONObject().put("title", title).put("user_ids", JSONArray(userIds)))).toChat()

    suspend fun createGroup(title: String, userIds: List<Int>): Chat =
        JSONObject(call("POST", "/chats/group", JSONObject().put("title", title).put("user_ids", JSONArray(userIds)))).toChat()

    suspend fun addMembers(chatId: Int, userIds: List<Int>): Chat =
        JSONObject(call("POST", "/chats/$chatId/members", JSONObject().put("user_ids", JSONArray(userIds)))).toChat()

    suspend fun leave(chatId: Int) { call("POST", "/chats/$chatId/leave", JSONObject()) }

    // chat menu
    suspend fun chatSettings(chatId: Int, alias: String? = null, muted: Boolean? = null, pinned: Boolean? = null): Chat {
        val body = JSONObject()
        if (alias != null) body.put("alias", alias)
        if (muted != null) body.put("muted", muted)
        if (pinned != null) body.put("pinned", pinned)
        return JSONObject(call("POST", "/chats/$chatId/settings", body)).toChat()
    }

    suspend fun clearChat(chatId: Int) { call("POST", "/chats/$chatId/clear", JSONObject()) }

    suspend fun blocks(): List<User> = JSONArray(call("GET", "/blocks")).let { a -> List(a.length()) { a.getJSONObject(it).toUser() } }

    suspend fun block(userId: Int) { call("POST", "/blocks/$userId", JSONObject()) }

    suspend fun unblock(userId: Int) { call("DELETE", "/blocks/$userId") }

    suspend fun removeKent(userId: Int) { call("POST", "/kents/$userId/remove", JSONObject()) }

    /** The newest 100, or the 100 before message [before]. */
    suspend fun messages(chatId: Int, before: Int = 0): List<Msg> =
        JSONArray(call("GET", "/chats/$chatId/messages?before=$before")).let { a -> List(a.length()) { a.getJSONObject(it).toMsg() } }

    /** Uploads a ready JPEG; the server answers and also pushes the message to everyone with our clientId. */
    suspend fun sendPhoto(chatId: Int, jpeg: ByteArray, w: Int, h: Int, caption: String, clientId: String): Msg = withContext(Dispatchers.IO) {
        val url = base.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("chats/$chatId/photo")
            .addQueryParameter("w", "$w").addQueryParameter("h", "$h")
            .addQueryParameter("caption", caption).addQueryParameter("client_id", clientId)
            .build()
        val req = Request.Builder().url(url).header("Authorization", token)
            .post(jpeg.toRequestBody("image/jpeg".toMediaType())).build()
        val res = try { upload.newCall(req).execute() } catch (e: Exception) { throw ApiError("Фото не отправилось, нет связи") }
        res.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError(errorText(r, text))
            JSONObject(text).toMsg()
        }
    }

    // uploads over a slow mobile network need more than the default 10 s
    private val upload = http.newBuilder().writeTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    private suspend fun post(path: String, body: RequestBody, failText: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(base.trimEnd('/') + path).header("Authorization", token).post(body).build()
        val res = try { upload.newCall(req).execute() } catch (e: Exception) { throw ApiError(failText) }
        res.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError(errorText(r, text))
            text
        }
    }

    // ---------- 0.10 ----------

    /** Any file up to 100 MB; returns its name on the server. */
    suspend fun upload(body: RequestBody, ext: String): String =
        JSONObject(post("/upload?ext=" + URLEncoder.encode(ext, "UTF-8"), body, "Файл не загрузился, нет связи")).getString("name")

    suspend fun sendMedia(chatId: Int, kind: String, media: String, name: String = "", duration: Int = 0, wave: String = "", thumb: String = "",
                          caption: String = "", clientId: String = "", replyTo: Int = 0): Msg =
        JSONObject(call("POST", "/chats/$chatId/media", JSONObject().put("kind", kind).put("media", media).put("name", name)
            .put("duration", duration).put("wave", wave).put("thumb", thumb).put("caption", caption).put("client_id", clientId)
            .put("reply_to", replyTo))).toMsg()

    suspend fun search(chatId: Int, q: String): List<Msg> =
        JSONArray(call("GET", "/chats/$chatId/search?q=" + URLEncoder.encode(q, "UTF-8"))).let { a -> List(a.length()) { a.getJSONObject(it).toMsg() } }

    suspend fun pinMessage(chatId: Int, messageId: Int) { call("POST", "/chats/$chatId/pin-message", JSONObject().put("message_id", messageId)) }

    suspend fun groupTitle(chatId: Int, title: String): Chat = JSONObject(call("POST", "/chats/$chatId/title", JSONObject().put("title", title))).toChat()

    suspend fun groupAvatar(chatId: Int, jpeg: ByteArray): Chat =
        JSONObject(post("/chats/$chatId/avatar", jpeg.toRequestBody("image/jpeg".toMediaType()), "Фото не загрузилось, нет связи")).toChat()

    /** The newest app on the server: code 0 = nothing published. */
    suspend fun latest(): JSONObject = JSONObject(call("GET", "/app/latest"))

    /** 0.11.2: a new token for this phone, every other device is signed out. */
    suspend fun logoutAll(): String = JSONObject(call("POST", "/me/logout_all", JSONObject())).getString("token")

    // ---------- 0.11 ----------

    suspend fun login(username: String, password: String): Pair<String, User> {
        val o = JSONObject(call("POST", "/login", JSONObject().put("username", username).put("password", password)))
        return o.getString("token") to o.getJSONObject("user").toUser()
    }

    /** [old] only when a password is already set; [username] = change it in the same go (the first-time screen). */
    suspend fun setPassword(password: String, old: String = "", username: String? = null): User {
        val body = JSONObject().put("password", password).put("old", old)
        if (username != null) body.put("username", username)
        return JSONObject(call("POST", "/me/password", body)).toUser()
    }

    suspend fun setKent(userId: Int, kent: Boolean) { call("POST", "/friends/$userId", JSONObject().put("kent", kent)) }

    suspend fun spots(): List<Spot> = JSONArray(call("GET", "/spots")).let { a -> List(a.length()) { a.getJSONObject(it).toSpot() } }

    private fun spotBody(title: String, kind: String, lat: Double, lng: Double, about: String, photos: List<String>, vis: String) =
        JSONObject().put("title", title).put("kind", kind).put("lat", lat).put("lng", lng).put("about", about)
            .put("photos", JSONArray(photos)).put("vis", vis)

    /** id == 0 creates a new spot. */
    suspend fun saveSpot(id: Int, title: String, kind: String, lat: Double, lng: Double, about: String, photos: List<String>, vis: String): Spot {
        val body = spotBody(title, kind, lat, lng, about, photos, vis)
        return JSONObject(if (id == 0) call("POST", "/spots", body) else call("PUT", "/spots/$id", body)).toSpot()
    }

    suspend fun deleteSpot(id: Int) { call("DELETE", "/spots/$id") }

    /** How many kents got the message. */
    suspend fun callToSpot(id: Int, text: String): Int = JSONObject(call("POST", "/spots/$id/call", JSONObject().put("text", text))).getInt("sent")

    // ---------- 0.15 Beta ----------

    private suspend fun cardCall(method: String, path: String, body: JSONObject? = null): Card =
        JSONObject(call(method, path, body)).toCard()

    /** A new card posts a message; the socket echo with [clientId] replaces the pending one. */
    suspend fun createPoll(chatId: Int, question: String, options: List<String>, multi: Boolean, anon: Boolean, clientId: String): Msg =
        JSONObject(call("POST", "/chats/$chatId/poll", JSONObject().put("question", question).put("options", JSONArray(options))
            .put("multi", multi).put("anon", anon).put("client_id", clientId))).toMsg()

    suspend fun vote(pollId: Int, options: List<Int>): Card = cardCall("POST", "/polls/$pollId/vote", JSONObject().put("options", JSONArray(options)))
    suspend fun closePoll(pollId: Int): Card = cardCall("POST", "/polls/$pollId/close", JSONObject())

    suspend fun createGather(chatId: Int, title: String, at: Long, place: String, spotId: Int, note: String, clientId: String): Msg =
        JSONObject(call("POST", "/chats/$chatId/gather", JSONObject().put("title", title).put("at", at).put("place", place)
            .put("spot_id", spotId).put("note", note).put("client_id", clientId))).toMsg()

    suspend fun rsvp(gatherId: Int, status: String): Card = cardCall("POST", "/gathers/$gatherId/rsvp", JSONObject().put("status", status))
    suspend fun cancelGather(gatherId: Int): Card = cardCall("POST", "/gathers/$gatherId/cancel", JSONObject())

    suspend fun createSplit(chatId: Int, title: String, total: Int, userIds: List<Int>, details: String, clientId: String): Msg =
        JSONObject(call("POST", "/chats/$chatId/split", JSONObject().put("title", title).put("total", total).put("user_ids", JSONArray(userIds))
            .put("details", details).put("client_id", clientId))).toMsg()

    suspend fun splitPaid(splitId: Int, userId: Int, paid: Boolean): Card =
        cardCall("POST", "/splits/$splitId/paid", JSONObject().put("user_id", userId).put("paid", paid))

    suspend fun createWatch(chatId: Int, source: String, src: String, title: String, clientId: String): Msg =
        JSONObject(call("POST", "/chats/$chatId/watch", JSONObject().put("source", source).put("src", src).put("title", title)
            .put("client_id", clientId))).toMsg()

    suspend fun watch(watchId: Int): Card = cardCall("GET", "/watch/$watchId")
    suspend fun endWatch(watchId: Int) { call("POST", "/watch/$watchId/end", JSONObject()) }

    /** Big videos for «Смотреть вместе»: up to 1 GB. */
    suspend fun uploadBig(body: RequestBody, ext: String): String =
        JSONObject(post("/upload?big=1&ext=" + URLEncoder.encode(ext, "UTF-8"), body, "Видео не загрузилось, нет связи")).getString("name")

    /** «Я тут» on a spot; 0 = I left. */
    suspend fun checkin(spotId: Int) { call("POST", "/me/checkin", JSONObject().put("spot_id", spotId)) }

    /** The audio socket of a call: binary Opus packets both ways. */
    fun callAudio(callId: Int, listener: WebSocketListener): WebSocket =
        http.newWebSocket(Request.Builder().url(base.trimEnd('/').replaceFirst("http", "ws") + "/call?token=$token&call_id=$callId").build(), listener)

    /** Opens the live socket; onClosed fires once on any disconnect so the caller can retry. */
    fun connect(onEvent: (JSONObject) -> Unit, onOpen: () -> Unit, onClosed: () -> Unit): WebSocket {
        val url = base.trimEnd('/').replaceFirst("http", "ws") + "/ws?token=$token&v=" + URLEncoder.encode(version, "UTF-8")
        return http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = onEvent(JSONObject(text))
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onClosed()
        })
    }
}
