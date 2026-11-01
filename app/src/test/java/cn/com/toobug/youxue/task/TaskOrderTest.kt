package cn.com.toobug.youxue.task

import cn.com.toobug.youxue.data.TaskItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动排序的换算。
 *
 * 这组用例盯的是「拖完之后其它东西有没有被带歪」——排序出错时界面不会报错，
 * 只是任务位置莫名其妙地跳，用户下次进来还可能再跳一次，很难靠肉眼复现。
 */
class TaskOrderTest {

    private fun task(id: String, order: Long, createdAt: Long = order) = TaskItem(
        id = id,
        title = id,
        createdAt = createdAt,
        order = order,
    )

    private fun idsOf(tasks: List<TaskItem>): List<String> =
        TaskOrder.sorted(tasks).map { it.id }

    @Test
    fun `默认按权重从大到小排`() {
        val tasks = listOf(task("a", 1_000L), task("b", 3_000L), task("c", 2_000L))
        assertEquals(listOf("b", "c", "a"), idsOf(tasks))
    }

    @Test
    fun `权重相同时按创建时刻兜底 不会每次顺序都不一样`() {
        val tasks = listOf(
            task("old", order = 5_000L, createdAt = 1_000L),
            task("new", order = 5_000L, createdAt = 2_000L),
        )
        assertEquals(listOf("new", "old"), idsOf(tasks))
    }

    @Test
    fun `新任务的权重比现有的都大`() {
        val now = 1_700_000_000_000L
        val existing = listOf(task("a", 5_000L), task("b", 1_000L))
        assertTrue(TaskOrder.newOrder(existing, now) > 5_000L)
    }

    @Test
    fun `现存权重比当前时刻还大时 新任务仍然排最前面`() {
        val now = 1_000L
        val existing = listOf(task("huge", 9_999_999L))
        assertEquals(10_000_000L, TaskOrder.newOrder(existing, now))
    }

    @Test
    fun `往下拖一位`() {
        val a = task("a", 3_000L)
        val b = task("b", 2_000L)
        val c = task("c", 1_000L)
        // 把 a 放到 b 之后（去掉 a 之后在第 1 位插入）
        val moved = TaskOrder.move(listOf(a, b, c), listOf("a", "b", "c"), "a", 1)
        assertEquals(listOf("b", "a", "c"), idsOf(moved))
    }

    @Test
    fun `拖到最上面`() {
        val a = task("a", 3_000L)
        val b = task("b", 2_000L)
        val c = task("c", 1_000L)
        val moved = TaskOrder.move(listOf(a, b, c), listOf("a", "b", "c"), "c", 0)
        assertEquals(listOf("c", "a", "b"), idsOf(moved))
    }

    @Test
    fun `拖到最下面`() {
        val a = task("a", 3_000L)
        val b = task("b", 2_000L)
        val c = task("c", 1_000L)
        val moved = TaskOrder.move(listOf(a, b, c), listOf("a", "b", "c"), "a", 2)
        assertEquals(listOf("b", "c", "a"), idsOf(moved))
    }

    @Test
    fun `拖回原位不产生新列表`() {
        val tasks = listOf(task("a", 3_000L), task("b", 2_000L), task("c", 1_000L))
        // 去掉 a 之后仍在第 0 位插入 = 原地不动
        val moved = TaskOrder.move(tasks, listOf("a", "b", "c"), "a", 0)
        assertSame(tasks, moved)
    }

    @Test
    fun `不在这一组里的任务原样返回`() {
        val tasks = listOf(task("a", 3_000L), task("b", 2_000L))
        val moved = TaskOrder.move(tasks, listOf("a", "b"), "missing", 0)
        assertSame(tasks, moved)
    }

    @Test
    fun `只有一条的组拖不出变化`() {
        val tasks = listOf(task("a", 3_000L))
        assertSame(tasks, TaskOrder.move(tasks, listOf("a"), "a", 0))
    }

    @Test
    fun `重排只影响本组 别的组之间的先后不变`() {
        // a、b 属于同一组（比如「今天要做」），x、y 属于别的组，
        // 它们在全局序列里是散开的——重排不能把 x、y 的相对位置也翻掉
        val a = task("a", 5_000L)
        val x = task("x", 4_500L)
        val b = task("b", 4_000L)
        val y = task("y", 3_500L)
        val all = listOf(a, x, b, y)

        val moved = TaskOrder.move(all, listOf("a", "b"), "a", 1)
        assertEquals(listOf("b", "x", "a", "y"), idsOf(moved))
    }

    @Test
    fun `重排后权重仍然全局唯一`() {
        val a = task("a", 5_000L)
        val b = task("b", 2_000L)
        val c = task("c", 1_000L)
        val moved = TaskOrder.move(listOf(a, b, c), listOf("a", "b", "c"), "c", 0)
        assertEquals(moved.size, moved.map { it.order }.distinct().size)
    }
}
