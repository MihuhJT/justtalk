package com.fayor.justtalk

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** This version's news; rewrite the list for every release. */
private val NEWS: List<Triple<ImageVector, String, String>> = listOf(
    Triple(Ic.Camera, "Приближение кружков", "Во время записи раздвинь два пальца на экране, чтобы приблизить камеру"),
    Triple(Ic.Chat, "Обновления", "Если закрыть окно установки, повторное нажатие откроет уже скачанное обновление"),
    Triple(Ic.Camera, "Кружки", "Раздвинь пальцы на видео, чтобы приблизить камеру. Кнопка справа отправляет запись"),
    Triple(Ic.Chat, "Каналы", "У читателей канала больше нет области для написания сообщения"),
    Triple(Ic.User, "Кенты", "В списке выбора только те, с кем уже была личная переписка"),
)

/** Full-screen "Что нового", shown once after updating; the logo pops, the lines slide in one by one. */
@Composable
fun WhatsNew(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val logo = remember { Animatable(0f) }
    val rows = remember { NEWS.map { Animatable(0f) } }
    LaunchedEffect(Unit) {
        launch { logo.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }
        delay(250)
        rows.forEach { a -> launch { a.animateTo(1f, tween(380)) }; delay(90) }
    }
    Box(
        Modifier.fillMaxSize().background(C.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(40.dp))
                Box(Modifier.size(104.dp).scale(logo.value).clip(RoundedCornerShape(30.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                    Monogram(Modifier.size(78.dp))
                }
                T("JustTalk Beta", size = 30, weight = FontWeight.Bold)
                T("Что нового в ${BuildConfig.VERSION_NAME}", size = 14, color = C.mut)
                Spacer(Modifier.height(6.dp))
                NEWS.forEachIndexed { i, (icon, title, text) ->
                    val p = rows[i].value
                    Row(
                        Modifier.fillMaxWidth().graphicsLayer { alpha = p; translationY = (1 - p) * 40f }
                            .clip(RoundedCornerShape(20.dp)).background(C.s1).padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(C.s2), contentAlignment = Alignment.Center) {
                            Icon(icon, null, Modifier.size(20.dp), tint = C.acc)
                        }
                        Column(Modifier.weight(1f)) {
                            T(title, weight = FontWeight.SemiBold)
                            T(text, size = 13, color = C.mut)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Box(
                Modifier.padding(bottom = 16.dp).fillMaxWidth().clip(CircleShape).background(C.acc).clickable(onClick = onClose).padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) { T("Погнали", weight = FontWeight.SemiBold, color = C.onAcc) }
        }
    }
}

/** The app icon's JT monogram (same 100-grid geometry as ic_launcher_fg_*), dot in the current accent. */
@Composable
fun Monogram(modifier: Modifier) {
    val dot = C.acc
    Canvas(modifier) {
        val k = size.minDimension / 100f
        // the drawing spans x 15..83, y 22..77: centre it
        translate(left = (50f - 49f) * k, top = (50f - 49.5f) * k) {
            drawRoundRect(Color.White, Offset(22f * k, 22f * k), Size(56f * k, 14f * k), CornerRadius(7f * k))
            val stem = Path().apply {
                moveTo(50f * k, 30f * k); lineTo(50f * k, 60f * k)
                arcTo(androidx.compose.ui.geometry.Rect(22f * k, 46f * k, 50f * k, 74f * k), 0f, 180f, false)
            }
            drawPath(stem, Color.White, style = Stroke(14f * k, cap = StrokeCap.Round))
            drawCircle(dot, 10f * k, Offset(73f * k, 67f * k))
        }
    }
}
