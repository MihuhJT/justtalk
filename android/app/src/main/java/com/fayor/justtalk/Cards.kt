package com.fayor.justtalk

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ---------- models ----------

/** A live card in a chat; the server sends its whole fresh state with every change. */
sealed interface Card { val id: Int; val ownerId: Int }

data class BotMessageCard(override val id: Int, override val ownerId: Int, val recipientId: Int, val recipientName: String, val recipientUsername: String, val text: String, val sent: Boolean) : Card

data class PollOption(val text: String, val votes: Int, val voters: List<Int>)
data class PollCard(
    override val id: Int, override val ownerId: Int, val question: String, val options: List<PollOption>,
    val multi: Boolean, val anon: Boolean, val closed: Boolean, val mine: List<Int>, val voters: Int,
) : Card

/** rsvp: who goes (yes), maybe and won't (no) */
data class GatherCard(
    override val id: Int, override val ownerId: Int, val title: String, val at: Long, val place: String, val spot: SpotRef?,
    val note: String, val cancelled: Boolean, val yes: List<Int>, val maybe: List<Int>, val no: List<Int>,
) : Card

data class Share(val userId: Int, val amount: Int, val paid: Boolean)
data class SplitCard(
    override val id: Int, override val ownerId: Int, val title: String, val total: Int, val details: String,
    val closed: Boolean, val shares: List<Share>,
) : Card

/** pos = seconds at the server moment [serverNow]; source = youtube / vk / file */
data class WatchCard(
    override val id: Int, override val ownerId: Int, val source: String, val src: String, val title: String,
    val ended: Boolean, val viewers: List<Int>, val playing: Boolean, val pos: Double, val serverNow: Double,
) : Card

/** status = active / ended / missed; inCall = talking now */
data class CallCard(
    override val id: Int, override val ownerId: Int, val chatId: Int, val status: String, val started: Long, val answered: Long,
    val duration: Long, val inCall: List<Int>, val muted: List<Int>, val people: List<Int>,
) : Card

/** The card's table row is gone (an old message after a cleanup). */
data class GoneCard(override val id: Int, val type: String) : Card { override val ownerId = 0 }

private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else List(length()) { getInt(it) }

fun JSONObject.toCard(): Card {
    val id = optInt("id")
    val owner = optInt("owner_id")
    if (optBoolean("gone")) return GoneCard(id, optString("type"))
    return when (optString("type")) {
        "poll" -> PollCard(
            id, owner, optString("question"),
            optJSONArray("options")?.let { a -> List(a.length()) { a.getJSONObject(it).let { o -> PollOption(o.optString("text"), o.optInt("votes"), o.optJSONArray("voters").ints()) } } }.orEmpty(),
            optBoolean("multi"), optBoolean("anon"), optBoolean("closed"), optJSONArray("mine").ints(), optInt("voters"),
        )
        "gather" -> {
            val r = optJSONObject("rsvp") ?: JSONObject()
            GatherCard(
                id, owner, optString("title"), optLong("at"), optString("place"),
                optJSONObject("spot")?.let { SpotRef(it.getInt("id"), it.getString("title"), it.optString("kind"), it.getDouble("lat"), it.getDouble("lng")) },
                optString("note"), optBoolean("cancelled"), r.optJSONArray("yes").ints(), r.optJSONArray("maybe").ints(), r.optJSONArray("no").ints(),
            )
        }
        "split" -> SplitCard(
            id, owner, optString("title"), optInt("total"), optString("details"), optBoolean("closed"),
            optJSONArray("shares")?.let { a -> List(a.length()) { a.getJSONObject(it).let { s -> Share(s.getInt("user_id"), s.optInt("amount"), s.optBoolean("paid")) } } }.orEmpty(),
        )
        "watch" -> WatchCard(
            id, owner, optString("source"), optString("src"), optString("title"), optBoolean("ended"), optJSONArray("viewers").ints(),
            optBoolean("playing"), optDouble("pos", 0.0), optDouble("server_now", 0.0),
        )
        "bot_message" -> BotMessageCard(id, owner, optInt("recipient_id"), optString("recipient_name"), optString("recipient_username"), optString("text"), optBoolean("sent"))
        "call" -> CallCard(
            id, owner, optInt("chat_id"), optString("status"), optLong("started"), optLong("answered"), optLong("duration"),
            optJSONArray("in").ints(), optJSONArray("muted").ints(), optJSONArray("people").ints(),
        )
        else -> GoneCard(id, optString("type"))
    }
}

