package cn.com.toobug.youxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务编解码的往返测试。
 *
 * 重点在「任何用户输入都不能破坏结构」——标题和内容里出现竖线、换行、
 * 反斜杠都是完全可能的（粘贴一段带格式的文字就会带进来），
 * 一旦漏转义，后果是任务列表被切碎、后续所有记录都错位。
 *
 * 另一组重点是**旧格式兼容**：日期从「一个」变成「一段」时多了一个字段，
 * 用户设备上已经存着旧数据，读不进来就是任务凭空消失。
 */
class TaskCodecTest {

    private fun task(
        id: String = "id-1",
        title: String = "听写第三单元",
        content: String = "苹果、蓝天",
        startDay: Long? = null,
        endDay: Long? = null,
        done: Boolean = false,
        createdAt: Long = 1_700_000_000_000L,
        completedAt: Long = 0L,
    ) = TaskItem(id, title, content, startDay, endDay, done, createdAt, completedAt)

    private fun roundTrip(vararg tasks: TaskItem): List<TaskItem> =
        TaskCodec.decode(TaskCodec.encode(tasks.toList()))

    @Test
    fun `空列表编码为空串再解回空列表`() {
        assertEquals("", TaskCodec.encode(emptyList()))
        assertTrue(TaskCodec.decode("").isEmpty())
    }

    @Test
    fun `普通任务完整往返`() {
        val original = task(
            startDay = 20_660L,
            endDay = 20_670L,
            done = true,
            completedAt = 1_700_000_500_000L,
        )
        assertEquals(listOf(original), roundTrip(original))
    }

    @Test
    fun `未安排的任务起止日都是 null`() {
        val restored = roundTrip(task()).single()
        assertEquals(null, restored.startDay)
        assertEquals(null, restored.endDay)
    }

    @Test
    fun `开始日与截止日分别往返 不会串位`() {
        // 两个日期字段相邻，编码顺序写反了会非常难发现——值都合法，只是换了个含义
        val original = task(id = "x", startDay = 20_000L, endDay = 20_005L)
        val restored = roundTrip(original).single()
        assertEquals(20_000L, restored.startDay)
        assertEquals(20_005L, restored.endDay)
    }

    @Test
    fun `多条的先后顺序保持不变`() {
        val list = listOf(
            task(id = "a", title = "一"),
            task(id = "b", title = "二"),
            task(id = "c", title = "三"),
        )
        assertEquals(listOf("a", "b", "c"), roundTrip(*list.toTypedArray()).map { it.id })
    }

    @Test
    fun `标题里的竖线不会撑破字段`() {
        val original = task(title = "听写|第三单元", content = "a|b|c")
        val restored = roundTrip(original).single()
        assertEquals("听写|第三单元", restored.title)
        assertEquals("a|b|c", restored.content)
    }

    @Test
    fun `内容里的换行不会撑破记录`() {
        val original = task(content = "第一行\n第二行\n第三行")
        val restored = roundTrip(original).single()
        assertEquals("第一行\n第二行\n第三行", restored.content)
    }

    @Test
    fun `内容里的回车不会撑破记录`() {
        // Windows 下粘贴文本很容易带进来 \r\n
        val original = task(content = "第一行\r\n第二行")
        assertEquals("第一行\r\n第二行", roundTrip(original).single().content)
    }

