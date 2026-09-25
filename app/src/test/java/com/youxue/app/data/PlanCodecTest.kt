package com.youxue.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计划与记录的编解码。
 *
 * 两组重点：
 * 1. **任何用户输入都不能破坏结构**——计划名里出现竖线、换行是完全可能的
 *    （粘贴一段带格式的文字就带进来了），漏一个转义就会把列表切碎、后续全部错位。
 * 2. **坏数据要能兜住**——解码失败的行丢弃而不是抛异常。宁可少一条计划，
 *    也不能让整个列表打不开。
 */
class PlanCodecTest {

    private fun plan(
        id: String = "p1",
        title: String = "每周听写",
        weekdays: Set<Int> = setOf(1, 3, 5),
        startDay: Long = 20_000L,
        endDay: Long? = null,
        enabled: Boolean = true,
        createdAt: Long = 1_700_000_000_000L,
    ) = StudyPlan(id, title, weekdays, startDay, endDay, enabled, createdAt)

    private fun roundTrip(vararg plans: StudyPlan): List<StudyPlan> =
        PlanCodec.decodePlans(PlanCodec.encodePlans(plans.toList()))

    private fun record(
        planId: String = "p1",
        day: Long = 20_000L,
        completedAt: Long = 1_700_000_000_000L,
    ) = PlanRecord(planId, day, completedAt)

    private fun roundTripRecords(vararg records: PlanRecord): List<PlanRecord> =
        PlanCodec.decodeRecords(PlanCodec.encodeRecords(records.toList()))

    // ------------------------------------------------------------------ 计划

    @Test
    fun `空列表编码为空串再解回空列表`() {
        assertEquals("", PlanCodec.encodePlans(emptyList()))
        assertTrue(PlanCodec.decodePlans("").isEmpty())
    }

    @Test
    fun `普通计划完整往返`() {
        val original = plan(endDay = 20_100L, enabled = false)
        assertEquals(listOf(original), roundTrip(original))
    }

    @Test
    fun `重复日集合往返`() {
        assertEquals(setOf(1, 3, 5), roundTrip(plan(weekdays = setOf(1, 3, 5))).single().weekdays)
        assertEquals(setOf(0, 6), roundTrip(plan(weekdays = setOf(0, 6))).single().weekdays)
        assertEquals((0..6).toSet(), roundTrip(plan(weekdays = (0..6).toSet())).single().weekdays)
    }

    @Test
    fun `空重复日往返 表示每天`() {
        assertEquals(emptySet<Int>(), roundTrip(plan(weekdays = emptySet())).single().weekdays)
    }

    @Test
    fun `同一集合不同插入顺序编码结果相同`() {
        // 编码前排序，是为了让「值没变就不落盘」这类判断可靠
        val a = PlanCodec.encodePlans(listOf(plan(weekdays = setOf(5, 1, 3))))
        val b = PlanCodec.encodePlans(listOf(plan(weekdays = linkedSetOf(3, 5, 1))))
        assertEquals(a, b)
    }

    @Test
    fun `长期有效往返为 null 结束日`() {
        assertEquals(null, roundTrip(plan(endDay = null)).single().endDay)
    }

    @Test
    fun `启用标志往返`() {
        assertEquals(true, roundTrip(plan(enabled = true)).single().enabled)
        assertEquals(false, roundTrip(plan(enabled = false)).single().enabled)
    }

    @Test
    fun `计划名里的分隔符不会破坏结构`() {
        // 竖线、换行、反斜杠都是这套格式的元字符，必须被封住
        val tricky = "每天|听写\n共\\10 个"
        val restored = roundTrip(plan(title = tricky)).single()
        assertEquals(tricky, restored.title)
    }

    @Test
    fun `多行之间不会串位`() {
        val list = listOf(
            plan(id = "a", title = "第|一", weekdays = setOf(1)),
            plan(id = "b", title = "第二", weekdays = setOf(2, 4)),
            plan(id = "c", title = "第三", weekdays = emptySet()),
        )
        assertEquals(list, PlanCodec.decodePlans(PlanCodec.encodePlans(list)))
    }

    @Test
    fun `字段数不对的行被丢弃 其它行不受影响`() {
        val good = plan(id = "keep")
        val raw = PlanCodec.encodePlans(listOf(good)) + "\n缺|字段\n" + "a|b|c|d|e|f|g|h"
        val restored = PlanCodec.decodePlans(raw)
        assertEquals(listOf("keep"), restored.map { it.id })
    }

    @Test
    fun `缺起始日的行被丢弃`() {
        // 起始日是「哪天开始生效」的唯一依据，缺了没法判定
        assertTrue(PlanCodec.decodePlans("p1|标题|1,3||0|1|0").isEmpty())
    }

    @Test
    fun `缺 id 或标题的行被丢弃`() {
        assertTrue(PlanCodec.decodePlans("|标题|1|100||1|0").isEmpty())
        assertTrue(PlanCodec.decodePlans("p1||1|100||1|0").isEmpty())
    }

    @Test
    fun `结束日早于起始日时退化为长期有效`() {
        // 空区间会让计划「哪天都不出现」，等于凭空消失。
        // 与 TaskCodec 的取舍一致：宁可纠正
        val restored = PlanCodec.decodePlans("p1|标题|1|200|100|1|0").single()
        assertEquals(null, restored.endDay)
        assertEquals(200L, restored.startDay)
    }

    @Test
    fun `重复日里的越界值和非数字被过滤`() {
        val restored = PlanCodec.decodePlans("p1|标题|1,x,9,-1,3|100||1|0").single()
        assertEquals(setOf(1, 3), restored.weekdays)
    }

    @Test
    fun `缺创建时刻时按零处理`() {
        assertEquals(0L, PlanCodec.decodePlans("p1|标题|1|100||1|").single().createdAt)
    }

    // ------------------------------------------------------------------ 记录

    @Test
    fun `记录往返`() {
        val original = record(planId = "p|1", day = 20_123L, completedAt = 1_700_000_123_456L)
        assertEquals(listOf(original), roundTripRecords(original))
    }

    @Test
    fun `空记录列表往返`() {
        assertEquals("", PlanCodec.encodeRecords(emptyList()))
        assertTrue(PlanCodec.decodeRecords("").isEmpty())
    }

    @Test
    fun `记录缺字段被丢弃`() {
        assertTrue(PlanCodec.decodeRecords("p1|20000").isEmpty())
        assertTrue(PlanCodec.decodeRecords("|20000|123").isEmpty())
        assertTrue(PlanCodec.decodeRecords("p1||123").isEmpty())
    }

    @Test
    fun `两条数据格式互不通用`() {
        // 字段数不同，拿计划的文本去解记录（或反过来）应当什么都解不出来，
        // 而不是解出一堆错位的记录
        val planText = PlanCodec.encodePlans(listOf(plan()))
        assertTrue(PlanCodec.decodeRecords(planText).isEmpty())

        val recordText = PlanCodec.encodeRecords(listOf(record()))
        assertTrue(PlanCodec.decodePlans(recordText).isEmpty())
    }
}
