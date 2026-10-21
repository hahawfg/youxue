package cn.com.toobug.youxue.plan

import cn.com.toobug.youxue.data.PlanRecord
import cn.com.toobug.youxue.data.StudyPlan
import cn.com.toobug.youxue.util.DayMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计划重复规则的判定与统计。
 *
 * 这个文件的重点是**连续打卡的「按重复日连续」语义**：
 * 只排周一三五的计划，周二没做不该断连胜。这是最容易写成
 * 「按自然日连续」的地方，而写错了表现是连胜数字忽大忽小，
 * 很难靠肉眼在界面上发现。
 *
 * 所有日期都用 `DayMath.ymdToEpochDay` 从真实日历推出，不硬编码 epoch day
 * ——基准错了要一眼看得见（见第一条用例）。
 */
class PlanScheduleTest {

    // 2026-09-25 是周五，下面都以它为锚点推
    private val fri = DayMath.ymdToEpochDay(2026, 9, 25)
    private val sat = DayMath.ymdToEpochDay(2026, 9, 26)
    private val sun = DayMath.ymdToEpochDay(2026, 9, 27)
    private val mon = DayMath.ymdToEpochDay(2026, 9, 28)
    private val tue = DayMath.ymdToEpochDay(2026, 9, 29)
    private val wed = DayMath.ymdToEpochDay(2026, 9, 30)

    /** 一三五 */
    private val MWF = setOf(1, 3, 5)
    private val WORKDAYS = setOf(1, 2, 3, 4, 5)

    private fun plan(
        id: String = "p1",
        title: String = "听写",
        weekdays: Set<Int> = MWF,
        startDay: Long = DayMath.ymdToEpochDay(2026, 9, 1),
        endDay: Long? = null,
        enabled: Boolean = true,
    ) = StudyPlan(
        id = id,
        title = title,
        weekdays = weekdays,
        startDay = startDay,
        endDay = endDay,
        enabled = enabled,
        createdAt = 0L,
    )

    private fun rec(day: Long, planId: String = "p1") =
        PlanRecord(planId = planId, day = day, completedAt = 1_700_000_000_000L)

    // ------------------------------------------------------------------ 基准

    @Test
    fun `基准日期 2026年9月25日是周五`() {
        assertEquals(5, DayMath.dayOfWeek(fri))
        assertEquals(6, DayMath.dayOfWeek(sat))
        assertEquals(0, DayMath.dayOfWeek(sun))
        assertEquals(1, DayMath.dayOfWeek(mon))
        assertEquals(3, DayMath.dayOfWeek(wed))
    }

    // ------------------------------------------------------------------ 判定

    @Test
    fun `命中重复日才算要做`() {
        val p = plan()
        assertTrue(PlanSchedule.isDueOn(p, mon))
        assertFalse(PlanSchedule.isDueOn(p, tue))
        assertFalse(PlanSchedule.isDueOn(p, sat))
    }

    @Test
    fun `起始日之前不做 起始日当天开始做`() {
        val p = plan(startDay = wed)
        assertFalse(PlanSchedule.isRepeatDay(p, tue))
        assertTrue(PlanSchedule.isRepeatDay(p, wed))
    }

    @Test
    fun `结束日当天要做 之后不做`() {
        val p = plan(endDay = wed)
        assertTrue(PlanSchedule.isRepeatDay(p, wed))
        assertFalse(PlanSchedule.isRepeatDay(p, wed + 1))
        // 结束日之后即便星期命中也不算
        assertFalse(PlanSchedule.isRepeatDay(p, wed + 7))
    }

    @Test
    fun `重复日为空表示每天`() {
        val p = plan(weekdays = emptySet())
        assertTrue(PlanSchedule.isRepeatDay(p, mon))
        assertTrue(PlanSchedule.isRepeatDay(p, tue))
        assertTrue(PlanSchedule.isRepeatDay(p, sat))
        assertTrue(PlanSchedule.isRepeatDay(p, sun))
    }

    @Test
    fun `停用后今天不做 但重复日判定不受影响`() {
        val p = plan(enabled = false)
        // 这两个的区别是整份逻辑的枢纽：列表与角标看 isDueOn，
        // 连续打卡看 isRepeatDay。合成一个就会让「停用一下」
        // 把历史连胜全部清零
        assertFalse(PlanSchedule.isDueOn(p, mon))
        assertTrue(PlanSchedule.isRepeatDay(p, mon))
    }

    @Test
    fun `打卡判定只认同一条计划的同一天`() {
        val records = listOf(rec(day = mon), rec(day = tue, planId = "other"))
        assertTrue(PlanSchedule.isDoneOn("p1", mon, records))
        assertFalse(PlanSchedule.isDoneOn("p1", tue, records))
        assertFalse(PlanSchedule.isDoneOn("other", mon, records))
    }

    // ------------------------------------------------------------------ 今日待办

    @Test
    fun `待办数只算今天该做且没打卡的`() {
        val plans = listOf(
            plan(id = "a"),
            plan(id = "b"),
            plan(id = "c", weekdays = setOf(2)), // 周二，今天不是
            plan(id = "d", enabled = false),
        )
        val records = listOf(rec(day = mon, planId = "b"))
        // 今天周一：a 没做、b 做了、c 不轮、d 停用
        assertEquals(1, PlanSchedule.pendingCount(plans, records, mon))
    }

    @Test
    fun `今天该做的列表 没做的排在前面`() {
        val plans = listOf(
            plan(id = "done", startDay = 0L),
            plan(id = "todo", startDay = 0L),
        )
        val records = listOf(rec(day = mon, planId = "done"))
        val due = PlanSchedule.dueToday(plans, records, mon)
        assertEquals(listOf("todo", "done"), due.map { it.id })
    }

