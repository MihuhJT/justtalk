package com.fayor.justtalk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Current colors. They are Compose state, so picking a theme or accent in
 * Профиль → Оформление redraws the whole app. Defaults: "Чёрная" + "Фиолетовый".
 */
object C {
    var bg by mutableStateOf(Color(0xFF000000))
    var s1 by mutableStateOf(Color(0xFF131315))
    var s2 by mutableStateOf(Color(0xFF1F1F22))
    var ink by mutableStateOf(Color(0xFFF4F4F6))
    var mut by mutableStateOf(Color(0xFF8B8B93))
    var line by mutableStateOf(Color(0x12FFFFFF))
    var acc by mutableStateOf(Color(0xFF7366F0))
    var onAcc by mutableStateOf(Color(0xFFFFFFFF))
    var glass by mutableStateOf(Color(0xB31C1C20))
    var glassBorder by mutableStateOf(Color(0x1FFFFFFF))
    val green = Color(0xFF34C46A)
    val red = Color(0xFFFF6B6B)
    var light by mutableStateOf(false)

    /** Applies a palette id (black/graphite/light) and an accent id (see ACCENTS). */
    fun apply(palette: String, accent: String) {
        val p = PALETTES[palette] ?: PALETTES.getValue("black")
        bg = p[0]; s1 = p[1]; s2 = p[2]; ink = p[3]; mut = p[4]; line = p[5]; glass = p[6]; glassBorder = p[7]
        light = palette == "light"
        val a = ACCENTS.firstOrNull { it.id == accent } ?: ACCENTS[0]
        // black "mono" accent would vanish on white, so it flips there
        acc = if (accent == "mono" && light) Color(0xFF111114) else a.color
        onAcc = if (accent == "mono" && light) Color.White else a.on
    }
}

// bg, s1, s2, ink, mut, line, glass, glassBorder
val PALETTES = mapOf(
    "black" to listOf(0xFF000000, 0xFF131315, 0xFF1F1F22, 0xFFF4F4F6, 0xFF8B8B93, 0x12FFFFFF, 0xB31C1C20, 0x1FFFFFFF).map { Color(it) },
    "graphite" to listOf(0xFF17171A, 0xFF222226, 0xFF2D2D32, 0xFFF2F2F4, 0xFF95959E, 0x12FFFFFF, 0xB328282E, 0x1FFFFFFF).map { Color(it) },
    "light" to listOf(0xFFF5F5F7, 0xFFFFFFFF, 0xFFECECF0, 0xFF111114, 0xFF74747C, 0x0F000000, 0xD9FFFFFF, 0x14000000).map { Color(it) },
)

class Accent(val id: String, val label: String, val color: Color, val on: Color)

val ACCENTS = listOf(
    Accent("violet", "Фиолетовый", Color(0xFF7366F0), Color.White),
    Accent("lime", "Лайм", Color(0xFFC8F26B), Color(0xFF111111)),
    Accent("orange", "Оранжевый", Color(0xFFFF7A3D), Color.White),
    Accent("blue", "Синий", Color(0xFF4D7CFF), Color.White),
    Accent("mono", "Без цвета", Color(0xFFF2F2F4), Color(0xFF111111)),
)

