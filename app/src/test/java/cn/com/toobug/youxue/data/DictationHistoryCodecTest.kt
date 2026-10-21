package cn.com.toobug.youxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 听写历史编解码测试。
 *
 * 重点在两处：**变长词表**（一次听写几个词不固定）能不能原样往返，
 * 以及坏数据容不容得下——历史是只增的，一条读不出来就该丢一条，
 * 绝不能因为一条坏记录让整份列表打不开。
 */
class DictationHistoryCodecTest {

    private fun record(
        at: Long = 1_700_000_000_000L,
        words: List<String> = listOf("苹果", "香蕉"),
        elapsed: Long = 65_000L,
    ) = DictationRecord(completedAt = at, words = words, elapsedMillis = elapsed)

    @Test
    fun `单条记录往返一致`() {
        val list = listOf(record())
        val decoded = DictationHistoryCodec.decode(DictationHistoryCodec.encode(list))
        assertEquals(list, decoded)
    }

    @Test
    fun `多条记录往返保持顺序`() {
        val list = listOf(
            record(at = 100L, words = listOf("一")),
            record(at = 200L, words = listOf("二", "三")),
            record(at = 300L, words = listOf("四")),
        )
        val decoded = DictationHistoryCodec.decode(DictationHistoryCodec.encode(list))
        assertEquals(list, decoded)
    }

    @Test
    fun `词表长度不同也能正确往返`() {
        // 变长是本格式最容易写错的地方：词表占满剩余字段，
        // 如果解析时按固定字段数切，短记录和长记录必有一个读错
        val list = listOf(
            record(words = listOf("a")),
            record(words = (1..20).map { "词$it" }),
        )
        val decoded = DictationHistoryCodec.decode(DictationHistoryCodec.encode(list))
        assertEquals(list, decoded)
        assertEquals(1, decoded[0].wordCount)
        assertEquals(20, decoded[1].wordCount)
    }

    @Test
    fun `词语里的竖线和换行不会破坏结构`() {
        val list = listOf(
            record(words = listOf("带|竖线的词", "带\n换行的词", "反斜杠\\也算", "正常词")),
        )
        val decoded = DictationHistoryCodec.decode(DictationHistoryCodec.encode(list))
        assertEquals(list, decoded)
        assertEquals(4, decoded.single().wordCount)
    }

    @Test
    fun `空输入解出空列表`() {
        assertTrue(DictationHistoryCodec.decode("").isEmpty())
    }

    @Test
    fun `坏行丢弃而不是抛异常`() {
        val raw = listOf(
            "不是数字|1000|苹果",
            "1700000000000|1000",            // 没有词
            "",                               // 空行
            "1700000000000|1000|好词",        // 唯一合法的一行
        ).joinToString("\n")

        val decoded = DictationHistoryCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(listOf("好词"), decoded.single().words)
    }

    @Test
    fun `用时缺失按零处理但仍保留记录`() {
        // 用时不是关键词，缺了不该让整条记录消失
        val decoded = DictationHistoryCodec.decode("1700000000000||苹果")
        assertEquals(1, decoded.size)
        assertEquals(0L, decoded.single().elapsedMillis)
        assertEquals(listOf("苹果"), decoded.single().words)
    }

    @Test
    fun `负用时被夹到零`() {
        val decoded = DictationHistoryCodec.decode("1700000000000|-500|苹果")
        assertEquals(0L, decoded.single().elapsedMillis)
    }

    @Test
    fun `trim 只保留最近若干条`() {
        val list = (1..10).map { record(at = it.toLong(), words = listOf("词$it")) }
        val trimmed = DictationHistoryCodec.trim(list, limit = 3)
        assertEquals(3, trimmed.size)
        // 保留最近的那几条
        assertEquals(listOf("词8", "词9", "词10"), trimmed.map { it.words.single() })
    }

    @Test
    fun `trim 对不超限的列表原样返回`() {
        val list = listOf(record(at = 1L), record(at = 2L))
        assertEquals(list, DictationHistoryCodec.trim(list, limit = 50))
    }
}