/** One line for the chat list, notifications and quotes. */
fun Card.label(): String = when (this) {
    is PollCard -> "📊 $question"
    is GatherCard -> "📅 $title"
    is SplitCard -> "💸 $title · ${rub(total)}"
    is WatchCard -> "🎬 " + title.ifBlank { sourceLabel(source) }
    is CallCard -> when (status) { "active" -> "📞 Идёт звонок"; "missed" -> "📞 Пропущенный звонок"; else -> "📞 Звонок ${durationLabel((duration * 1000).toInt())}" }
    is BotMessageCard -> "Сообщение для $recipientName"
    is GoneCard -> "Карточка удалена"
}

fun sourceLabel(source: String) = when (source) { "youtube" -> "YouTube"; "vk" -> "VK Видео"; else -> "Видео" }

/** "1 500 ₽" */
fun rub(n: Int): String = String.format(Locale.ROOT, "%,d", n).replace(',', ' ') + " ₽"

private val RU = Locale.forLanguageTag("ru")

/** "сб, 26 окт · 18:00" */
fun gatherWhen(at: Long): String {
    val d = Date(at * 1000)
    val today = Calendar.getInstance()
    val c = Calendar.getInstance().apply { time = d }
    val sameDay = { a: Calendar, b: Calendar -> a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) }
    val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
    val day = when {
        sameDay(c, today) -> "сегодня"
        sameDay(c, tomorrow) -> "завтра"
        else -> SimpleDateFormat("EE, d MMM", RU).format(d).replace(".", "")
    }
    return day + " · " + SimpleDateFormat("HH:mm", Locale.ROOT).format(d)
}

/** "через 2 ч", "через 15 мин", "уже идёт" */
fun untilLabel(at: Long, now: Long): String {
    val left = at - now
    return when {
        left <= -3 * 3600 -> "прошёл"
        left <= 0 -> "уже идёт"
        left < 3600 -> "через ${maxOf(1, left / 60)} мин"
        left < 86_400 -> "через ${left / 3600} ч"
        else -> "через ${left / 86_400} дн"
    }
}

// ---------- in a bubble ----------

/** The card inside a message bubble; [ink]/[accent] follow the bubble colours. */
@Composable
fun CardBody(store: Store, m: Msg, card: Card, ink: Color, accent: Color) {
    when (card) {
        is PollCard -> PollBody(store, card, ink, accent)
        is GatherCard -> GatherBody(store, card, ink, accent)
        is SplitCard -> SplitBody(store, card, ink, accent)
        is WatchCard -> WatchBody(store, m, card, ink, accent)
        is CallCard -> CallBody(store, card, ink, accent)
        is BotMessageCard -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            T("${card.recipientName} · @${card.recipientUsername}", weight = FontWeight.SemiBold, color = ink)
            T(card.text, color = ink)
            if (card.sent) T("Отправлено", size = 13, color = accent)
            else if (card.ownerId == store.me?.id) T("Отправить", color = C.onAcc,
                modifier = Modifier.clip(CircleShape).background(C.acc).clickable { store.sendBotDraft(card.id) }.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        is GoneCard -> T("Карточка удалена", size = 13, color = ink.copy(alpha = .6f))
    }
}

@Composable
private fun CardHead(icon: String, label: String, ink: Color) {
    T("$icon  $label".uppercase(), size = 11, weight = FontWeight.SemiBold, color = ink.copy(alpha = .6f), modifier = Modifier.padding(bottom = 4.dp))
}

/** Small overlapping faces, then "+3". */
@Composable
fun Faces(store: Store, ids: List<Int>, size: Dp = 22.dp, max: Int = 5) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ids.take(max).forEachIndexed { i, id ->
            val u = store.user(id)
            Box(Modifier.offset(x = (-6 * i).dp).clip(CircleShape).background(C.bg).padding(1.5.dp)) {
                Avatar(u?.name ?: store.userName(id), size, false, photo = u?.photo())
            }
        }
        if (ids.size > max) T("+${ids.size - max}", size = 11, color = C.mut, modifier = Modifier.offset(x = (-6 * max + 4).dp))
    }
}

