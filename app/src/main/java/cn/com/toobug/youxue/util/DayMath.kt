package cn.com.toobug.youxue.util

import java.util.Calendar
import java.util.TimeZone

/**
 * 日历天工具。
 *
 * 全部基于 **epoch day**（1970-01-01 记为 0）做整数运算。
 * 刻意不在毫秒时间戳上直接加减 86400000——在实行夏令时的地区，
 * 一年里有两天只有 23 或 25 小时，「时间戳加一天」会偏到隔壁日期去。
 * 换成天数运算就不存在这个问题。
 *
 * 只用 `java.util.Calendar` 而不是 `java.time`，是因为 minSdk 24：
 * `java.time` 需要 API 26，或开启 core library desugaring 再加一个依赖。
 * Calendar 全版本可用，且在 JVM 单测里能直接跑。
 *
 * 内部固定用 UTC 时区做「天 ↔ 年月日」的换算，这样 `epochDay * 86400000`
 * 恰好是那一天的零点，读出来的年月日不会有偏移；
 * 只有「今天」是按**本地**时区取的。
 */
object DayMath {

    /**
     * 一天的毫秒数。
     *
     * 对外公开是因为界面侧也要用它做「epoch day ↔ DatePicker 的毫秒」换算
     * （DatePicker 收发的就是 UTC 零点毫秒，除以它正好得到 epoch day）。
     * 注意它**只适用于 UTC 下「天 ↔ 毫秒」的换算**——
     * 本地时区有夏令时，那边的 23/25 小时日要靠 [localMillisAt]。
     */
    const val MILLIS_PER_DAY = 86_400_000L
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    private val WEEK_LABELS = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /** 本地时区下的今天 */
    fun today(): Long {
        val c = Calendar.getInstance()
        return ymdToEpochDay(
            year = c.get(Calendar.YEAR),
            month = c.get(Calendar.MONTH) + 1,
            day = c.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** 公历年月日 → epoch day */
    fun ymdToEpochDay(year: Int, month: Int, day: Int): Long {
        val c = Calendar.getInstance(UTC)
        c.clear()
        c.set(year, month - 1, day, 0, 0, 0)
        return c.timeInMillis / MILLIS_PER_DAY
    }

    /** epoch day → 公历年月日 */
    fun ymd(epochDay: Long): Ymd {
        val c = Calendar.getInstance(UTC)
        c.timeInMillis = epochDay * MILLIS_PER_DAY
        return Ymd(
            year = c.get(Calendar.YEAR),
            month = c.get(Calendar.MONTH) + 1,
            day = c.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** 0 = 周日，6 = 周六 */
    fun dayOfWeek(epochDay: Long): Int {
        val c = Calendar.getInstance(UTC)
        c.timeInMillis = epochDay * MILLIS_PER_DAY
        // Calendar 里 SUNDAY = 1，减一即得「周日为 0」
        return c.get(Calendar.DAY_OF_WEEK) - 1
    }

    fun weekLabel(epochDay: Long): String = WEEK_LABELS[dayOfWeek(epochDay)]

    fun dayOfMonth(epochDay: Long): Int = ymd(epochDay).day

    /** 该月的天数。靠「下个月 1 号往前退一天」求，比记闰年规则可靠 */
    fun daysInMonth(year: Int, month: Int): Int {
        val c = Calendar.getInstance(UTC)
        c.clear()
        c.set(year, month - 1, 1, 0, 0, 0)
        c.add(Calendar.MONTH, 1)
        c.add(Calendar.DAY_OF_MONTH, -1)
        return c.get(Calendar.DAY_OF_MONTH)
    }

    /**
     * 前后翻月。[delta] 为 -1 表示上个月。
     *
     * 返回的 [Ymd.day] 固定是 1，调用方只用它的 year/month——
     * 「同一个日号在上个月可能不存在」（如 3 月 31 日的上个月没有 31 号），
     * 与其夹到 28/30 号制造一个不是用户想要的日期，不如明确只翻年月。
     */
    fun shiftMonth(year: Int, month: Int, delta: Int): Ymd {
        val c = Calendar.getInstance(UTC)
        c.clear()
        c.set(year, month - 1, 1, 0, 0, 0)
        c.add(Calendar.MONTH, delta)
        return Ymd(
            year = c.get(Calendar.YEAR),
            month = c.get(Calendar.MONTH) + 1,
            day = 1,
        )
    }

    /**
     * 月历网格：该月视图（周日起始）连续 42 天的 epoch day，含上月/下月的补位。
     *
     * 固定 6 行 42 格，而不是按需 5 或 6 行：翻月时网格高度不跳，
     * 点「上个月」的按钮不会从指下溜走。
     */
    fun monthGrid(year: Int, month: Int): List<Long> {
        val first = ymdToEpochDay(year, month, 1)
        // dayOfWeek 返回 0=周日，正好等于「这个月首日之前要补几个格子」
        val start = first - dayOfWeek(first)
        return List(42) { start + it }
    }

    /** 日期条上的小标签，如 `9/27` */
    fun shortLabel(epochDay: Long): String {
        val d = ymd(epochDay)
        return "${d.month}/${d.day}"
    }

    /**
     * 毫秒时间戳 → 它落在本地时区的哪一天。
     *
     * 任务的「完成时刻」记的是真实时刻（毫秒，同一天内能分出先后），
     * 但要按天归组（「这天完成了什么」）又必须换回日历天。
     * 这个函数就是两者之间的桥，注意它**按本地时区**取日，
     * 和 [ymdToEpochDay] 内部固定用 UTC 不是一回事——后者只是为了让
     * 「天 ↔ 年月日」的换算不受时区影响。
     */
    fun localDayOf(millis: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        return ymdToEpochDay(
            year = c.get(Calendar.YEAR),
            month = c.get(Calendar.MONTH) + 1,
            day = c.get(Calendar.DAY_OF_MONTH),
        )
    }

    /**
     * [localDayOf] 的反函数：某个日历天在**本地时区**的零点。
     *
     * 界面用不到它（时间戳都是 `System.currentTimeMillis()` 来的），
     * 但测试需要造「本地某一天的某个时刻」这样的时间戳，
     * 而按 `day * 86400000` 硬算在非零时区会落到隔壁那天去。
     */
    fun localMillisAt(epochDay: Long): Long {
        val d = ymd(epochDay)
        val c = Calendar.getInstance()
        c.clear()
        c.set(d.year, d.month - 1, d.day, 0, 0, 0)
        return c.timeInMillis
    }

    /** 完整说法，如 `9月27日` */
    fun monthDayLabel(epochDay: Long): String {
        val d = ymd(epochDay)
        return "${d.month}月${d.day}日"
    }

    /**
     * 相对今天的说法。
     * 近三天用「今天/明天/后天/昨天」更好认，再远就直接报日期。
     */
    fun relativeLabel(epochDay: Long, today: Long): String = when (epochDay - today) {
        0L -> "今天"
        1L -> "明天"
        2L -> "后天"
        -1L -> "昨天"
        -2L -> "前天"
        else -> weekLabel(epochDay)
    }

    /** `今天 · 9月27日` 这样的副标题 */
    fun fullLabel(epochDay: Long, today: Long): String =
        "${relativeLabel(epochDay, today)} · ${monthDayLabel(epochDay)}"

    /** 公历年月日 */
    data class Ymd(val year: Int, val month: Int, val day: Int)
}
