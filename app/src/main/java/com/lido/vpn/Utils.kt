package com.lido.vpn

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

class CoreCallbackStub : libv2ray.CoreCallbackHandler {
    override fun onEmitStatus(status: Long, message: String?): Long = 0
    override fun shutdown(): Long = 0
    override fun startup(): Long = 0
}

fun extractFlag(s: String): String? {
    val flagRegex = Regex("[\uD83C\uDDE6-\uD83C\uDDFF]{2}")
    return flagRegex.find(s)?.value
}

fun codeToFlag(code: String): String {
    if (code.length != 2) return ""
    return code.uppercase().map { char ->
        Character.codePointAt(char.toString(), 0) - 0x41 + 0x1F1E6
    }.joinToString("") { String(Character.toChars(it)) }
}

fun flagToCode(flag: String): String? {
    if (flag.length < 4) return null // Emoji surrogate pairs
    try {
        val cp1 = Character.codePointAt(flag, 0)
        val cp2 = Character.codePointAt(flag, 2)
        val char1 = (cp1 - 0x1F1E6 + 0x41).toChar()
        val char2 = (cp2 - 0x1F1E6 + 0x41).toChar()
        return "$char1$char2"
    } catch (_: Exception) { return null }
}

fun getCountryName(code: String, isRu: Boolean): String {
    val locale = java.util.Locale(if (isRu) "ru" else "en", code.uppercase())
    val name = locale.getDisplayCountry(java.util.Locale(if (isRu) "ru" else "en"))
    
    // Fallback/Custom overrides for common VPN locations
    return when(code.uppercase()) {
        "US" -> if (isRu) "США" else "USA"
        "GB" -> if (isRu) "Великобритания" else "United Kingdom"
        "HK" -> if (isRu) "Гонконг" else "Hong Kong"
        "AE" -> if (isRu) "ОАЭ" else "UAE"
        else -> name.ifEmpty { code }
    }
}

@Composable
fun Modifier.tvFocusable(
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium,
    extraPadding: androidx.compose.ui.unit.Dp = 0.dp,
    forceFocus: Boolean = true
): Modifier {
    var isFocused by remember { mutableStateOf(false) }
    
    return this
        .onFocusChanged { isFocused = it.isFocused }
        .padding(extraPadding)
        .border(
            width = if (isFocused) 3.dp else 0.dp,
            color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape = shape
        )
        .graphicsLayer {
            scaleX = if (isFocused) 1.05f else 1f
            scaleY = if (isFocused) 1.05f else 1f
            clip = false
        }
        .then(if (forceFocus) Modifier.focusable() else Modifier)
}

@Composable
fun Modifier.tutorialHighlight(enabled: Boolean): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "tutorial")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    return if (enabled) {
        this.then(
            Modifier
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), MaterialTheme.shapes.medium)
        )
    } else this
}
