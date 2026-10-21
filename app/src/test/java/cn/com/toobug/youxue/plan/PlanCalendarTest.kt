package cn.com.toobug.youxue.plan

import cn.com.toobug.youxue.data.PlanRecord
import cn.com.toobug.youxue.data.StudyPlan
import cn.com.toobug.youxue.util.DayMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 月历的数据装配。
 *
 * 两个容易错的地方：一是**网格首格不一定是本月 1 号**（前面要补上月末的几天），
 * 二是**完成率的分母不能含未来的日子**——月初打开看到「完成 0 / 20 次」，
 * 那是把还没到的日子也算进去了，会让人以为自己在偷懒。
 */
class PlanCalendarTest {

    private val planStart = DayMath.ymdToEpochDay(2026, 9, 1)
    private val sep25 = DayMath.ymdToEpochDay(2026, 9, 25)   // 周五
    private val sep28 = DayMath.ymdToEpochDay(2026, 9, 28)   // 周一
    private val sep30 = DayMath.ymdToEpochDay(2026, 9, 30)   // 周三

    private fun plan(
        weekdays: Set<Int> = setOf(1, 3, 5),
        startDay: Long = planStart,
        endDay: Long? = null,
    ) = StudyPlan(
        id = "p1",
        title = "听写",
        weekdays = weekdays,
        startDay = startDay,
        endDay = endDay,
        enabled = true,
        createdAt = 0L,
    )

    private fun rec(day: Long) =
        PlanRecord(planId = "p1", day = day, completedAt = 1_700_000_000_000L)

    // ------------------------------------------------------------------ 网格

    @Test
    fun `月历固定四十二格`() {
        assertEquals(42, DayMath.monthGrid(2026, 9).size)
        // 2 月也要是 6 行，高度恒定，翻月时网格不跳
        assertEquals(42, DayMath.monthGrid(2026, 2).size)
    }

    @Test
    fun `网格首格是包含一号那一周的周日`() {
        // 2026-09-01 是周二，所以首格要补到 8/30（周日）
        val grid = DayMath.monthGrid(2026, 9)
        val first = grid.first()
        assertEquals(DayMath.ymdToEpochDay(2026, 8, 30), first)
        assertEquals(0, DayMath.dayOfWeek(first))
        // 一号落在第三格（补了两天）
        assertEquals(DayMath.ymdToEpochDay(2026, 9, 1), grid[2])
    }

    @Test
    fun `翻月跨年`() {
        val next = DayMath.shiftMonth(2026, 12, 1)
        assertEquals(2027, next.year)
        assertEquals(1, next.month)

        val prev = DayMath.shiftMonth(2026, 1, -1)
        assertEquals(2025, prev.year)
        assertEquals(12, prev.month)
    }

    @Test
    fun `每月天数`() {
        assertEquals(30, DayMath.daysInMonth(2026, 9))
        assertEquals(31, DayMath.daysInMonth(2026, 12))
        assertEquals(28, DayMath.daysInMonth(2026, 2))
        assertEquals(29, DayMath.daysInMonth(2028, 2))
    }

    // ------------------------------------------------------------------ 格子

    @Test
    fun `格子标出是否本月 是否今天 是否未来`() {
        val cells = PlanCalendar.monthCells(plan(), emptyList(), 2026, 9, sep30)

        // 首格是上月末的补位
        assertFalse(cells.first().inMonth)
        assertTrue(cells.first().future.not())

        val one = cells.first { DayMath.dayOfMonth(it.day) == 1 && it.inMonth }
        assertTrue(one.inMonth)

        val today = cells.first { it.day == sep30 }
        assertTrue(today.today)
        assertFalse(today.future)

        val tomorrow = cells.first { it.day == sep30 + 1 }
        assertTrue(tomorrow.future)
    }

    @Test
    fun `格子标出哪天该做`() {
        val cells = PlanCalendar.monthCells(plan(), emptyList(), 2026, 9, sep30)
        fun cellOf(day: Long) = cells.first { it.day == day }

        assertTrue(cellOf(sep25).repeatDay)   // 周五
        assertFalse(cellOf(sep25 + 1).repeatDay) // 周六
        assertTrue(cellOf(sep28).repeatDay)   // 周一
    }

    @Test
    fun `打完卡的格子被标出来`() {
        val cells = PlanCalendar.monthCells(
            plan = plan(),
            records = listOf(rec(sep28), rec(sep30)),
            year = 2026,
            month = 9,
            today = sep30,
        )
        assertTrue(cells.first { it.day == sep28 }.done)
        assertTrue(cells.first { it.day == sep30 }.done)
        assertFalse(cells.first { it.day == sep25 }.done)
    }

    @Test
    fun `只认本计划的打卡记录`() {
        val other = PlanRecord(planId = "other", day = sep28, completedAt = 1L)
        val cells = PlanCalendar.monthCells(plan(), listOf(other), 2026, 9, sep30)
        assertFalse(cells.first { it.day == sep28 }.done)
    }

    // ------------------------------------------------------------------ 统计

    @Test
    fun `本月成绩只统计已过去的日子`() {
        val cells = PlanCalendar.monthCells(
            plan = plan(),
            records = listOf(rec(sep28), rec(sep30)),
            year = 2026,
            month = 9,
            today = sep30,
        )
        val summary = PlanCalendar.summaryOfMonth(cells)
        // 9/1~9/30 之间的周一周三周五共 13 天，其中打了 2 天
        assertEquals(13, summary.due)
        assertEquals(2, summary.done)
    }

    @Test
    fun `补位格不计入本月成绩`() {
        // 只看 9 月：8/31（周一）是补位格，即便该做也不能算进 9 月
        val cells = PlanCalendar.monthCells(plan(), emptyList(), 2026, 9, sep30)
        val inMonth = cells.filter { it.inMonth }
        assertEquals(30, inMonth.size)
        // 今天就是月末，本月没有「未来」的格子，两种口径的分母应当一致
        assertFalse(inMonth.any { it.future })
        assertEquals(13, PlanCalendar.summaryOfMonth(cells).due)
        assertEquals(13, inMonth.count { it.repeatDay })
    }

    @Test
    fun `该做为零时完成率按百分之百`() {
        // 分母是 0 时若返回 0，界面上会出现「0%」这种看着像失败的显示
        val summary = PlanCalendar.Summary(due = 0, done = 0)
        assertEquals(1f, summary.rate, 0.0001f)
    }

    @Test
    fun `近三十天成绩含今天`() {
        val summary = PlanCalendar.summaryOfRecent(
            plan = plan(),
            records = listOf(rec(sep28), rec(sep30)),
            today = sep30,
            days = 30,
        )
        // 9/1 到 9/30，与本月成绩口径一致
        assertEquals(13, summary.due)
        assertEquals(2, summary.done)
    }

    @Test
    fun `计划尚未开始时不产生该做日`() {
        val summary = PlanCalendar.summaryOfRecent(
            plan = plan(startDay = DayMath.ymdToEpochDay(2027, 1, 1)),
            records = emptyList(),
            today = sep30,
            days = 30,
        )
        assertEquals(0, summary.due)
    }
}
