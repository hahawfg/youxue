package com.youxue.app.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WordParser] 单元测试。
 *
 * 这些是纯逻辑，不依赖 Android 框架，可以直接 `./gradlew testDebugUnitTest` 跑。
 */
class WordParserTest {

    // ---------------------------------------------------------- 解析

    @Test
    fun `按行解析词语`() {
        val result = WordParser.parse("苹果\n蓝天\n小朋友")
        assertEquals(listOf("苹果", "蓝天", "小朋友"), result)
    }

    @Test
    fun `跳过空行与纯空白行`() {
        val result = WordParser.parse("苹果\n\n  \n蓝天\n\u3000\n\n小朋友\n")
        assertEquals(listOf("苹果", "蓝天", "小朋友"), result)
    }

    @Test
    fun `去除首尾空白`() {
        val result = WordParser.parse("  苹果  \n\t蓝天\t")
        assertEquals(listOf("苹果", "蓝天"), result)
    }

    @Test
    fun `去除阿拉伯数字序号前缀`() {
        assertEquals(listOf("苹果", "蓝天"), WordParser.parse("1. 苹果\n2、蓝天"))
        assertEquals(listOf("苹果", "蓝天"), WordParser.parse("1.苹果\n2)蓝天"))
        assertEquals(listOf("苹果", "蓝天"), WordParser.parse("(1) 苹果\n（2）蓝天"))
        assertEquals(listOf("苹果"), WordParser.parse("1: 苹果"))
    }

    @Test
    fun `去除中文数字序号前缀`() {
        assertEquals(listOf("苹果", "蓝天"), WordParser.parse("一、苹果\n二、蓝天"))
        assertEquals(listOf("苹果"), WordParser.parse("三. 苹果"))
    }

    @Test
    fun `按行分隔时不拆分内部空格`() {
        // 像「白日依山尽 黄河入海流」这样的整句，内部空格要保留
        val result = WordParser.parse("白日依山尽 黄河入海流")
        assertEquals(listOf("白日依山尽 黄河入海流"), result)
    }

    @Test
    fun `超长行被截断`() {
        val long = "一".repeat(100)
        val result = WordParser.parse(long)
        assertEquals(WordParser.MAX_WORD_LENGTH, result.first().length)
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(WordParser.parse("").isEmpty())
        assertTrue(WordParser.parse("   ").isEmpty())
        assertTrue(WordParser.parse("\n\n\n").isEmpty())
    }

    @Test
    fun `纯序号行不会被当成词语`() {
        // "1." 这类只有序号没内容的行，去掉序号后为空，应被丢弃
        assertEquals(listOf("苹果"), WordParser.parse("1.\n苹果"))
        assertEquals(listOf("苹果"), WordParser.parse("苹果\n2、\n\n"))
        assertEquals(listOf("苹果", "蓝天"), WordParser.parse("(1)\n苹果\n二、\n蓝天"))
    }

    @Test
    fun `不误剥含有数字的词语`() {
        // "2024年" 里的 2024 是词的一部分，不是序号
        assertEquals(listOf("2024年"), WordParser.parse("2024年"))
        assertEquals(listOf("一二三"), WordParser.parse("一二三"))
        // 没有分隔符的纯数字也保留
        assertEquals(listOf("12345"), WordParser.parse("12345"))
    }

    @Test
    fun `兼容 CRLF 换行`() {
        assertEquals(listOf("苹果", "蓝天"), WordParser.parse("苹果\r\n蓝天\r\n"))
    }

    // ---------------------------------------------------- 间隔计算

    @Test
    fun `间隔按字数线性计算`() {
        // 2 字 × 1.5s = 3s
        assertEquals(3000L, WordParser.intervalMillis("苹果", 1.5f, 1f, 60f))
        // 4 字 × 1.5s = 6s
        assertEquals(6000L, WordParser.intervalMillis("中华人民共和国".take(4), 1.5f, 1f, 60f))
    }

    @Test
    fun `低于最小间隔时取最小值`() {
        // 1 字 × 0.5s = 0.5s，但最小间隔 2s
        assertEquals(2000L, WordParser.intervalMillis("日", 0.5f, 2f, 60f))
    }

    @Test
    fun `超过最大间隔时取最大值`() {
        // 10 字 × 5s = 50s，但最大间隔 20s
        assertEquals(20000L, WordParser.intervalMillis("一".repeat(10), 5f, 1f, 20f))
    }

    @Test
    fun `最小最大值颠倒时自动纠正`() {
        // 传入 min > max，函数内部应自行交换，不能返回异常值
        val r = WordParser.intervalMillis("苹果", 1.5f, 30f, 5f)
        assertEquals(5000L, r)
    }

    @Test
    fun `间隔计算忽略空白字符`() {
        // "白 日" 含空格，算 2 个可见字
        assertEquals(3000L, WordParser.intervalMillis("白 日", 1.5f, 1f, 60f))
    }

    @Test
    fun `间隔不会低于下限500毫秒`() {
        assertTrue(WordParser.intervalMillis("日", 0.01f, 0f, 60f) >= 500L)
    }

    @Test
    fun `预估总时长等于各词间隔之和`() {
        val words = listOf("苹果", "蓝天", "小朋友")
        val expected = words.sumOf { WordParser.intervalMillis(it, 1.5f, 1.5f, 20f) }
        assertEquals(expected, WordParser.estimatedTotalMillis(words, 1.5f, 1.5f, 20f))
    }

    @Test
    fun `计数与解析结果一致`() {
        assertEquals(3, WordParser.countWords("苹果\n\n蓝天\n1. 小朋友"))
    }
}
