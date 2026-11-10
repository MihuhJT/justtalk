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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// 0.11 account screens; Screen is sealed, so they live in this package
data object SetPassword : Screen
data object ChangePassword : Screen
data object Kents : Screen

private val hidden = PasswordVisualTransformation()
private val pwKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password)

/**
 * One time after the update (or right after signup): @username + password, so the account survives a new phone.
 * Chats stay as they are. There is no way around it: back leaves the app.
 */
@Composable
fun SetPasswordScreen(store: Store) {
    val me = store.me
    var username by remember { mutableStateOf(me?.username ?: "") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    // "checking", "free", "taken" or "offline", like on the profile editor
    var check by remember { mutableStateOf("free") }
    val un = username.trim().lowercase().removePrefix("@")
    val bad = un != "jt" && !Regex("^[a-z0-9_]{3,20}$").matches(un)
    LaunchedEffect(un) {
        if (bad || un == me?.username) { check = "free"; return@LaunchedEffect }
        check = "checking"
        delay(300)
        check = when (store.usernameFree(un)) { true -> "free"; false -> "taken"; null -> "offline" }
    }
    val ok = !bad && check != "taken" && check != "checking" && pw.length >= 6 && pw == pw2 && !busy
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        T("Защити аккаунт", size = 28, weight = FontWeight.Bold)
        T("Придумай пароль. С ним ты войдёшь с нового телефона или после переустановки, переписки никуда не денутся.", color = C.mut)
        Spacer(Modifier.height(8.dp))
        LabeledInput("Юзернейм · по нему входишь", username, { username = it.take(21); error = "" }, "Твой юзернейм",
            KeyboardOptions(keyboardType = KeyboardType.Ascii))
        when {
            bad -> T("3–20 символов: латиница, цифры, _", size = 11, color = C.red)
            check == "checking" -> T("Проверяем @$un…", size = 11, color = C.mut)
            check == "taken" -> T("@$un уже занят", size = 11, color = C.red)
            check == "offline" -> T("Нет связи, проверим при сохранении", size = 11, color = C.mut)
            else -> T("Потом его можно поменять в профиле", size = 11, color = C.mut)
        }
        LabeledInput("Пароль · минимум 6 символов", pw, { pw = it.take(100); error = "" }, "••••••", pwKeyboard, hidden)
        LabeledInput("Ещё раз", pw2, { pw2 = it.take(100); error = "" }, "••••••", pwKeyboard, hidden)
        if (pw2.isNotEmpty() && pw != pw2) T("Пароли не совпадают", size = 11, color = C.red)
        if (error.isNotEmpty()) T(error, size = 13, color = C.red)
        Spacer(Modifier.height(6.dp))
        Cta(if (busy) "Сохраняем…" else "Сохранить", enabled = ok) {
            busy = true
            store.setPassword(pw, username = un) { err -> busy = false; if (err != null) { error = err; if (err.contains("занят")) check = "taken" } }
        }
    }
}

@Composable
fun ChangePasswordScreen(store: Store) {
    BackHandler { store.screen = Screen.Profile }
    var old by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val ok = old.isNotEmpty() && pw.length >= 6 && pw == pw2 && !busy
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header("Сменить пароль") { store.screen = Screen.Profile }
        LabeledInput("Сейчас", old, { old = it.take(100); error = "" }, "••••••", pwKeyboard, hidden)
        LabeledInput("Новый · минимум 6 символов", pw, { pw = it.take(100); error = "" }, "••••••", pwKeyboard, hidden)
        LabeledInput("Новый ещё раз", pw2, { pw2 = it.take(100); error = "" }, "••••••", pwKeyboard, hidden)
        if (pw2.isNotEmpty() && pw != pw2) T("Пароли не совпадают", size = 11, color = C.red)
        if (error.isNotEmpty()) T(error, size = 13, color = C.red)
        Spacer(Modifier.weight(1f))
        Box(Modifier.padding(bottom = 16.dp)) {
            Cta(if (busy) "Сохраняем…" else "Сохранить", enabled = ok) {
                busy = true
                store.setPassword(pw, old = old) { err -> busy = false; if (err != null) error = err }
            }
        }
    }
}

/** Everyone I share a chat with: tap to switch between кент and просто знакомый. */
@Composable
fun KentsScreen(store: Store) {
    BackHandler { store.screen = Screen.Profile }
    val people = store.users.filter { !it.removed && it.corresponded }
        .sortedWith(compareByDescending<User> { it.kent }.thenBy { it.name.lowercase() })
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 20.dp)) {
        Header("Кенты") { store.screen = Screen.Profile }
        T("Кенты видят твои споты «для кентов» и получают «Позвать на спот». Знакомые — нет. Человек не узнает, кем ты его отметил.",
            size = 12, color = C.mut, modifier = Modifier.padding(vertical = 8.dp))
        if (people.isEmpty()) T("Пока не с кем: напиши кому-нибудь в чатах", size = 13, color = C.mut, modifier = Modifier.padding(vertical = 24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(people, key = { it.id }) { u ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { store.setKent(u, !u.kent) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(u.name, 42.dp, store.avatarRing, photo = u.photo())
                    Column(Modifier.weight(1f)) {
                        T(u.name, weight = FontWeight.SemiBold, maxLines = 1)
                        Handle(u)
                    }
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (u.kent) C.acc else C.s1).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        T(if (u.kent) "Кент" else "Знакомый", size = 12, weight = FontWeight.SemiBold, color = if (u.kent) C.onAcc else C.mut)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