@Composable
private fun SmallAction(text: String, color: Color, onClick: () -> Unit) {
    T(text, size = 12, weight = FontWeight.Medium, color = color, modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp))
}

@Composable
private fun PollBody(store: Store, p: PollCard, ink: Color, accent: Color) {
    val meId = store.me?.id
    val showResults = p.mine.isNotEmpty() || p.closed
    val totalVotes = p.options.sumOf { it.votes }.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CardHead("📊", buildString {
            append(if (p.anon) "Анонимный опрос" else "Опрос")
            if (p.multi) append(" · несколько")
            if (p.closed) append(" · закрыт")
        }, ink)
        T(p.question, size = 16, weight = FontWeight.SemiBold, color = ink)
        p.options.forEachIndexed { i, o ->
            val mine = i in p.mine
            val share by animateFloatAsState(if (showResults) o.votes.toFloat() / totalVotes else 0f, tween(350), label = "poll")
            Box(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(14.dp)).background(ink.copy(alpha = .07f))
                    .then(if (mine) Modifier.border(1.5.dp, accent, RoundedCornerShape(14.dp)) else Modifier)
                    .clickable(enabled = !p.closed) {
                        val next = when {
                            p.multi -> if (mine) p.mine - i else p.mine + i
                            mine -> emptyList()
                            else -> listOf(i)
                        }
                        store.vote(p, next)
                    },
            ) {
                Box(Modifier.matchParentSize()) { Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(accent.copy(alpha = .28f))) }
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mine) Icon(Ic.Check, null, Modifier.size(15.dp), tint = accent)
                    T(o.text, color = ink, modifier = Modifier.weight(1f))
                    if (showResults) {
                        if (!p.anon && o.voters.isNotEmpty()) Faces(store, o.voters, 18.dp, 3)
                        T("${o.votes * 100 / totalVotes}%", size = 12, weight = FontWeight.SemiBold, color = ink.copy(alpha = .75f))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(votesLabel(p.voters) + if (!showResults) " · голосуй, чтобы увидеть" else "", size = 12, color = ink.copy(alpha = .6f), modifier = Modifier.weight(1f))
            if (p.ownerId == meId && !p.closed) SmallAction("Закрыть", accent) { store.closePoll(p) }
        }
    }
}

private fun votesLabel(n: Int) = when {
    n == 0 -> "Пока никто не голосовал"
    n % 10 == 1 && n % 100 != 11 -> "$n голос"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "$n голоса"
    else -> "$n голосов"
}

