package cn.com.toobug.youxue.task

import cn.com.toobug.youxue.data.TaskItem
import cn.com.toobug.youxue.util.DayMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务切分与角标测试。
 *
 * 切分错了会直接表现成「任务不见了」——比如过了期的被丢到看不见的地方，
 * 或者区间外的日子莫名出现一条任务。所以归属、顺序、角标的起止都要断言清楚。
 *
 * 现在日期是**区间**（开始日..截止日），所以「哪天看得见」这件事必须逐日验证。
 */
class TaskGrouperTest {

    /** 固定一个「今天」，避免依赖真实时钟 */
    private val today = 20_000L

    private fun task(
        id: String,
        startDay: Long? = null,
        endDay: Long? = null,
        done: Boolean = false,
        createdAt: Long = 0L,
        completedAt: Long = 0L,
    ) = TaskItem(
        id = id,
        title = "任务-$id",
        content = "",
        startDay = startDay,
        endDay = endDay,
        done = done,
        createdAt = createdAt,
        completedAt = completedAt,
    )

    /** 一条「从 [from] 开始、到 [to] 为止」的任务 */
    private fun span(id: String, to: Long, from: Long = today, createdAt: Long = 0L) =
        task(id = id, startDay = from, endDay = to, createdAt = createdAt)

    /** 一条在 [day] 那天完成的任务。完成时刻取当天中午，避免卡在零点边界上 */
    private fun doneOn(id: String, day: Long, atHourOffset: Long = 12) = task(
        id = id,
        startDay = day,
        endDay = day,
        done = true,
        completedAt = DayMath.localMillisAt(day) + atHourOffset * 3_600_000L,
    )

    // ------------------------------------------------------ 分组归属

    @Test
    fun `选中日切出四组且顺序固定为 已过期 要做 未安排 这天完成`() {
        val tasks = listOf(
            doneOn("done", today),
            task("none"),
            span("active", today + 2),
            span("late", to = today - 1, from = today - 3),
        )
        val groups = TaskGrouper.group(tasks, today, today)
        assertEquals(
            listOf(
                TaskGrouper.GROUP_OVERDUE,
                TaskGrouper.GROUP_ACTIVE,
                TaskGrouper.GROUP_UNSCHEDULED,
                TaskGrouper.GROUP_DONE,
            ),
            groups.map { it.id },
        )
    }

    @Test
    fun `区间里的每一天都算要做 含起止两端`() {
        val t = span("a", from = today + 1, to = today + 3)
        listOf(today + 1, today + 2, today + 3).forEach { day ->
            val active = TaskGrouper.group(listOf(t), day, today)
                .firstOrNull { it.id == TaskGrouper.GROUP_ACTIVE }
            assertEquals("第 $day 天应当要做", listOf("a"), active?.tasks?.map { it.id })
        }
    }

    @Test
    fun `区间外的日子不出现这条任务`() {
        val t = span("a", from = today + 1, to = today + 3)
        // 区间之前：还没开始，什么都不该有
        assertTrue(TaskGrouper.group(listOf(t), today, today).isEmpty())
        // 区间之后：它不是「还在要做」，而是变成了过期
        val after = TaskGrouper.group(listOf(t), today + 4, today)
        assertFalse(after.any { it.id == TaskGrouper.GROUP_ACTIVE })
        assertEquals(
            listOf("a"),
            after.first { it.id == TaskGrouper.GROUP_OVERDUE }.tasks.map { it.id },
        )
    }

    @Test
    fun `还没开始的任务不在选中日出现`() {
        val t = span("future", from = today + 5, to = today + 7)
        assertTrue(TaskGrouper.group(listOf(t), today, today).isEmpty())
    }

    @Test
    fun `已过期的任务在之后的任何一天都看得见`() {
        // 漏做一次就再也找不回来，是最糟的失败方式
        val t = span("late", from = today - 5, to = today - 1)
        listOf(today, today + 3, today + 30).forEach { day ->
            val overdue = TaskGrouper.group(listOf(t), day, today)
                .firstOrNull { it.id == TaskGrouper.GROUP_OVERDUE }
            assertEquals("第 $day 天应当能看到过期任务", listOf("late"), overdue?.tasks?.map { it.id })
        }
    }

