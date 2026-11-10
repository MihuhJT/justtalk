package com.fayor.justtalk

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

val REACTIONS = listOf("👍", "🔥", "😂", "❤️", "😮", "😢")

/** Short copy of a quoted message that the server sends along with a reply. */
data class Quote(val id: Int, val senderId: Int, val text: String, val deleted: Boolean)

fun JSONObject.toQuote() = Quote(getInt("id"), getInt("sender_id"), getString("text"), optBoolean("deleted"))

/** Per-chat state of the message actions: open menu, reply/edit in progress, highlighted message. */
class MsgActions(private val list: LazyListState, private val scope: CoroutineScope) {
    var menu by mutableStateOf<Msg?>(null)
    var reply by mutableStateOf<Msg?>(null)
    var edit by mutableStateOf<Msg?>(null)
    var flash by mutableStateOf(0)
    /** message being forwarded: the chat picker is open */
    var forward by mutableStateOf<Msg?>(null)

    /** Send button: saves an edit or sends a new message (maybe as a reply). */
    fun submit(store: Store, chatId: Int, text: String) {
        val e = edit
        if (e != null) {
            store.editMessage(e, text)
            edit = null
        } else {
            store.send(chatId, text, reply?.id ?: 0)
            reply = null
        }
    }

    /** Scrolls to the quoted message and blinks it. */
    fun jumpTo(store: Store, chatId: Int, id: Int) {
        val msgs = store.messages[chatId].orEmpty()
        val i = msgs.indexOfFirst { it.id == id }
        if (i < 0) return
        scope.launch {
            // the chat list is reversed (newest = item 1, after the bottom spacer)
            list.animateScrollToItem(msgs.size - i)
            flash = id
            delay(1200)
            if (flash == id) flash = 0
        }
    }
}

@Composable
fun rememberMsgActions(chatId: Int, list: LazyListState): MsgActions {
    val scope = rememberCoroutineScope()
    return remember(chatId) { MsgActions(list, scope) }
}


@Composable
private fun Txt(text: String, size: Int = 15, weight: FontWeight = FontWeight.Normal, color: Color = C.ink, maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier) =
    Text(text, modifier, style = base.copy(fontSize = size.sp, fontWeight = weight, color = color), maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/**
 * Long press opens the menu; swipe left = reply, like in Telegram (swipe right closes the chat).
 * Pending messages (id < 0) only get the menu.
 */
fun Modifier.msgGestures(acts: MsgActions?, m: Msg, store: Store): Modifier = if (acts == null) this else composed {
    val haptic = LocalHapticFeedback.current
    val shift = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val max = with(LocalDensity.current) { 64.dp.toPx() }
    val msg by rememberUpdatedState(m)
    this
        .offset { IntOffset(shift.value.roundToInt(), 0) }
        .pointerInput(store.quickReaction, msg.id) {
            detectTapGestures(onDoubleTap = { if (msg.id > 0) store.react(msg, store.quickReaction) }, onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                acts.menu = msg
            })
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // a right swipe is left alone: the chat screen takes it as "back"
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over -> if (over < 0) change.consume() }
                    ?: return@awaitEachGesture
                val ended = horizontalDrag(drag.id) { change ->
                    val x = (shift.value + change.positionChange().x).coerceIn(-max, 0f)
                    scope.launch { shift.snapTo(x) }
                    change.consume()
                }
                if (ended && shift.value <= -max * .8f && msg.id > 0) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    acts.edit = null
                    acts.reply = msg
                }
                scope.launch { shift.animateTo(0f) }
            }
        }
}

/** Quote plate at the top of a reply bubble; tap scrolls to the original. */
@Composable
fun QuoteInBubble(store: Store, acts: MsgActions?, m: Msg, ink: Color, accent: Color) {
    if (m.replyTo == 0) return
    // the loaded original is fresher (edits), the server copy covers old or deleted ones
    val orig = store.messages[m.chatId].orEmpty().firstOrNull { it.id == m.replyTo }
    val q = orig?.let { Quote(it.id, it.senderId, it.preview(), false) } ?: m.quote ?: Quote(m.replyTo, 0, "", true)
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp).height(IntrinsicSize.Min).clip(RoundedCornerShape(10.dp))
            .background(ink.copy(alpha = .08f))
            .clickable(enabled = acts != null && orig != null) { acts?.jumpTo(store, m.chatId, q.id) }
            .padding(end = 10.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
            if (!q.deleted) Txt(store.userName(q.senderId), size = 12, weight = FontWeight.SemiBold, color = accent, maxLines = 1)
            Txt(if (q.deleted) "Сообщение удалено" else q.text, size = 13, color = ink.copy(alpha = .75f), maxLines = 1)
        }
    }
}