@Composable
private fun GatherBody(store: Store, g: GatherCard, ink: Color, accent: Color) {
    val meId = store.me?.id
    val now by produceState(System.currentTimeMillis() / 1000) { while (true) { delay(30_000); value = System.currentTimeMillis() / 1000 } }
    val my = when (meId) { in g.yes -> "yes"; in g.maybe -> "maybe"; in g.no -> "no"; else -> "" }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CardHead("📅", if (g.cancelled) "Сбор отменён" else "Сбор · ${untilLabel(g.at, now)}", ink)
        T(g.title, size = 17, weight = FontWeight.Bold, color = if (g.cancelled) ink.copy(alpha = .5f) else ink)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Ic.Clock, null, Modifier.size(15.dp), tint = accent)
            T(gatherWhen(g.at), size = 14, color = ink)
        }
        g.spot?.let { ref -> SpotCard(ref, ink, accent) { store.showSpot(ref) } }
        if (g.place.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Ic.Map, null, Modifier.size(15.dp), tint = accent)
            T(g.place, size = 14, color = ink)
        }
        if (g.note.isNotBlank()) T(g.note, size = 13, color = ink.copy(alpha = .75f))
        if (!g.cancelled) Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Triple("yes", "Иду", g.yes.size), Triple("maybe", "Может", g.maybe.size), Triple("no", "Не иду", g.no.size)).forEach { (id, label, n) ->
                val on = my == id
                val bg by animateColorAsState(if (on) accent else ink.copy(alpha = .08f), tween(200), label = "rsvp")
                Box(
                    Modifier.weight(1f).clip(CircleShape).background(bg).clickable { store.rsvp(g, if (on) "" else id) }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    T(if (n > 0) "$label · $n" else label, size = 13, weight = FontWeight.SemiBold,
                        color = if (on) (if (accent == C.acc) C.onAcc else C.acc) else ink, maxLines = 1)
                }
            }
        }
        if (g.yes.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Faces(store, g.yes)
            T(g.yes.joinToString { store.userName(it) }, size = 12, color = ink.copy(alpha = .65f), maxLines = 1)
        }
        if (g.ownerId == meId && !g.cancelled && g.at > now) SmallAction("Отменить сбор", C.red) { store.cancelGather(g) }
    }
}

@Composable
private fun SplitBody(store: Store, s: SplitCard, ink: Color, accent: Color) {
    val meId = store.me?.id
    val ctx = LocalContext.current
    val paid = s.shares.filter { it.paid }.sumOf { it.amount }
    val progress by animateFloatAsState(if (s.total > 0) paid.toFloat() / s.total else 0f, tween(350), label = "split")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CardHead("💸", if (s.closed) "Скинулись ✓" else "Скидываемся", ink)
        T(s.title, size = 16, weight = FontWeight.SemiBold, color = ink)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            T(rub(s.total), size = 24, weight = FontWeight.Bold, color = ink)
            s.shares.firstOrNull()?.let { T("по ${rub(s.shares.minOf { it.amount })}", size = 13, color = ink.copy(alpha = .65f), modifier = Modifier.padding(bottom = 4.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(ink.copy(alpha = .1f))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(CircleShape).background(if (s.closed) C.green else accent))
        }
        T("Собрано ${rub(paid)} из ${rub(s.total)} · кидать ${store.userName(s.ownerId)}", size = 12, color = ink.copy(alpha = .65f))
        if (s.details.isNotBlank()) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ink.copy(alpha = .07f)).clickable {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Реквизиты", s.details))
                store.toast = "Скопировано"
            }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(s.details, size = 13, color = ink, modifier = Modifier.weight(1f), maxLines = 2)
            T("копировать", size = 11, color = accent)
        }
        s.shares.forEach { sh ->
            val canTick = sh.userId == meId || s.ownerId == meId
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = canTick) { store.splitPaid(s, sh.userId, !sh.paid) }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val u = store.user(sh.userId)
                Avatar(u?.name ?: store.userName(sh.userId), 24.dp, false, photo = u?.photo())
                T(if (sh.userId == meId) "Я" else store.userName(sh.userId), size = 14, color = ink, modifier = Modifier.weight(1f), maxLines = 1)
                T(rub(sh.amount), size = 13, color = ink.copy(alpha = .75f))
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(if (sh.paid) C.green else Color.Transparent)
                        .border(1.5.dp, if (sh.paid) C.green else ink.copy(alpha = .35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (sh.paid) Icon(Ic.Check, null, Modifier.size(13.dp), tint = Color.White) }
            }
        }
        val mine = s.shares.firstOrNull { it.userId == meId }
        if (mine != null && !mine.paid) Box(
            Modifier.fillMaxWidth().clip(CircleShape).background(accent).clickable { store.splitPaid(s, mine.userId, true) }.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) { T("Я скинул ${rub(mine.amount)}", size = 14, weight = FontWeight.SemiBold, color = if (accent == C.acc) C.onAcc else C.acc) }
    }
}