    @Test
    fun `选中过去的某天时 截止更晚的任务不算过期`() {
        val t = span("a", from = today - 10, to = today + 2)
        val groups = TaskGrouper.group(listOf(t), today - 6, today)
        assertFalse(groups.any { it.id == TaskGrouper.GROUP_OVERDUE })
        assertEquals(listOf("a"), groups.first { it.id == TaskGrouper.GROUP_ACTIVE }.tasks.map { it.id })
    }

    @Test
    fun `未安排的任务不随日期切换消失`() {
        // 它是拖拽的来源，跟着日期藏起来就没法把它拖到别的日期上了
        val t = task("none")
        listOf(today - 3, today, today + 9).forEach { day ->
            val group = TaskGrouper.group(listOf(t), day, today)
                .firstOrNull { it.id == TaskGrouper.GROUP_UNSCHEDULED }
            assertEquals(listOf("none"), group?.tasks?.map { it.id })
        }
    }

    @Test
    fun `已完成的任务只出现在完成那一天`() {
        val t = doneOn("done", today - 1)
        assertEquals(
            listOf("done"),
            TaskGrouper.group(listOf(t), today - 1, today)
                .first { it.id == TaskGrouper.GROUP_DONE }.tasks.map { it.id },
        )
        // 昨天做完的，今天不该再出现在任何组里
        assertTrue(TaskGrouper.group(listOf(t), today, today).isEmpty())
    }

    @Test
    fun `已完成的任务不再进入要做或过期组`() {
        val t = task("a", startDay = today - 3, endDay = today - 1, done = true, completedAt = DayMath.localMillisAt(today - 1) + 1000)
        val groups = TaskGrouper.group(listOf(t), today, today)
        assertFalse(groups.any { it.id == TaskGrouper.GROUP_OVERDUE })
        assertFalse(groups.any { it.id == TaskGrouper.GROUP_ACTIVE })
    }

    @Test
    fun `空列表不产生任何分组`() {
        assertTrue(TaskGrouper.group(emptyList(), today, today).isEmpty())
    }

    @Test
    fun `这一天没有内容时不产生任何分组`() {
        // 任务是明天的，今天什么都不该有
        val t = span("a", from = today + 1, to = today + 1)
        assertTrue(TaskGrouper.group(listOf(t), today, today).isEmpty())
    }

    // ------------------------------------------------------ 组内排序

    @Test
    fun `要做组按手动权重排 截止日不再参与`() {
        // 权重和截止日故意反着给：如果还按「截止日升序」排，结果会是 near-later 在前。
        // 改成手动排序之后，截止日只作为卡片上的一个标签，不再决定位置
        val tasks = listOf(
            span("far-first", to = today + 5, createdAt = 1).copy(order = 900L),
            span("near-later", to = today + 1, createdAt = 2).copy(order = 100L),
        )
        val group = TaskGrouper.group(tasks, today, today).first { it.id == TaskGrouper.GROUP_ACTIVE }
        assertEquals(listOf("far-first", "near-later"), group.tasks.map { it.id })
    }

    @Test
    fun `权重相同时新建的排前面`() {
        // order 默认等于 createdAt，所以没手动拖过的列表就是「新的在上」
        val tasks = listOf(
            span("older", to = today + 1, createdAt = 1),
            span("newer", to = today + 1, createdAt = 2),
        )
        val group = TaskGrouper.group(tasks, today, today).first { it.id == TaskGrouper.GROUP_ACTIVE }
        assertEquals(listOf("newer", "older"), group.tasks.map { it.id })
    }

    @Test
    fun `已过期组同样按手动权重排`() {
        val tasks = listOf(
            span("late-deadline", to = today - 1, createdAt = 1).copy(order = 900L),
            span("early-deadline", to = today - 5, createdAt = 2).copy(order = 100L),
        )
        val group = TaskGrouper.group(tasks, today, today).first { it.id == TaskGrouper.GROUP_OVERDUE }
        assertEquals(listOf("late-deadline", "early-deadline"), group.tasks.map { it.id })
    }

    @Test
    fun `未安排组按创建时间倒序 新记的排前面`() {
        val tasks = listOf(
            task("old", createdAt = 100),
            task("new", createdAt = 300),
            task("mid", createdAt = 200),
        )
        val group = TaskGrouper.group(tasks, today, today).single()
        assertEquals(listOf("new", "mid", "old"), group.tasks.map { it.id })
    }