    @Test
    fun `停用的计划不出现在今天 但出现在其它计划里`() {
        val plans = listOf(plan(id = "a", enabled = false))
        assertTrue(PlanSchedule.dueToday(plans, emptyList(), mon).isEmpty())
        assertEquals(listOf("a"), PlanSchedule.notDueToday(plans, mon).map { it.id })
    }

    // ------------------------------------------------------------------ 下一次

    @Test
    fun `周末查到的是下周一`() {
        val p = plan()
        assertEquals(mon, PlanSchedule.nextDueDay(p, sat))
    }

    @Test
    fun `起始日在未来时 下一次就是起始日`() {
        val p = plan(startDay = wed)
        assertEquals(wed, PlanSchedule.nextDueDay(p, mon))
    }

    @Test
    fun `计划已结束则找不到下一次`() {
        val p = plan(startDay = fri, endDay = fri)
        assertNull(PlanSchedule.nextDueDay(p, sat))
    }

    // ------------------------------------------------------------------ 连续打卡

    @Test
    fun `没有记录时连续为零`() {
        assertEquals(0, PlanSchedule.streak(plan(), emptyList(), mon))
    }

    @Test
    fun `连续三次重复日都打卡 连续为三`() {
        val records = listOf(rec(day = fri), rec(day = mon), rec(day = wed))
        assertEquals(3, PlanSchedule.streak(plan(), records, wed))
    }

    @Test
    fun `中间的休息日不算断卡`() {
        // 一三五计划：周四没做完全正常，连续数不该在这里归零。
        // 这正是「按重复日连续」与「按自然日连续」的分水岭
        val records = listOf(rec(day = fri), rec(day = mon), rec(day = wed))
        assertEquals(3, PlanSchedule.streak(plan(), records, wed))
    }

    @Test
    fun `漏掉一个重复日就断了`() {
        // 一三五计划：9/25(五) 与 9/30(三) 打了，9/28(一) 没打
        val records = listOf(rec(day = fri), rec(day = wed))
        assertEquals(1, PlanSchedule.streak(plan(), records, wed))
    }

    @Test
    fun `今天该做但还没打卡 不算断卡`() {
        // 今天(周三)还没做，从上一个重复日(周一)往回数。
        // 清早打开就看到连胜归零会让人以为数据丢了
        val records = listOf(rec(day = mon))
        assertEquals(1, PlanSchedule.streak(plan(), records, wed))
    }

    @Test
    fun `起始日之前的空缺不算断卡`() {
        val p = plan(startDay = mon)
        val records = listOf(rec(day = mon), rec(day = wed))
        assertEquals(2, PlanSchedule.streak(p, records, wed))
    }

    @Test
    fun `只数本计划的记录`() {
        val records = listOf(rec(day = mon, planId = "other"), rec(day = wed))
        assertEquals(1, PlanSchedule.streak(plan(), records, wed))
    }

    @Test
    fun `计划已结束时从结束日往回数`() {
        val p = plan(startDay = fri, endDay = wed)
        val records = listOf(rec(day = fri), rec(day = mon), rec(day = wed))
        // 结束日之后的日子不该继续要求打卡
        assertEquals(3, PlanSchedule.streak(p, records, fri + 10))
    }

    // ------------------------------------------------------------------ 完成率

    @Test
    fun `区间内该做的天数`() {
        // 9/1(二) 到 9/30(三) 之间的周一周三周五：
        // 周一 7/14/21/28（4 天），周三 2/9/16/23/30（5 天），周五 4/11/18/25（4 天）
        val from = DayMath.ymdToEpochDay(2026, 9, 1)
        val to = DayMath.ymdToEpochDay(2026, 9, 30)
        assertEquals(13, PlanSchedule.dueDayCount(plan(startDay = 0L), from, to))
    }

    @Test
    fun `该做天数会被裁到计划有效期内`() {
        val from = DayMath.ymdToEpochDay(2026, 9, 1)
        val to = DayMath.ymdToEpochDay(2026, 9, 30)
        // 计划从 9/25(五) 才开始：只有 25(五)、28(一)、30(三) 这三天
        assertEquals(3, PlanSchedule.dueDayCount(plan(startDay = fri), from, to))
    }

    @Test
    fun `完成天数只算该做日里打了卡的`() {
        val from = DayMath.ymdToEpochDay(2026, 9, 1)
        val to = DayMath.ymdToEpochDay(2026, 9, 30)
        val records = listOf(
            rec(day = mon),  // 该做，计入
            rec(day = tue),  // 不该做，不计入
        )
        assertEquals(1, PlanSchedule.doneDayCount(plan(startDay = 0L), records, from, to))
    }

    @Test
    fun `累计完成次数按天去重`() {
        val records = listOf(rec(day = mon), rec(day = mon, planId = "other"), rec(day = wed))
        assertEquals(2, PlanSchedule.totalDoneDays(plan(startDay = 0L), records))
    }

    // ------------------------------------------------------------------ 摘要

    @Test
    fun `重复规则摘要`() {
        assertEquals("每天", PlanSchedule.repeatLabel((0..6).toSet()))
        assertEquals("每天", PlanSchedule.repeatLabel(emptySet()))
        assertEquals("工作日", PlanSchedule.repeatLabel(WORKDAYS))
        assertEquals("周末", PlanSchedule.repeatLabel(setOf(0, 6)))
        assertEquals("周一、周三、周五", PlanSchedule.repeatLabel(MWF))
        // 顺序按周一到周日，不按 0=周日 的索引顺序——中文里没人把周日说在最前面
        assertEquals("周一、周日", PlanSchedule.repeatLabel(setOf(0, 1)))
    }
}
