package com.fayor.justtalk

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// A normal app screen; preferences belong to the current account on the server.
data object BotSettings : Screen

@Composable
fun BotAvatar(size: Dp = 38.dp) {
    val pixels = listOf("   pppp   ", "  pppppp  ", " pwwppwwp ", " ppbppbpp ", "pppppppppp", "p p pp p p", "p p    p p")
    Box(Modifier.size(size).clip(CircleShape).background(C.s1).border(1.5.dp, C.acc, CircleShape)
        .semantics { contentDescription = "JT бот" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(size * .2f)) {
            val unit = this.size.width / 10
            pixels.forEachIndexed { y, row -> row.forEachIndexed { x, pixel ->
                val color = when (pixel) { 'p' -> Color(0xFFFF7BBF); 'w' -> Color.White; 'b' -> Color(0xFF242027); else -> Color.Transparent }
                if (pixel != ' ') drawRect(color, Offset(x * unit, y * unit + unit), Size(unit, unit))
            } }
        }
    }
}

@Composable
fun BotThinking() {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BotAvatar(26.dp)
        T("JT бот думает…", size = 13, color = C.mut)
    }
}

@Composable
fun BotReply(store: Store, m: Msg, modifier: Modifier = Modifier) {
    val meta = m.bot ?: return
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.s1)
        .border(1.dp, C.acc.copy(alpha = .5f), RoundedCornerShape(22.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotAvatar(28.dp)
            T("JT бот", weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            T(if (store.chats.firstOrNull { it.id == m.chatId }?.kind == "bot") "ответ для тебя" else "ответ для всех", size = 11, color = C.mut)
        }
        if (m.card != null || store.chats.firstOrNull { it.id == m.chatId }?.kind != "bot") T("По просьбе ${meta.requestedName}", size = 12, color = C.mut)
        if (m.card != null) CardBody(store, m, m.card, C.ink, C.acc)
        else T(m.text, size = 15)
        if (meta.targetChatId > 0 && meta.targetChatId != m.chatId) {
            T("Открыть чат с карточкой", size = 13, color = C.acc, modifier = Modifier.clip(CircleShape).clickable { store.openChat(meta.targetChatId) }.padding(8.dp))
        }
        val split = m.card as? SplitCard
        if (split != null && split.ownerId == store.me?.id && !split.closed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                T("Поровну", size = 12, color = C.acc, modifier = Modifier.clip(CircleShape).background(C.s2).clickable { store.botEqual(split.id) }.padding(10.dp))
                T("Напомнить завтра", size = 12, color = C.acc, modifier = Modifier.clip(CircleShape).background(C.s2).clickable { store.botRemind(split.id) }.padding(10.dp))
            }
        }
        if (meta.error && meta.requestedBy == store.me?.id && meta.requestId > 0) {
            T("Повторить", size = 13, color = C.acc, modifier = Modifier.clip(CircleShape)
                .clickable(enabled = store.botBusy[m.chatId] != true) { store.retryBot(meta.requestId) }.padding(8.dp))
        }
    }
}

@Composable
fun BotChatScreen(store: Store, chatId: Int) {
    BackHandler { store.screen = Screen.Chats }
    val messages = store.messages[chatId].orEmpty()
    val state = rememberLazyListState()
    var text by remember(chatId) { mutableStateOf("") }
    val send = { if (text.isNotBlank()) { store.send(chatId, text); text = "" } }
    LaunchedEffect(chatId) { store.loadBotSettings(); store.checkBotState(chatId) }
    LaunchedEffect(messages.lastOrNull()?.id, store.botBusy[chatId]) { if (messages.isNotEmpty()) state.animateScrollToItem(0) }
    Column(Modifier.fillMaxSize().background(C.bg).windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { store.screen = Screen.Chats }, contentAlignment = Alignment.Center) { Icon(Ic.Back, "Назад", tint = C.ink) }
            BotAvatar()
            Column(Modifier.weight(1f)) {
                T("JT бот", weight = FontWeight.Bold)
                T(if (store.botBusy[chatId] == true) "Думает…" else "Qwen · на сервере JT", size = 12, color = C.mut)
            }
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { store.botSettings() }, contentAlignment = Alignment.Center) { Icon(Ic.Edit, "Характер бота", Modifier.size(20.dp), tint = C.ink) }
        }
        if (messages.isEmpty()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Spacer(Modifier.height(16.dp)); BotAvatar(84.dp)
                T("Йо, я JT бот", size = 24, weight = FontWeight.Bold)
                T("Общаюсь и помогаю советом. Здесь можно писать без @бот.", size = 14, color = C.mut)
                val prompts = listOf("Итог разговора" to "Подведи итог последних сообщений", "Где покатать" to "Какие споты доступны?",
                    "Идеи" to "Предложи идеи для встречи", "Помоги" to "Помоги составить план")
                prompts.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { (label, prompt) ->
                            Box(Modifier.weight(1f).height(86.dp).clip(RoundedCornerShape(20.dp)).background(C.s1).clickable { text = prompt }.padding(14.dp)) {
                                T(label, weight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterStart))
                            }
                        }
                    }
                }
            }
        } else LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = state, reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Spacer(Modifier.height(4.dp)) }
            if (store.botBusy[chatId] == true) item { BotThinking() }
            items(messages.asReversed(), key = { it.clientId ?: it.id }) { m ->
                Bubble(store, m, m.senderId == store.me?.id, true, 0, Modifier)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clip(CircleShape).background(C.s1).border(1.dp, C.acc, CircleShape).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Field(text, { text = it.take(4000) }, "Спроси бота…", Modifier.fillMaxWidth(),
                    androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send), onSend = send)
            }
            RoundButton(Ic.Send, "Отправить", size = 44.dp, bg = C.acc, tint = C.onAcc) { send() }
        }
    }

}

@Composable
fun BotSettingsScreen(store: Store) {
    val back = { store.screen = store.botSettingsBack }
    BackHandler(onBack = back)
    LaunchedEffect(Unit) { store.loadBotSettings() }
    Column(Modifier.fillMaxSize().background(C.bg).windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header("Характер JT бота", back)
        T("Твоя личная настройка. В группе бот отвечает в стиле того, кто его позвал.", size = 14, color = C.mut)
        listOf("friendly" to "Свой в компании", "calm" to "Спокойный", "brief" to "Коротко", "detailed" to "Подробно").forEach { (id, title) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.s1).clickable { store.saveBotSettings(style = id) }.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                T(title, modifier = Modifier.weight(1f))
                if (store.botStyle == id) Icon(Ic.Check, "Выбрано", Modifier.size(20.dp), tint = C.acc)
            }
        }
        T("В переписках с людьми позови через @бот. Для ответа учитываются до 20 предыдущих сообщений этого же чата.", size = 13, color = C.mut)
    }
}

@Composable
fun BotMentionText(text: String, ink: Color, accent: Color, modifier: Modifier) {
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        Regex("(?<![\\p{L}\\p{N}_@])@бот(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            addStyle(androidx.compose.ui.text.SpanStyle(color = accent, fontWeight = FontWeight.SemiBold), match.range.first, match.range.last + 1)
        }
    }
    androidx.compose.material3.Text(annotated, modifier, style = base.copy(color = ink))
}