/** YouTube preview picture; nothing for VK and own videos. */
fun watchThumb(w: WatchCard): String? = if (w.source == "youtube") "https://img.youtube.com/vi/${w.src}/hqdefault.jpg" else null

@Composable
private fun WatchBody(store: Store, m: Msg, w: WatchCard, ink: Color, accent: Color) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CardHead("🎬", if (w.ended) "Посмотрели" else "Смотрим вместе · ${sourceLabel(w.source)}", ink)
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black)
                .clickable(enabled = !w.ended) { store.openWatch(m.chatId, w.id) },
            contentAlignment = Alignment.Center,
        ) {
            watchThumb(w)?.let { src -> rememberPhoto(src, 480)?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) } }
            if (!w.ended) Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
                Icon(Ic.Play, "Смотреть", Modifier.size(24.dp), tint = Color.White)
            }
        }
        if (w.title.isNotBlank()) T(w.title, size = 15, weight = FontWeight.SemiBold, color = ink, maxLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (w.viewers.isNotEmpty()) Faces(store, w.viewers)
            T(if (w.viewers.isEmpty()) (if (w.ended) "Сеанс закончен" else "Пока никто не смотрит") else "смотрят: ${w.viewers.size}",
                size = 12, color = ink.copy(alpha = .65f), modifier = Modifier.weight(1f))
            if (!w.ended) SmallAction("Подключиться", accent) { store.openWatch(m.chatId, w.id) }
        }
    }
}

@Composable
private fun CallBody(store: Store, c: CallCard, ink: Color, accent: Color) {
    val meId = store.me?.id
    val mine = c.ownerId == meId
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val active = c.status == "active"
        val missed = c.status == "missed"
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(when { active -> C.green; missed && !mine -> C.red; else -> ink.copy(alpha = .1f) }),
            contentAlignment = Alignment.Center,
        ) { Icon(Ic.Phone, null, Modifier.size(18.dp), tint = if (active || (missed && !mine)) Color.White else ink) }
        Column(Modifier.weight(1f, fill = false)) {
            T(when {
                active -> "Идёт звонок"
                missed && mine -> "Без ответа"
                missed -> "Пропущенный звонок"
                else -> "Звонок"
            }, weight = FontWeight.SemiBold, color = ink)
            T(when {
                active -> "в звонке: ${c.inCall.size}"
                missed -> if (mine) "Исходящий" else "Входящий"
                else -> durationLabel((c.duration * 1000).toInt()) + " · " + (if (mine) "исходящий" else "входящий")
            }, size = 12, color = ink.copy(alpha = .65f))
        }
        when {
            active && meId !in c.inCall && Calls.callId != c.id ->
                Box(Modifier.clip(CircleShape).background(C.green).clickable { store.joinCall(c.id, c.chatId) }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                    T("Войти", size = 13, weight = FontWeight.SemiBold, color = Color.White)
                }
            !active -> Box(Modifier.size(34.dp).clip(CircleShape).clickable { store.startCall(c.chatId) }, contentAlignment = Alignment.Center) {
                Icon(Ic.Phone, "Перезвонить", Modifier.size(17.dp), tint = accent)
            }
        }
    }
}

// ---------- creating ----------

/** Shared frame of the "new card" sheets: title, scrollable fields, the main button at the bottom. */
@Composable
private fun CardSheet(title: String, onClose: () -> Unit, button: String, enabled: Boolean, onDone: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 640.dp).clip(RoundedCornerShape(26.dp)).background(C.s1).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(title, size = 19, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(34.dp).clip(CircleShape).background(C.s2).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Ic.Close, "Закрыть", Modifier.size(16.dp), tint = C.mut)
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
            Cta(button, enabled, onDone)
        }
    }
}