// Onest ships as one variable font; each weight picks a point on its wght axis
private fun onest(w: Int) = Font(R.font.onest, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Onest = FontFamily(onest(400), onest(500), onest(600), onest(700))

/** Icons from the mockup's SVG paths (round caps); tinted by Icon(). */
private fun icon(vararg paths: String, filled: Boolean = false, stroke: Float = 1.8f, w: Float = 24f, h: Float = 24f): ImageVector =
    ImageVector.Builder(defaultWidth = w.dp, defaultHeight = h.dp, viewportWidth = w, viewportHeight = h)
        .apply {
            paths.forEach {
                addPath(
                    pathData = PathParser().parsePathString(it).toNodes(),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = if (filled) null else SolidColor(Color.Black),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

object Ic {
    val Flash = icon("M13 2L4 13h7l-1 9 10-13h-7l1-7z", filled = true)
    val Rotate = icon("M20 11a8 8 0 1 1-3-6", "M20 4v7h-7", stroke = 2f)
    val Expand = icon("M9 4H4v5M15 4h5v5M20 15v5h-5M9 20H4v-5", stroke = 2f)
    val Chat = icon("M20 11.5a8 8 0 0 1-11.7 7.1L4 20l1.4-4A8 8 0 1 1 20 11.5z")
    val Map = icon("M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z", "M9.5 9.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0z")
    val Music = icon("M9 18V5l11-2v13", "M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", "M14 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z")
    val User = icon("M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z", "M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6")
    val Plus = icon("M12 5v14M5 12h14", stroke = 2f)
    val Search = icon("M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0z", "M20 20l-3.5-3.5", stroke = 2f)
    val Back = icon("M15 5l-7 7 7 7", stroke = 2.2f)
    val Send = icon("M12 19V5M6 11l6-6 6 6", stroke = 2.2f)
    val Tick1 = icon("M1.5 5.8l3 3L11 2.2", stroke = 1.7f, w = 16f, h = 11f)
    val Tick2 = icon("M1.5 5.8l3 3L11 2.2", "M7.4 8.4l.4.4L14.4 2.2", stroke = 1.7f, w = 16f, h = 11f)
    val Photo = icon("M4 7.5A2.5 2.5 0 0 1 6.5 5h11A2.5 2.5 0 0 1 20 7.5v9a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 16.5z", "M4.5 16l4.5-4.5 3.5 3.5 2.5-2.5 4.5 4.5", "M14.5 9.5a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0 -2.4 0z")
    // profile
    val Edit = icon("M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16v4z")
    val Bell = icon("M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15zM10 21h4", stroke = 2f)
    val Lock = icon("M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M8 11V8a4 4 0 0 1 8 0v3", stroke = 2f)
    val Star = icon("M12 3l2.7 5.6 6.1.9-4.4 4.3 1 6.1L12 17l-5.4 2.9 1-6.1L3.2 9.5l6.1-.9z", stroke = 2f)
    val Check = icon("M5 12.5l4.5 4.5L19 7", stroke = 2.2f)
    // 0.10
    val Mic = icon("M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0z", "M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21", stroke = 2f)
    val Camera = icon("M4 8.5A2.5 2.5 0 0 1 6.5 6h7A2.5 2.5 0 0 1 16 8.5v7a2.5 2.5 0 0 1-2.5 2.5h-7A2.5 2.5 0 0 1 4 15.5z", "M16 10.5l4-2.5v8l-4-2.5", stroke = 2f)
    val Clip = icon("M20 11.5l-8 8a5 5 0 0 1-7-7l8.5-8.5a3.3 3.3 0 0 1 4.7 4.7L9.7 17.2a1.7 1.7 0 0 1-2.4-2.4L15 7")
    val File = icon("M7 3h7l5 5v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M14 3v5h5", stroke = 2f)
    val Pin = icon("M15 4l5 5-3 1-4 4 .5 4.5L12 20l-8-8 1.5-1.5L10 11l4-4z", "M4 20l4.5-4.5", stroke = 2f)
    val Forward = icon("M14 5l6 6-6 6", "M20 11H10a6 6 0 0 0-6 6v2", stroke = 2f)
    val Down = icon("M5 9l7 7 7-7", stroke = 2.2f)
    val Play = icon("M8 5.5v13l11-6.5z", filled = true)
    val Pause = icon("M7 5h3.5v14H7zM13.5 5H17v14h-3.5z", filled = true)
    val Flip = icon("M4 12a8 8 0 0 1 14-5.3M20 12a8 8 0 0 1-14 5.3", "M18 3v4h-4M6 21v-4h4", stroke = 2f)
    val Close = icon("M6 6l12 12M18 6L6 18", stroke = 2.2f)
    val Up = icon("M5 15l7-7 7 7", stroke = 2.2f)
    // 0.15
    val Phone = icon("M5 4h3.5l1.8 4.5-2.3 1.4a11 11 0 0 0 6.1 6.1l1.4-2.3L20 15.5V19a1.5 1.5 0 0 1-1.6 1.5A16.5 16.5 0 0 1 3.5 5.6 1.5 1.5 0 0 1 5 4z", stroke = 2f)
    val PhoneDown = icon("M1.5 13.8c5.8-5.4 15.2-5.4 21 0l-1.7 3.6-4.9-1.1-.8-3a11.5 11.5 0 0 0-6.2 0l-.8 3-4.9 1.1z", filled = true)
    val MicOff = icon("M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0z", "M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21", "M4 4l16 16", stroke = 2f)
    val Speaker = icon("M4 9.5h3.5L12 5.5v13l-4.5-4H4z", "M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11", stroke = 2f)
    val Calendar = icon("M5 6.5h14a1.5 1.5 0 0 1 1.5 1.5v11a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 19V8A1.5 1.5 0 0 1 5 6.5z", "M3.5 11h17M8 4v4M16 4v4", stroke = 2f)
    val Poll = icon("M5 20V12M12 20V5M19 20v-5", stroke = 2.4f)
    val Money = icon("M3.5 7h17v10h-17z", "M9.5 12a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0z", "M6.5 10v4M17.5 10v4", stroke = 2f)
    val Tv = icon("M4 6.5h16a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 17V8A1.5 1.5 0 0 1 4 6.5z", "M9.5 10v5l4.5-2.5z", "M8 21h8", stroke = 2f)
    val Clock = icon("M3 8a5 5 0 1 0 10 0a5 5 0 1 0 -10 0z", "M8 5.5V8l1.8 1.2", stroke = 1.5f, w = 16f, h = 16f)
}