    @Test
    fun `反斜杠本身能正确往返`() {
        // 反斜杠是转义引导符，最容易在「转义了但没转义转义符」上出错
        val tricky = """C:\Users\路径|含竖线"""
        val original = task(title = tricky, content = """末尾反斜杠\""")
        val restored = roundTrip(original).single()
        assertEquals(tricky, restored.title)
        assertEquals("""末尾反斜杠\""", restored.content)
    }

    @Test
    fun `转义符后面跟着普通字母不会被误解析`() {
        // "\\q" 里的 q 不是合法转义码，应当按字面量还原
        val original = task(title = """\q\normal""")
        assertEquals("""\q\normal""", roundTrip(original).single().title)
    }

    @Test
    fun `空内容能保留`() {
        assertEquals("", roundTrip(task(content = "")).single().content)
    }

    @Test
    fun `中文标点与表情符号能往返`() {
        val original = task(title = "背古诗《静夜思》", content = "注意「疑是地上霜」🌙")
        val restored = roundTrip(original).single()
        assertEquals("背古诗《静夜思》", restored.title)
        assertEquals("注意「疑是地上霜」🌙", restored.content)
    }

    @Test
    fun `首尾空格不被吞掉`() {
        val original = task(title = "  缩进的标题  ")
        assertEquals("  缩进的标题  ", roundTrip(original).single().title)
    }

    @Test
    fun `字段数量不对的坏行被跳过`() {
        val good = TaskCodec.encode(listOf(task(id = "good")))
        val mixed = "$good\n这是一行被人手改坏的文本\n${TaskCodec.encode(listOf(task(id = "ok")))}"
        assertEquals(listOf("good", "ok"), TaskCodec.decode(mixed).map { it.id })
    }

    @Test
    fun `id 或标题为空的记录被丢弃`() {
        // 手工拼一条标题为空的记录：id||内容| | |0|0|0
        assertTrue(TaskCodec.decode("some-id||内容|||0|0|0").isEmpty())
    }

    @Test
    fun `尾部空行不影响结果`() {
        val encoded = TaskCodec.encode(listOf(task())) + "\n"
        assertEquals(1, TaskCodec.decode(encoded).size)
    }

    @Test
    fun `日期字段非数字时按未安排处理而不是丢弃整条`() {
        // 日期坏掉属于可降级信息：任务本身还在，显示成未安排即可
        val restored = TaskCodec.decode("a|标题||坏日期|坏日期|0|1|0").singleOrNull()
        assertEquals("标题", restored?.title)
        assertEquals(null, restored?.endDay)
    }

    // ------------------------------------------------------ 旧格式兼容

    @Test
    fun `旧的七字段记录仍能读出来`() {
        // 旧格式：id | 标题 | 内容 | 日期 | 完成 | 创建时刻 | 完成时刻
        val legacy = "old-1|背单词|每天十个|20670|0|1700000000000|0"
        val restored = TaskCodec.decode(legacy).single()
        assertEquals("old-1", restored.id)
        assertEquals("背单词", restored.title)
        // 旧数据只有一个日期，按「那一天当天要做」还原，语义与升级前一致
        assertEquals(20_670L, restored.startDay)
        assertEquals(20_670L, restored.endDay)
    }

    @Test
    fun `旧格式的完成状态与时刻一并保留`() {
        val legacy = "old-2|写日记||20660|1|1700000000000|1700000500000"
        val restored = TaskCodec.decode(legacy).single()
        assertTrue(restored.done)
        assertEquals(1_700_000_500_000L, restored.completedAt)
    }

    @Test
    fun `只有截止日没有开始日的坏记录按当天要做兜底`() {
        // 这种记录在区间语义下哪一天都不会出现（等于任务消失了），必须纠正
        val restored = TaskCodec.decode("a|标题|||20670|0|1|0").single()
        assertEquals(20_670L, restored.startDay)
        assertEquals(20_670L, restored.endDay)
    }

    @Test
    fun `开始日晚于截止日的坏记录被夹到截止日`() {
        val restored = TaskCodec.decode("a|标题||20700|20670|0|1|0").single()
        assertEquals(20_670L, restored.startDay)
        assertEquals(20_670L, restored.endDay)
    }

    // ------------------------------------------------------ 紧急度与排序

    @Test
    fun `紧急度与排序权重完整往返`() {
        val original = task().copy(priority = TaskPriority.URGENT, order = 123_456_789L)
        val restored = roundTrip(original).single()
        assertEquals(TaskPriority.URGENT, restored.priority)
        assertEquals(123_456_789L, restored.order)
    }

    @Test
    fun `八字段旧记录补上默认紧急度与排序`() {
        // 加紧急度之前那版：id|标题|内容|开始日|截止日|完成|创建|完成时刻
        val v2 = "v2-1|背单词|每天十个|20670|20680|0|1700000000000|0"
        val restored = TaskCodec.decode(v2).single()
        assertEquals(TaskPriority.NORMAL, restored.priority)
        // 排序回落到创建时刻，等价于「老数据仍按新旧排」
        assertEquals(1_700_000_000_000L, restored.order)
    }

    @Test
    fun `不认识的紧急度按普通处理而不是丢掉整条`() {
        // 枚举名改过、或者文件被手工改坏，都不能让任务消失
        val restored = TaskCodec.decode("a|标题|||20670|0|1|0|SOMEDAY|5").single()
        assertEquals(TaskPriority.NORMAL, restored.priority)
    }

    @Test
    fun `排序列坏掉时回落到创建时刻`() {
        val restored = TaskCodec.decode("a|标题|||20670|0|1700000000000|0|HIGH|坏数字").single()
        assertEquals(TaskPriority.HIGH, restored.priority)
        assertEquals(1_700_000_000_000L, restored.order)
    }
}