@Composable
private fun SheetField(label: String, value: String, onChange: (String) -> Unit, hint: String, keyboard: KeyboardOptions = KeyboardOptions.Default) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.s2).padding(horizontal = 14.dp, vertical = 8.dp)) {
        T(label, size = 11, color = C.mut)
        Field(value, onChange, hint, Modifier.fillMaxWidth(), keyboard)
    }
}

@Composable
private fun SwitchRow(label: String, hint: String, on: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.s2).clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { T(label, size = 14); T(hint, size = 11, color = C.mut) }
        Toggle(on)
    }
}

@Composable
private fun Pill(text: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).background(if (on) C.acc else C.s2).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 8.dp)) {
        T(text, size = 13, weight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) C.onAcc else C.ink, maxLines = 1)
    }
}

@Composable
fun NewPollSheet(store: Store, chatId: Int, onClose: () -> Unit) {
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(listOf("", "")) }
    var multi by remember { mutableStateOf(false) }
    var anon by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val filled = options.map { it.trim() }.filter { it.isNotEmpty() }
    CardSheet("Опрос", onClose, if (busy) "Создаю…" else "Запустить опрос", question.isNotBlank() && filled.size >= 2 && !busy, {
        busy = true
        store.createPoll(chatId, question.trim(), filled, multi, anon) { ok -> busy = false; if (ok) onClose() }
    }) {
        SheetField("Вопрос", question, { question = it.take(200) }, "Куда идём в субботу?")
        options.forEachIndexed { i, o ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f)) {
                    SheetField("Вариант ${i + 1}", o, { v -> options = options.toMutableList().also { it[i] = v.take(100) } }, if (i == 0) "Парк" else if (i == 1) "Ко мне" else "")
                }
                if (options.size > 2) Box(Modifier.size(34.dp).clip(CircleShape).clickable { options = options.filterIndexed { j, _ -> j != i } }, contentAlignment = Alignment.Center) {
                    Icon(Ic.Close, "Убрать", Modifier.size(14.dp), tint = C.mut)
                }
            }
        }
        if (options.size < 10) T("+ Добавить вариант", size = 14, color = C.acc, weight = FontWeight.Medium,
            modifier = Modifier.clip(CircleShape).clickable { options = options + "" }.padding(8.dp))
        SwitchRow("Несколько ответов", "Можно выбрать больше одного", multi) { multi = !multi }
        SwitchRow("Анонимный", "Никто не увидит, кто за что", anon) { anon = !anon }
    }
}