/** Strip above the input: "Ответ Роме" or "Редактирование", with a cross to cancel. */
@Composable
fun ComposeBar(store: Store, acts: MsgActions, onCancelEdit: () -> Unit) {
    val editing = acts.edit
    val m = editing ?: acts.reply ?: return
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp).height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(18.dp)).background(C.s1).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(vertical = 8.dp).width(3.dp).fillMaxHeight().clip(CircleShape).background(C.acc))
        Column(Modifier.weight(1f).padding(start = 10.dp, top = 8.dp, bottom = 8.dp)) {
            Txt(if (editing != null) "Редактирование" else "Ответ: ${store.userName(m.senderId)}", size = 12, weight = FontWeight.SemiBold, color = C.acc, maxLines = 1)
            Txt(m.preview(), size = 13, color = C.mut, maxLines = 1)
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).clickable {
                acts.reply = null
                if (editing != null) { acts.edit = null; onCancelEdit() }
            },
            contentAlignment = Alignment.Center,
        ) { Txt("✕", size = 16, color = C.mut) }
    }
}

/** Long-press menu: the message on top, then pill buttons. */
@Composable
fun MsgMenu(store: Store, acts: MsgActions, onEdit: (String) -> Unit) {
    val m = acts.menu ?: return
    val ctx = LocalContext.current
    val mine = m.senderId == store.me?.id
    val sent = m.id > 0
    var confirm by remember(m.id) { mutableStateOf(false) }
    var customEmoji by remember(m.id) { mutableStateOf("") }
    val close = { acts.menu = null }
    Dialog(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // one tap puts a reaction (or takes mine back)
            if (sent) Row(Modifier.fillMaxWidth().clip(CircleShape).background(C.s2).padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                val mineNow = m.reactions.entries.firstOrNull { store.me?.id in it.value }?.key
                REACTIONS.forEach { e ->
                    Box(Modifier.size(44.dp).clip(CircleShape).background(if (e == mineNow) C.acc.copy(alpha = .3f) else Color.Transparent)
                        .clickable { store.react(m, e); close() }, contentAlignment = Alignment.Center) { Txt(e, size = 22) }
                }
            }
            if (sent) Row(Modifier.fillMaxWidth().clip(CircleShape).background(C.s1).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Field(customEmoji, { customEmoji = emojiInput(it) }, "Любой эмодзи", Modifier.weight(1f))
                if (customEmoji.isNotBlank()) Box(Modifier.size(42.dp).clip(CircleShape).clickable {
                    store.react(m, customEmoji.trim()); close()
                }, contentAlignment = Alignment.Center) { Txt("✓", color = C.acc) }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.s2).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (!mine) Txt(store.userName(m.senderId), size = 12, weight = FontWeight.SemiBold, color = C.acc)
                Txt(m.preview(), maxLines = 6)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.s1).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (sent && store.chats.firstOrNull { it.id == m.chatId }?.let { it.kind != "channel" || it.ownerId == store.me?.id } == true) MenuPill(if (store.chats.firstOrNull { it.id == m.chatId }?.pinnedMessage == m.id) "Открепить сообщение" else "Закрепить сообщение") {
                    store.pinMessage(m.chatId, if (store.chats.firstOrNull { it.id == m.chatId }?.pinnedMessage == m.id) 0 else m.id); close()
                }
                if (sent) MenuPill("Ответить") { acts.edit = null; acts.reply = m; close() }
                if (sent) MenuPill("Переслать") { acts.forward = m; close() }
                if (m.text.isNotBlank()) MenuPill("Копировать") {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("JustTalk", m.text))
                    close()
                }
                if (mine && sent) MenuPill("Изменить") { acts.reply = null; acts.edit = m; onEdit(m.text); close() }
                // only own messages can be deleted, and then for everyone; a second tap confirms
                if (mine && sent) MenuPill(if (confirm) "Удалить у всех? Нажми ещё раз" else "Удалить", C.red) {
                    if (confirm) { store.deleteMessage(m); close() } else confirm = true
                }
            }
        }
    }
}

@Composable
internal fun MenuPill(text: String, color: Color = C.ink, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(CircleShape).background(C.s2).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp),
    ) { Txt(text, color = color, weight = FontWeight.Medium) }
}

/** Small "изменено" mark next to the time. */
@Composable
fun EditedMark(m: Msg, color: Color) {
    if (m.edited) Txt("изменено", size = 10, color = color)
}
