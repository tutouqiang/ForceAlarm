package com.example.forcealarm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = AlarmOrange,
    onPrimary = Color.White,
    secondary = InfoBlue,
    onSecondary = Color.White,
    background = LightBg,
    onBackground = TextPrimary,
    surface = LightCard,
    onSurface = TextPrimary,
    error = Bad,
    onError = Color.White
)

@Composable
fun ForceAlarmTheme(
    // 首页浅色；闹钟界面是纯黑自己画的背景，不受主题控制，不受影响
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(colorScheme = LightColorScheme, typography = Typography, content = content)
}