@Composable
fun NewGatherSheet(store: Store, chatId: Int, onClose: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var dayShift by remember { mutableStateOf(0) }
    var time by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var spot by remember { mutableStateOf<Spot?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val digits = time.filter(Char::isDigit)
    val hh = digits.take(2).toIntOrNull()
    val mm = digits.drop(2).take(2).toIntOrNull()
    val at = if (digits.length == 4 && hh != null && mm != null && hh < 24 && mm < 60) Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, dayShift); set(Calendar.HOUR_OF_DAY, hh); set(Calendar.MINUTE, mm); set(Calendar.SECOND, 0)
    }.timeInMillis / 1000 else 0L
    val past = at in 1 until System.currentTimeMillis() / 1000 - 60
    if (choosing) Dialog(onDismissRequest = { choosing = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).clip(RoundedCornerShape(22.dp)).background(C.s1).padding(8.dp)) {
            T("Спот для сбора", weight = FontWeight.SemiBold, modifier = Modifier.padding(10.dp))
            if (store.spots.isEmpty()) T("Спотов пока нет: добавь на карте", size = 13, color = C.mut, modifier = Modifier.padding(10.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                store.spots.forEach { s ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { spot = s; choosing = false }.padding(10.dp)) {
                        T(s.title, weight = FontWeight.Medium, maxLines = 1)
                        T(kindLabel(s.kind), size = 12, color = C.mut)
                    }
                }
            }
        }
    }
    CardSheet("Сбор", onClose, if (busy) "Создаю…" else "Собрать кентов", title.isNotBlank() && at > 0 && !past && !busy, {
        busy = true
        store.createGather(chatId, title.trim(), at, place.trim(), spot?.id ?: 0, note.trim()) { ok -> busy = false; if (ok) onClose() }
    }) {
        SheetField("Что за сбор", title, { title = it.take(80) }, "Катка в парке")
        T("Когда", size = 12, color = C.mut)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0 until 14).forEach { d ->
                val label = when (d) {
                    0 -> "Сегодня"; 1 -> "Завтра"
                    else -> SimpleDateFormat("EE d", RU).format(Date(System.currentTimeMillis() + d * 86_400_000L)).replace(".", "")
                }
                Pill(label, d == dayShift) { dayShift = d }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(110.dp)) {
                SheetField("Время", withColon(digits), { v -> time = v.filter(Char::isDigit).take(4) }, "18:00", KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1600", "1800", "1900", "2000", "2200").forEach { t -> Pill(withColon(t), digits == t) { time = t } }
            }
        }
        if (past) T("Это время уже прошло", size = 12, color = C.red)
        T("Где", size = 12, color = C.mut)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.s2).clickable { if (spot != null) spot = null else choosing = true }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Ic.Map, null, Modifier.size(18.dp), tint = C.acc)
            T(spot?.title ?: "Выбрать спот с карты", color = if (spot == null) C.acc else C.ink, modifier = Modifier.weight(1f), maxLines = 1)
            if (spot != null) Icon(Ic.Close, "Убрать спот", Modifier.size(14.dp), tint = C.mut)
        }
        SheetField("Или адрес / как найти", place, { place = it.take(120) }, "У фонтана")
        SheetField("Заметка", note, { note = it.take(300) }, "Берите колонку")
        T("За час до начала в чат придёт напоминалка", size = 12, color = C.mut)
    }
}

private fun withColon(d: String) = if (d.length > 2) d.take(2) + ":" + d.drop(2) else d

@Composable
fun NewSplitSheet(store: Store, chatId: Int, onClose: () -> Unit) {
    val chat = store.chats.firstOrNull { it.id == chatId } ?: return
    val meId = store.me?.id ?: return
    var title by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var people by remember { mutableStateOf(chat.members.map { it.id }.toSet()) }
    var details by remember { mutableStateOf(store.lastSplitDetails) }
    var busy by remember { mutableStateOf(false) }
    val sum = total.filter(Char::isDigit).take(8).toIntOrNull() ?: 0
    val n = people.size
    val ok = title.isNotBlank() && sum > 0 && n >= 1 && people != setOf(meId) && !busy
    CardSheet("Скидываемся", onClose, if (busy) "Создаю…" else "Собрать деньги", ok, {
        busy = true
        store.createSplit(chatId, title.trim(), sum, chat.members.map { it.id }.filter { it in people }, details.trim()) { done -> busy = false; if (done) onClose() }
    }) {
        SheetField("На что", title, { title = it.take(80) }, "Пицца и газировка")
        SheetField("Сумма, ₽", total, { total = it.filter(Char::isDigit).take(8) }, "3000", KeyboardOptions(keyboardType = KeyboardType.Number))
        if (sum > 0 && n > 0) T("По ${rub(sum / n)}${if (sum % n != 0) " (кому-то на рубль больше)" else ""} · $n чел.", size = 13, color = C.acc)
        T("Кто скидывается", size = 12, color = C.mut)
        chat.members.forEach { u ->
            val on = u.id in people
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { people = if (on) people - u.id else people + u.id }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(u.name, 32.dp, false, photo = u.photo())
                T(if (u.id == meId) "${u.name} (я, уже заплатил)" else u.name, modifier = Modifier.weight(1f), maxLines = 1)
                Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) C.acc else Color.Transparent).border(1.5.dp, if (on) C.acc else C.mut, CircleShape),
                    contentAlignment = Alignment.Center) { if (on) Icon(Ic.Check, null, Modifier.size(13.dp), tint = C.onAcc) }
            }
        }
        SheetField("Куда кидать", details, { details = it.take(200) }, "+7 900 000-00-00, Сбер")
    }
}

