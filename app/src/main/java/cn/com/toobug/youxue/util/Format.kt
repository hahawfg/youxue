package cn.com.toobug.youxue.util

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

/**
 * 把毫秒时刻格式化为「M月d日 HH:mm」。
 *
 * 历史列表要回答的是「什么时候练的」，所以日期与时刻都要；
 * 但年份不必显示——听写记录只看最近几十条，跨年的概率极低，
 * 而多一个「2026年」只会让每行更长、更难扫。
 *
 * 用 [java.util.Calendar] 而不是 `java.time`：minSdk 24，
 * `java.time` 要 API 26（或开 desugaring），项目一贯口径见 `DayMath`。
 */
fun formatDateMinute(millis: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = millis
    val month = cal.get(java.util.Calendar.MONTH) + 1
    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = cal.get(java.util.Calendar.MINUTE)
    return "%d月%d日 %02d:%02d".format(month, day, hour, minute)
}
