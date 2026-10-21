package cn.com.toobug.youxue.data

import cn.com.toobug.youxue.util.DayMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务本身的规则测试。
 *
 * 重点是 [TaskItem.reschedule] 维护的 `startDay <= endDay` ——
 * 区间一旦为空，任务在列表里**哪一天都不会出现**，是把任务弄丢的那种错。
 * 这条规则同时被「拖动落点」和「编辑弹层里改日期」两个入口使用，
 * 所以必须逐条钉在测试里。
 */
class TaskItemTest {

    private val today = 20_000L

    private fun item(start: Long? = null, end: Long? = null, done: Boolean = false) =
        TaskItem(id = "a", title = "听写", startDay = start, endDay = end, done = done)

    // ------------------------------------------------------ 安排日期

    @Test
    fun `未安排的任务没有起始日`() {
        val t = item()
        assertNull(t.startDay)
        assertNull(t.endDay)
        assertFalse(t.coversDay(today))
    }

    @Test
    fun `从无到有安排截止日时 起始日取今天`() {
        val t = item().reschedule(today + 5, today)
        assertEquals(today, t.startDay)
        assertEquals(today + 5, t.endDay)
    }

    @Test
    fun `选了一个已经过去的截止日时 起始日取截止日 保证区间非空`() {
        // 用户完全可能在日期选择器里挑一个已经过去的日子
        val t = item().reschedule(today - 3, today)
        assertEquals(today - 3, t.startDay)
        assertEquals(today - 3, t.endDay)
        assertTrue(t.coversDay(today - 3))
    }

    @Test
    fun `清空截止日会连起始日一起清掉`() {
        val t = item(start = today - 2, end = today + 2).reschedule(null, today)
        assertNull(t.startDay)
        assertNull(t.endDay)
    }

    @Test
    fun `改截止日保留原起始日`() {
        // 任务已经开始了，改截止日不该把它的历史起点抹掉
        val t = item(start = today - 2, end = today + 2).reschedule(today + 9, today)
        assertEquals(today - 2, t.startDay)
        assertEquals(today + 9, t.endDay)
    }

    @Test
    fun `新截止日早于原起始日时 起始日被夹到截止日`() {
        val t = item(start = today, end = today + 5).reschedule(today - 4, today)
        assertEquals(today - 4, t.startDay)
        assertEquals(today - 4, t.endDay)
    }

    // ------------------------------------------------------ 区间覆盖

    @Test
    fun `coversDay 覆盖区间两端`() {
        val t = item(start = today, end = today + 2)
        assertFalse(t.coversDay(today - 1))
        assertTrue(t.coversDay(today))
        assertTrue(t.coversDay(today + 1))
        assertTrue(t.coversDay(today + 2))
        assertFalse(t.coversDay(today + 3))
    }

    @Test
    fun `单日的任务只覆盖那一天`() {
        val t = item(start = today, end = today)
        assertTrue(t.coversDay(today))
        assertFalse(t.coversDay(today + 1))
    }

    // ------------------------------------------------------ 过期与完成

    @Test
    fun `截止日当天不算过期`() {
        assertFalse(item(start = today, end = today).isOverdue(today))
    }

    @Test
    fun `截止日过了才算过期`() {
        assertTrue(item(start = today - 2, end = today - 1).isOverdue(today))
    }

    @Test
    fun `已完成的不算过期`() {
        assertFalse(item(start = today - 2, end = today - 1, done = true).isOverdue(today))
    }

    @Test
    fun `未安排的任务不算过期`() {
        assertFalse(item().isOverdue(today))
    }

    @Test
    fun `完成日按本地时区换算`() {
        // 完成时刻存的是毫秒（同一天内能分出先后），按天归组时要换回日历天
        val day = today - 1
        val t = TaskItem(
            id = "a",
            title = "听写",
            startDay = day,
            endDay = day,
            done = true,
            completedAt = DayMath.localMillisAt(day) + 20 * 3_600_000L,
        )
        assertEquals(day, t.completedDay())
    }

    @Test
    fun `没完成时没有完成日`() {
        assertNull(item(start = today, end = today).completedDay())
    }
}
