package cn.com.toobug.youxue.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 优学配色。
 *
 * 主色取「书卷蓝」，沉稳不刺眼，长时间看书看屏幕都不累；
 * 辅色用暖橙做强调（按钮、进度），在冷色底上跳得出来又不抢戏。
 * 功能色遵循国内习惯：涨/正向用红，跌/负向用绿——这里暂时用不到，
 * 但语义色定义好，后续加图表功能可以直接复用。
 */

// ---- 主色（书卷蓝）----
val BluePrimary = Color(0xFF3D6BFF)
val BluePrimaryDark = Color(0xFF2A4FC4)
val BlueContainer = Color(0xFFE3EAFF)
val BlueOnContainer = Color(0xFF11307F)

// ---- 辅色（暖橙，用于强调与进度）----
val OrangeAccent = Color(0xFFFF8A3D)
val OrangeContainer = Color(0xFFFFEBDC)
val OrangeOnContainer = Color(0xFF7A3B00)

// ---- 语义色 ----
val SuccessRed = Color(0xFFD93F3F)      // 国内习惯：正向/涨用红
val SuccessContainer = Color(0xFFFFE4E4)
val WarningGreen = Color(0xFF1F9D55)    // 负向/跌用绿
val WarningContainer = Color(0xFFDFF5E7)
val ErrorColor = Color(0xFFD93F3F)

// ---- 中性色（浅色主题）----
val LightBackground = Color(0xFFF7F8FC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF0F2F8)
val LightOnBackground = Color(0xFF1A1C22)
val LightOnSurfaceVariant = Color(0xFF5A6072)
val LightOutline = Color(0xFFDCE0EB)

// ---- 中性色（深色主题）----
val DarkBackground = Color(0xFF12141A)
val DarkSurface = Color(0xFF1C1F27)
val DarkSurfaceVariant = Color(0xFF262A34)
val DarkOnBackground = Color(0xFFECEEF4)
val DarkOnSurfaceVariant = Color(0xFFA8AFC0)
val DarkOutline = Color(0xFF3A3F4C)

val BluePrimaryLight = Color(0xFF9DB6FF)
val BlueContainerDark = Color(0xFF243A80)
val OrangeAccentLight = Color(0xFFFFB27A)
