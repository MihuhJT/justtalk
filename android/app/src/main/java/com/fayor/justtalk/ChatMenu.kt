package com.fayor.justtalk

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** @username; the creator's @JT is bold, in the accent color and glowing. */
@Composable
fun Handle(u: User, size: Int = 13, modifier: Modifier = Modifier) {
    if (u.isOwner) {
        Text(
            u.handle(), modifier,
            style = base.copy(fontSize = size.sp, fontWeight = FontWeight.ExtraBold, color = C.acc,
                shadow = Shadow(C.acc.copy(alpha = .8f), Offset.Zero, 14f)),
        )
    } else T(u.handle(), size = size, color = C.mut, modifier = modifier)
}

/** Long press on a chat in the list. Destructive actions ask for a second tap. */
@Composable
fun ChatMenu(store: Store, chat: Chat, onClose: () -> Unit) {
    val other = if (chat.kind == "dm") chat.members.firstOrNull { it.id != store.me?.id } else null
    var confirm by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf(false) }
    var alias by remember { mutableStateOf(chat.alias) }
    // a destructive pill: first tap arms it, second runs it
    @Composable
    fun Danger(key: String, label: String, ask: String, run: () -> Unit) =
        MenuPill(if (confirm == key) ask else label, C.red) { if (confirm == key) { run(); onClose() } else confirm = key }

    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.s2).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Avatar(chat.title, 40.dp, store.avatarRing, photo = chat.photo(store.me?.id))
                Column(Modifier.weight(1f)) {
                    T(chat.title, weight = FontWeight.SemiBold, maxLines = 1)
                    if (other != null) Handle(other) else T("Группа", size = 13, color = C.mut)
                }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.s1).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (renaming) {
                    // only I see this name; empty brings back the real one
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.s2).padding(horizontal = 16.dp, vertical = 13.dp)) {
                        Field(alias, { alias = it.take(60) }, "Своё имя для «${chat.title}»")
                    }
                    MenuPill("Сохранить") { store.renameChat(chat.id, alias); onClose() }
                    if (chat.alias.isNotEmpty()) MenuPill("Вернуть настоящее имя") { store.renameChat(chat.id, ""); onClose() }
                    return@Column
                }
                MenuPill(if (chat.pinned > 0) "Открепить" else "Закрепить") { store.setPinned(chat.id, chat.pinned == 0L); onClose() }
                MenuPill("Переименовать") { renaming = true }
                MenuPill(if (chat.muted) "Включить уведомления" else "Выключить уведомления") { store.setMuted(chat.id, !chat.muted); onClose() }
                if (other != null) {
                    if (store.isBlocked(other.id)) MenuPill("Разблокировать") { store.setBlocked(other, false); onClose() }
                    else Danger("block", "Заблокировать", "Заблокировать? Нажми ещё раз") { store.setBlocked(other, true) }
                }
                Danger("clear", "Удалить переписку", "Удалить у себя? Нажми ещё раз") { store.clearChat(chat.id) }
                if (other != null) Danger("remove", "Удалить кента :(", "Точно удалить? Нажми ещё раз") { store.removeKent(other, chat.id) }
            }
        }
    }
}

/** Personal name for a chat; empty brings back the real one. */
@Composable
fun RenameDialog(store: Store, chat: Chat, onClose: () -> Unit) {
    var alias by remember { mutableStateOf(chat.alias) }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.s1).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            T("Как он будет у тебя называться", size = 13, color = C.mut, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.s2).padding(horizontal = 16.dp, vertical = 13.dp)) {
                Field(alias, { alias = it.take(60) }, chat.members.firstOrNull { it.id != store.me?.id }?.name ?: chat.title)
            }
            MenuPill("Сохранить") { store.renameChat(chat.id, alias); onClose() }
            if (chat.alias.isNotEmpty()) MenuPill("Вернуть настоящее имя") { store.renameChat(chat.id, ""); onClose() }
        }
    }
}

/** Someone's profile: avatar, name, @username, about. */
@Composable
fun PersonScreen(store: Store, userId: Int, back: Screen) {
    BackHandler { store.screen = back }
    val u = store.user(userId)
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TopBar("") { store.screen = back }
        if (u == null) {
            T("Кент не найден", color = C.mut, modifier = Modifier.padding(top = 40.dp))
            return@Column
        }
        val dm = store.dmWith(u.id)
        var renaming by remember { mutableStateOf(false) }
        if (renaming && dm != null) RenameDialog(store, dm) { renaming = false }
        Avatar(u.name, 92.dp, store.avatarRing, photo = u.photo())
        T(u.name, size = 20, weight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        // my own name for him, from "Переименовать"
        if (dm != null && dm.alias.isNotEmpty()) T("у тебя: ${dm.alias}", size = 13, color = C.mut, modifier = Modifier.padding(top = 2.dp))
        Handle(u, size = 14, modifier = Modifier.padding(top = 4.dp))
        Text(
            u.about.ifBlank { "Пусто" },
            Modifier.padding(top = 8.dp).widthIn(max = 260.dp),
            style = base.copy(fontSize = 13.sp, color = C.mut, textAlign = TextAlign.Center),
        )
        PersonFacts(u)
        Spacer(Modifier.height(24.dp))
        if (u.id != store.me?.id) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Cta("Написать") { store.openDm(u) }
                // кент sees my spots for kents and gets «Позвать на спот»; знакомый doesn't
                Segs(listOf("kent" to "Кент", "no" to "Просто знакомый"), if (store.isKent(u.id)) "kent" else "no") { store.setKent(u, it == "kent") }
                if (dm != null) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { renaming = true }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center) { T("Переименовать", color = C.acc) }
                val blocked = store.isBlocked(u.id)
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { store.setBlocked(u, !blocked) }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center) {
                    T(if (blocked) "Разблокировать" else "Заблокировать", color = if (blocked) C.acc else C.red)
                }
            }
        }
    }
}