/** A YouTube or VK link -> (source, src for the server), or null. */
fun parseVideoLink(link: String): Pair<String, String>? {
    val s = link.trim()
    Regex("""(?:youtu\.be/|youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|live/|embed/))([A-Za-z0-9_-]{11})""").find(s)?.let { return "youtube" to it.groupValues[1] }
    Regex("""video_ext\.php\?(?=.*oid=(-?\d+))(?=.*[?&]id=(\d+))(?:.*hash=([0-9a-f]+))?""").find(s)?.let { m ->
        val (oid, id, hash) = m.destructured
        return "vk" to listOf(oid, id, hash).filter { it.isNotEmpty() }.joinToString("_")
    }
    Regex("""(?:vk\.com|vkvideo\.ru|vk\.ru)/.*?video(-?\d+)_(\d+)""").find(s)?.let { m ->
        val hash = Regex("""[?&]hash=([0-9a-f]+)""").find(s)?.groupValues?.get(1).orEmpty()
        return "vk" to listOf(m.groupValues[1], m.groupValues[2], hash).filter { it.isNotEmpty() }.joinToString("_")
    }
    return null
}

@Composable
fun NewWatchSheet(store: Store, chatId: Int, onClose: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    val parsed = parseVideoLink(link)
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            busy = true; progress = 0f
            store.createWatchFromFile(chatId, uri, title.trim(), { p -> progress = p }) { ok -> busy = false; progress = null; if (ok) onClose() }
        }
    }
    CardSheet("Смотреть вместе", onClose, when { progress != null -> "Загружаю видео… ${((progress ?: 0f) * 100).toInt()}%"; busy -> "Создаю…"; else -> "Позвать смотреть" },
        parsed != null && !busy, {
            val (source, src) = parsed ?: return@CardSheet
            busy = true
            store.createWatch(chatId, source, src, title.trim()) { ok -> busy = false; if (ok) onClose() }
        }) {
        T("Все в чате смотрят синхронно: пауза и перемотка у одного — у всех.", size = 13, color = C.mut)
        SheetField("Ссылка на YouTube или VK Видео", link, { link = it.take(500) }, "https://youtu.be/…", KeyboardOptions(keyboardType = KeyboardType.Uri))
        if (link.isNotBlank()) T(parsed?.let { "✓ ${sourceLabel(it.first)}" } ?: "Не понял ссылку: нужна ссылка на видео YouTube или VK", size = 12,
            color = if (parsed != null) C.green else C.red)
        if (parsed?.first == "youtube") T("YouTube без VPN может тормозить", size = 11, color = C.mut)
        SheetField("Название (необязательно)", title, { title = it.take(120) }, "Фильм на вечер")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(1.dp).background(C.line)); T("или", size = 12, color = C.mut); Box(Modifier.weight(1f).height(1.dp).background(C.line))
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.s2).clickable(enabled = !busy) {
                pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Ic.Camera, null, Modifier.size(20.dp), tint = C.acc)
            Column(Modifier.weight(1f)) {
                T("Видео с телефона", weight = FontWeight.Medium)
                T("До 1 ГБ, загрузится на сервер JT", size = 11, color = C.mut)
            }
        }
        progress?.let { p ->
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(C.s2)) {
                Box(Modifier.fillMaxWidth(p).fillMaxHeight().clip(CircleShape).background(C.acc))
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