    @Test
    fun `已完成组按完成时刻倒序 刚完成的排前面`() {
        val tasks = listOf(
            doneOn("early", today, atHourOffset = 8),
            doneOn("late", today, atHourOffset = 20),
        )
        val group = TaskGrouper.group(tasks, today, today).single()
        assertEquals(listOf("late", "early"), group.tasks.map { it.id })
    }

    // ------------------------------------------------------ 强调语义

    @Test
    fun `选中今天时要做组带今天强调 其它日期不带`() {
        val t = span("a", to = today + 1)
        val onToday = TaskGrouper.group(listOf(t), today, today)
            .first { it.id == TaskGrouper.GROUP_ACTIVE }
        assertEquals(GroupAccent.TODAY, onToday.accent)
        assertEquals("今天要做", onToday.label)

        val onTomorrow = TaskGrouper.group(listOf(t), today + 1, today)
            .first { it.id == TaskGrouper.GROUP_ACTIVE }
        assertEquals(GroupAccent.NORMAL, onTomorrow.accent)
        assertEquals("要做", onTomorrow.label)
    }

    @Test
    fun `过期组用过期强调并有说明`() {
        val group = TaskGrouper.group(
            listOf(span("late", to = today - 1, from = today - 3)),
            today,
            today,
        ).first()
        assertEquals(GroupAccent.OVERDUE, group.accent)
        assertEquals("已过期", group.label)
        assertTrue(group.hint != null)
    }

    // ------------------------------------------------------ 角标

    @Test
    fun `从今天到截止日 每一天都有角标`() {
        val t = span("a", from = today, to = today + 3)
        val days = (today..today + 3).toList()
        val counts = TaskGrouper.dayCounts(listOf(t), days)
        days.forEach { day ->
            assertEquals("第 $day 天应当有角标", 1, counts[day])
        }
    }

    @Test
    fun `开始日之前没有角标`() {
        val t = span("a", from = today + 2, to = today + 4)
        val counts = TaskGrouper.dayCounts(listOf(t), (today - 2..today + 6).toList())
        assertEquals(null, counts[today])
        assertEquals(null, counts[today + 1])
        assertEquals(1, counts[today + 2])
    }

    @Test
    fun `截止日之后角标熄灭`() {
        val t = span("a", from = today, to = today + 2)
        val counts = TaskGrouper.dayCounts(listOf(t), (today..today + 5).toList())
        assertEquals(1, counts[today + 2])
        assertEquals(null, counts[today + 3])
    }

    @Test
    fun `角标数字是当天要做且未完成的件数`() {
        val tasks = listOf(
            span("a", to = today + 2),
            span("b", to = today + 2),
            span("c", from = today + 1, to = today + 5),
            task("d", done = true, startDay = today, endDay = today, completedAt = DayMath.localMillisAt(today) + 1000),
            task("e"),
        )
        val counts = TaskGrouper.dayCounts(tasks, (today..today + 2).toList())
        // 今天：a、b（c 还没开始、d 已完成、e 未安排）
        assertEquals(2, counts[today])
        // 明天起 c 也进来了
        assertEquals(3, counts[today + 1])
        assertEquals(3, counts[today + 2])
    }

    @Test
    fun `已完成任务不产生角标`() {
        val t = doneOn("a", today)
        assertTrue(TaskGrouper.dayCounts(listOf(t), listOf(today)).isEmpty())
    }

    @Test
    fun `未安排的任务不产生角标`() {
        assertTrue(TaskGrouper.dayCounts(listOf(task("a")), listOf(today)).isEmpty())
    }

    @Test
    fun `只给传入的那些天算角标`() {
        // 日期条之外的日子不用算，避免白算一堆永远不会显示的格子
        val t = span("a", from = today, to = today + 9)
        val counts = TaskGrouper.dayCounts(listOf(t), listOf(today, today + 3))
        assertEquals(setOf(today, today + 3), counts.keys)
    }

    // ------------------------------------------------------ 首页入口角标

    @Test
    fun `入口角标把已过期的也算上`() {
        val tasks = listOf(
            span("today", from = today, to = today),
            span("overdue", from = today - 4, to = today - 2),
        )
        // 与日期条角标刻意不同：今天那格只算 1 件，入口要算 2 件
        assertEquals(1, TaskGrouper.dayCounts(tasks, listOf(today))[today])
        assertEquals(2, TaskGrouper.pendingCount(tasks, today))
    }

