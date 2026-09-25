package com.youxue.app.util

/**
 * 时长格式化工具。
 *
 * 听写场景下用得比通用日期格式化多得多，单独抽出来避免各处自己拼字符串。
 */

/** 把毫秒格式化为 `mm:ss`，超过一小时用 `h:mm:ss` */
fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** 把毫秒格式化为带单位的中文描述，例如 `2 分 13 秒` */
fun formatDurationChinese(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return buildString {
        if (hours > 0) append("$hours 时 ")
        if (minutes > 0 || hours > 0) append("$minutes 分 ")
        append("$seconds 秒")
    }
}

/** 把秒数格式化为一位小数，用于显示间隔设置 */
fun formatSeconds(seconds: Float): String =
    if (seconds == seconds.toInt().toFloat()) {
        seconds.toInt().toString()
    } else {
        "%.1f".format(seconds)
    }
