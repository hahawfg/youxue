package cn.com.toobug.youxue.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日历天换算测试。
 *
 * 这些断言用的都是**已知**的星期：1970-01-01 是周四、2000-01-01 是周六。
 * 换算出错的表现是「任务被安排到了昨天/明天」，属于用户一眼能看出的问题，
 * 所以宁可把基准写死在这。
 */
class DayMathTest {

    @Test
    fun `纪元起点为第零天`() {
        assertEquals(0L, DayMath.ymdToEpochDay(1970, 1, 1))
        assertEquals(1L, DayMath.ymdToEpochDay(1970, 1, 2))
    }

    @Test
    fun `年月日与天数互转可往返`() {
        val dates = listOf(
            Triple(1970, 1, 1),
            Triple(1999, 12, 31),
            Triple(2000, 1, 1),
            Triple(2024, 2, 29),
            Triple(2026, 9, 25),
            Triple(2038, 7, 4),
        )
        dates.forEach { (y, m, d) ->
            val ymd = DayMath.ymd(DayMath.ymdToEpochDay(y, m, d))
            assertEquals("$y-$m-$d", Triple(ymd.year, ymd.month, ymd.day), Triple(y, m, d))
        }
    }

    @Test
    fun `跨月与跨年只差一天`() {
        assertEquals(
            DayMath.ymdToEpochDay(2027, 1, 1),
            DayMath.ymdToEpochDay(2026, 12, 31) + 1,
        )
        assertEquals(
            DayMath.ymdToEpochDay(2024, 3, 1),
            DayMath.ymdToEpochDay(2024, 2, 29) + 1,
        )
    }

    @Test
    fun `星期计算与已知事实一致`() {
        // 0 = 周日
        assertEquals(4, DayMath.dayOfWeek(DayMath.ymdToEpochDay(1970, 1, 1)))   // 周四
        assertEquals(6, DayMath.dayOfWeek(DayMath.ymdToEpochDay(2000, 1, 1)))   // 周六
        assertEquals(5, DayMath.dayOfWeek(DayMath.ymdToEpochDay(2026, 9, 25)))  // 周五
    }

    @Test
    fun `星期标签取对应中文`() {
        val friday = DayMath.ymdToEpochDay(2026, 9, 25)
        assertEquals("周五", DayMath.weekLabel(friday))
        assertEquals("周六", DayMath.weekLabel(friday + 1))
        assertEquals("周一", DayMath.weekLabel(friday + 3))
        assertEquals("周日", DayMath.weekLabel(friday + 2))
    }

    @Test
    fun `日期标签格式符合预期`() {
        val day = DayMath.ymdToEpochDay(2026, 9, 27)
        assertEquals("9/27", DayMath.shortLabel(day))
        assertEquals("9月27日", DayMath.monthDayLabel(day))
        assertEquals(27, DayMath.dayOfMonth(day))
    }

    @Test
    fun `相对说法覆盖前后三天`() {
        val today = DayMath.ymdToEpochDay(2026, 9, 25)
        assertEquals("今天", DayMath.relativeLabel(today, today))
        assertEquals("明天", DayMath.relativeLabel(today + 1, today))
        assertEquals("后天", DayMath.relativeLabel(today + 2, today))
        assertEquals("昨天", DayMath.relativeLabel(today - 1, today))
        assertEquals("前天", DayMath.relativeLabel(today - 2, today))
        // 再远就退回星期，避免出现「大前天」「4 天后」这类绕口的说法
        assertEquals("周三", DayMath.relativeLabel(today + 5, today))
    }

    @Test
    fun `完整说法由相对词与日期拼成`() {
        val today = DayMath.ymdToEpochDay(2026, 9, 25)
        assertEquals("今天 · 9月25日", DayMath.fullLabel(today, today))
        assertEquals("周二 · 9月29日", DayMath.fullLabel(today + 4, today))
    }

    @Test
    fun `今天落在合理区间内`() {
        // 不硬编码具体值，只验证它确实是「最近」的一天
        val today = DayMath.today()
        assertTrue(today > DayMath.ymdToEpochDay(2024, 1, 1))
        assertTrue(today < DayMath.ymdToEpochDay(2100, 1, 1))
    }

    @Test
    fun `本地某天的零点与那一天互转一致`() {
        // 完成时刻 → 完成日 的桥。用「天 × 86400000」硬算在非零时区会落到隔壁那天，
        // 所以这里必须用本地时区走一遍真正的换算
        val days = listOf(
            DayMath.ymdToEpochDay(2026, 1, 1),
            DayMath.ymdToEpochDay(2026, 6, 15),
            DayMath.ymdToEpochDay(2026, 9, 25),
            DayMath.ymdToEpochDay(2026, 12, 31),
        )
        days.forEach { day ->
            assertEquals(day, DayMath.localDayOf(DayMath.localMillisAt(day)))
            // 当天中午也必须还在同一天
            assertEquals(day, DayMath.localDayOf(DayMath.localMillisAt(day) + 12 * 3_600_000L))
        }
    }

    @Test
    fun `本地零点换算能跨过整年`() {
        val lastDay = DayMath.ymdToEpochDay(2026, 12, 31)
        val firstDay = DayMath.ymdToEpochDay(2027, 1, 1)
        assertEquals(firstDay, DayMath.localDayOf(DayMath.localMillisAt(lastDay) + 24 * 3_600_000L))
    }
}