    @Test
    fun `入口角标不算还没开始的 也不算未安排的`() {
        val tasks = listOf(
            span("future", from = today + 2, to = today + 5),
            task("none"),
        )
        assertEquals(0, TaskGrouper.pendingCount(tasks, today))
    }

    @Test
    fun `入口角标不数已完成的`() {
        val tasks = listOf(
            doneOn("doneToday", today),
            task(
                "doneOverdue",
                startDay = today - 3,
                endDay = today - 1,
                done = true,
                completedAt = DayMath.localMillisAt(today - 1) + 1_000,
            ),
            span("open", from = today, to = today),
        )
        assertEquals(1, TaskGrouper.pendingCount(tasks, today))
    }

    @Test
    fun `入口角标只要没做完 过期之后再久也仍然算欠着`() {
        val tasks = listOf(span("a", from = today - 6, to = today - 4))
        assertEquals(1, TaskGrouper.pendingCount(tasks, today))
        assertEquals(1, TaskGrouper.pendingCount(tasks, today + 30))
    }

    /**
     * 这条是**接口契约**：入口角标的数字必须等于任务页上
     * 「已过期」+「今天要做」两组之和。用户点进去数的条数要和首页看到的一致，
     * 否则两个数字互相打脸。判据若从「并集」改成等价但易漂移的写法，这条会拦住。
     */
    @Test
    fun `入口角标恒等于已过期加今天要做两组之和`() {
        val tasks = listOf(
            span("a", from = today, to = today + 2),
            span("b", from = today - 5, to = today - 3),
            span("c", from = today - 1, to = today + 1),
            span("d", from = today + 3, to = today + 6),
            task("e"),
            doneOn("f", today),
        )
        val groups = TaskGrouper.group(tasks, selectedDay = today, today = today)
        val visible = groups
            .filter { it.id == TaskGrouper.GROUP_OVERDUE || it.id == TaskGrouper.GROUP_ACTIVE }
            .sumOf { it.tasks.size }

        assertEquals(3, visible) // a、b、c
        assertEquals(visible, TaskGrouper.pendingCount(tasks, today))
    }

    // ------------------------------------------------------ 日期条范围

    @Test
    fun `没有任务时日期条是默认的连续区间`() {
        val days = TaskGrouper.stripDays(emptyList(), today)
        assertEquals(today - TaskGrouper.BACK_DAYS, days.first())
        assertEquals(today + TaskGrouper.FORWARD_DAYS, days.last())
        assertEquals((TaskGrouper.BACK_DAYS + TaskGrouper.FORWARD_DAYS + 1).toInt(), days.size)
    }

    @Test
    fun `日期条是连续的 不跳日`() {
        val days = TaskGrouper.stripDays(listOf(span("a", to = today + 40)), today)
        days.zipWithNext().forEach { (a, b) -> assertEquals(a + 1, b) }
    }

    @Test
    fun `截止日超出默认区间会把日期条撑开`() {
        val far = today + 60
        val days = TaskGrouper.stripDays(listOf(span("a", to = far)), today)
        assertTrue(days.contains(far))
        assertEquals(far, days.last())
    }

    @Test
    fun `开始日在过去很远也会把日期条撑开`() {
        // 否则任务是从更早的那天开始的，日期条上却找不到那段
        val far = today - 60
        val days = TaskGrouper.stripDays(listOf(span("a", from = far, to = today)), today)
        assertEquals(far, days.first())
    }

    @Test
    fun `日期条最多撑到上限 防止极端日期把格子刷爆`() {
        val days = TaskGrouper.stripDays(listOf(span("a", to = today + 100_000)), today)
        assertEquals(today + TaskGrouper.MAX_SPAN_DAYS, days.last())
    }

    @Test
    fun `已完成任务不会撑开日期条`() {
        // 否则用久了日期条会被历史任务越拉越长
        val tasks = listOf(
            task(
                id = "a",
                startDay = today + 100,
                endDay = today + 100,
                done = true,
                completedAt = DayMath.localMillisAt(today) + 1000,
            )
        )
        val days = TaskGrouper.stripDays(tasks, today)
        assertEquals(today + TaskGrouper.FORWARD_DAYS, days.last())
    }
}
