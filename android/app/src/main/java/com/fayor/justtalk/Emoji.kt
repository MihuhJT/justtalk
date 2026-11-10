package com.fayor.justtalk

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator

/** A reaction is one visible emoji, including joined families, skin tones and flags. */
fun isSingleEmoji(value: String): Boolean {
    if (value.isBlank() || value.length > 16) return false
    val it = BreakIterator.getCharacterInstance()
    it.setText(value)
    if (it.first() != 0 || it.next() != value.length || it.next() != BreakIterator.DONE) return false
    var hasEmoji = false
    var pos = 0
    while (pos < value.length) {
        val cp = value.codePointAt(pos)
        if (cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code || cp in '0'.code..'9'.code) return false
        if (UCharacter.hasBinaryProperty(cp, UProperty.EMOJI)) hasEmoji = true
        pos += Character.charCount(cp)
    }
    return hasEmoji
}

/** Ignore ordinary keyboard text and keep a single emoji from a pasted string. */
fun emojiInput(value: String): String {
    val it = BreakIterator.getCharacterInstance()
    it.setText(value)
    var start = it.first()
    var end = it.next()
    var chosen = ""
    while (end != BreakIterator.DONE) {
        val item = value.substring(start, end)
        if (isSingleEmoji(item)) chosen = item
        start = end
        end = it.next()
    }
    return chosen
}
