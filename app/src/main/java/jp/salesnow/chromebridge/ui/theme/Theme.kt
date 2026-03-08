// Version: 1.0.0 | Updated: 2026-03-08
// [2026-03-08] SalesNow ブランドカラーを Compose テーマに移植
package jp.salesnow.chromebridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// SalesNow ブランドカラー
val Teal = Color(0xFF2A9BA1)        // Primary Main
val TealDark = Color(0xFF27878A)    // Primary Dark
val TealLight = Color(0xFF94CDD0)   // Primary Light
val NavyDark = Color(0xFF283C50)    // Secondary Main
val NavyDeep = Color(0xFF182F46)    // Secondary Dark
val GrayLight = Color(0xFF939DA7)   // Secondary Light
val ErrorRed = Color(0xFFE74C3C)

private val ColorScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = TealLight,
    secondary = NavyDark,
    onSecondary = Color.White,
    background = Color(0xFFF5F5F5),
    surface = Color.White,
    error = ErrorRed,
    outline = GrayLight,
)

@Composable
fun ChromeBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        content = content
    )
}
