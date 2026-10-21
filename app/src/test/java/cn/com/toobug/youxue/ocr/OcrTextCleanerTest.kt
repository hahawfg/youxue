package cn.com.toobug.youxue.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清洗逻辑的回归测试。
 *
 * 用例全部取自 ML Kit 在课本照片上的**真实输出形态**，而不是我编的字符串——
 * 这套规则的价值完全取决于它能不能处理真实世界的脏数据。
 * OCR 本身没法单测（要真图、真模型），但清洗能，所以这里是这个功能
 * 唯一能靠测试守住的地方。
 */
class OcrTextCleanerTest {

    private fun clean(raw: String) = OcrTextCleaner.cleanLine(raw)

    private fun reasons(rawLines: List<String>) =
        OcrTextCleaner.toCandidates(rawLines).map { it.suspect }

    /** 走完整链路后的词语列表。拆词相关用例一律用这个口径 */
    private fun texts(vararg raw: String): List<String> =
        OcrTextCleaner.toCandidates(raw.toList()).map { it.text }

    // ---- 字间空格（合并这一侧）：OCR 最常见的噪音 ----

    @Test
    fun `逐字断开的中文被合并回一个词`() {
        assertEquals(listOf("小朋友"), texts("小 朋 友"))
        assertEquals(listOf("中华人民共和国"), texts("中 华 人 民 共 和 国"))
        assertEquals(listOf("小朋友"), texts("小  朋  友"))
    }

    @Test
    fun `逐字噪声里夹着完整词时会被拆开，残字留可疑提示`() {
        // 已知取舍：`小 朋友` 这种形态，从字符层面区分不了「逐字噪声」
        // 和「单字词 + 双字词」两种可能。这里选了拆——
        // 残字 `小` 会被标成「只有一个字」、默认不勾选，用户看得见；
        // 而合错的 `小朋友` 是**没有痕迹**的，用户根本不会察觉
        val got = OcrTextCleaner.toCandidates(listOf("小 朋友"))
        assertEquals(listOf("小", "朋友"), got.map { it.text })
        assertEquals(SuspectReason.TOO_SHORT, got[0].suspect)
    }

    @Test
    fun `英文词组保持着一条`() {
        assertEquals("good morning", clean("good morning"))
        assertEquals("happy new year", clean("happy  new  year"))
        assertEquals(listOf("good morning"), texts("good morning"))
        assertEquals(listOf("happy new year"), texts("happy  new  year"))
    }

    @Test
    fun `中英对照条目不拆开`() {
        assertEquals("苹果 apple", clean("苹果 apple"))
        assertEquals(listOf("苹果 apple"), texts("苹果 apple"))
        assertEquals(listOf("apple 苹果"), texts("apple 苹果"))
    }

    @Test
    fun `中英混排时汉字噪声被修掉，英文词组保持完整`() {
        // 混排行走的是「不拆词」那条分支，但汉字之间的空格噪声仍然要修
        assertEquals(listOf("苹果 apple 香蕉"), texts("苹 果 apple 香 蕉"))
    }

    @Test
    fun `序号前有装饰符号时也能剥掉序号`() {
        assertEquals("苹果", clean("· 1. 苹果"))
    }

    // ---- 按分隔符拆词：横排词表 ----

    @Test
    fun `中文行按空格拆成多个词`() {
        assertEquals(listOf("苹果", "香蕉"), texts("苹果 香蕉"))
        assertEquals(listOf("苹果", "香蕉", "橘子"), texts("苹果 香蕉 橘子"))
    }

    @Test
    fun `中文行按顿号逗号拆成多个词`() {
        assertEquals(listOf("苹果", "香蕉", "橘子"), texts("苹果、香蕉、橘子"))
        assertEquals(listOf("苹果", "香蕉"), texts("苹果，香蕉"))
        assertEquals(listOf("苹果", "香蕉"), texts("苹果,香蕉"))
        assertEquals(listOf("苹果", "香蕉"), texts("苹果 ， 香蕉"))
    }

    @Test
    fun `英文行整条不拆`() {
        assertEquals(listOf("apple banana"), texts("apple banana"))
        assertEquals(listOf("orange grape pear"), texts("orange grape pear"))
    }

    @Test
    fun `被空格切碎的单字会并进相邻的词`() {
        // `香`、`蕉` 显然是被切碎的同一个词，「苹果」是完整的
        assertEquals(listOf("苹果", "香蕉"), texts("苹果 香 蕉"))
        assertEquals(listOf("苹果", "香蕉", "橘子"), texts("苹果 香 蕉 橘子"))
    }

    @Test
    fun `序号后面跟着多个词时逐个剥离`() {
        assertEquals(listOf("苹果", "香蕉"), texts("1. 苹果 香蕉"))
    }

    @Test
    fun `只有分隔符没有内容的段被丢弃`() {
        assertEquals(listOf("苹果", "香蕉"), texts("苹果、、香蕉"))
        assertEquals(listOf("苹果"), texts("、苹果、"))
    }

    @Test
    fun `没有分隔符时原样返回一个词`() {
        assertEquals(listOf("苹果"), texts("苹果"))
        assertEquals(listOf("2024年"), texts("2024年"))
    }

    @Test
    fun `拆出来的每个词各自判可疑`() {
        // 拆开之后「苹果」不会因为整行太长被连累成「长句」，各自判定才对
        val line = "苹果 香蕉 橘子 葡萄 西瓜 桃子 李子 杏子 枣子 梨子 柚子 橙子"
        val got = OcrTextCleaner.toCandidates(listOf(line))
        assertEquals(12, got.size)
        assertTrue("不该有可疑行，实际：$got", got.all { it.suspect == null })
    }

    @Test
    fun `整行过长时仍然标成长句`() {
        // 拆词只针对「有分隔符」的行；没有任何分隔符的超长行还是句子
        val long = "这是一个明显已经超过十个字的长句子"
        assertEquals(listOf(SuspectReason.TOO_LONG), reasons(listOf(long)))
    }

    // ---- 序号前缀：和手动粘贴走同一套规则 ----

    @Test
    fun `行首的数字序号被去掉`() {
        assertEquals("苹果", clean("1. 苹果"))
        assertEquals("苹果", clean("2、苹果"))
        assertEquals("苹果", clean("(3) 苹果"))
        assertEquals("苹果", clean("（4）苹果"))
    }

    @Test
    fun `行首的中文序号被去掉`() {
        assertEquals("蓝天", clean("一、蓝天"))
        assertEquals("白云", clean("十二、白云"))
    }

    @Test
    fun `不误剥含数字的词语`() {
        assertEquals("2024年", clean("2024年"))
        assertEquals("12345", clean("12345"))
    }

    // ---- 全角转半角：只动字母数字 ----

    @Test
    fun `全角字母和数字转半角`() {
        assertEquals("ABC", clean("ＡＢＣ"))
        assertEquals("123", clean("１２３"))
        assertEquals("hello", clean("ｈｅｌｌｏ"))
        assertEquals("abc123", clean("ａｂｃ１２３"))
    }

    @Test
    fun `中文标点保持不动`() {
        // 中顿号、逗号本来就不是听写内容，但也别把它改成半角更怪
        assertEquals("苹果，香蕉", clean("苹果，香蕉"))
        assertEquals("苹果、香蕉", clean("苹果、香蕉"))
    }

    // ---- 首尾噪音与空白 ----

    @Test
    fun `首尾的装饰符号被剥掉`() {
        assertEquals("苹果", clean("· 苹果"))
        assertEquals("苹果", clean("——苹果"))
        assertEquals("苹果", clean("苹果 ·"))
        assertEquals("苹果", clean("|苹果|"))
    }

    @Test
    fun `各种空白字符被正确去除`() {
        assertEquals("苹果", clean("  苹果  "))
        assertEquals("苹果", clean("\u3000苹果\u3000"))
        assertEquals("苹果", clean("\u00A0苹果"))
        assertEquals("苹果", clean("\t苹果\t"))
    }

    // ---- 空行与纯符号行 ----

    @Test
    fun `空行直接丢弃`() {
        assertEquals(emptyList<OcrCandidate>(), OcrTextCleaner.toCandidates(listOf("", "   ", "\u3000")))
    }

    @Test
    fun `纯符号行被剥成空后丢弃`() {
        assertEquals(emptyList<OcrCandidate>(), OcrTextCleaner.toCandidates(listOf("——", "· · ·", "||||")))
    }

    // ---- 可疑判定 ----

    @Test
    fun `正常词语不被标记`() {
        val got = OcrTextCleaner.toCandidates(listOf("苹果", "蓝天", "中华人民共和国", "apple", "good morning"))
        assertTrue("不该有可疑行，实际：$got", got.all { it.suspect == null })
        assertTrue(got.all { it.selectedByDefault })
    }

    @Test
    fun `纯数字行被标成像页码`() {
        assertEquals(
            listOf(SuspectReason.NUMERIC_ONLY),
            reasons(listOf("128")),
        )
    }

    @Test
    fun `单字符行被标成太短`() {
        assertEquals(listOf(SuspectReason.TOO_SHORT), reasons(listOf("苹")))
    }

    @Test
    fun `超长行被标成句子`() {
        val long = "这是一个明显已经超过十个字的长句子"
        assertEquals(listOf(SuspectReason.TOO_LONG), reasons(listOf(long)))
    }

    @Test
    fun `英文短语按词数算长度，不按字符数`() {
        // 这是回归测试：一开始按「去掉空白的字符数」判定，`good morning`
        // 算 11 个字符，直接就被当成句子筛掉了——而它只是两个词。
        // 现在按词元计数，一个汉字算一个、一个西文单词算一个
        assertEquals(null, OcrTextCleaner.toCandidates(listOf("good morning")).single().suspect)
        assertEquals(null, OcrTextCleaner.toCandidates(listOf("happy new year")).single().suspect)
        assertEquals(null, OcrTextCleaner.toCandidates(listOf("apple")).single().suspect)
    }

    @Test
    fun `英文长句仍然会被标成长句`() {
        val sentence = "the quick brown fox jumps over the lazy dog again and again"
        assertEquals(listOf(SuspectReason.TOO_LONG), reasons(listOf(sentence)))
    }

    @Test
    fun `正好卡在长度上限的行不算可疑`() {
        val exact = "一".repeat(OcrTextCleaner.SUSPECT_LONG_TOKENS)
        assertNull(OcrTextCleaner.toCandidates(listOf(exact)).single().suspect)
    }

    @Test
    fun `带连字符的英文算一个词`() {
        // good-morning 是 12 个字符但只有 1 个词，不该被当成句子
        assertNull(OcrTextCleaner.toCandidates(listOf("good-morning")).single().suspect)
    }

    @Test
    fun `可疑行仍然会被保留，只是默认不勾`() {
        val got = OcrTextCleaner.toCandidates(listOf("苹果", "128", "蓝天"))
        assertEquals(3, got.size)
        assertEquals(listOf("苹果", "128", "蓝天"), got.map { it.text })
        // 页码那行虽然默认不勾，但用户能看到它、也能勾上
        assertEquals(false, got[1].selectedByDefault)
        assertEquals(true, got[0].selectedByDefault)
        assertEquals(true, got[2].selectedByDefault)
    }

    @Test
    fun `空输入得到空结果`() {
        assertEquals(emptyList<OcrCandidate>(), OcrTextCleaner.toCandidates(emptyList()))
    }

    // ---- 拼回文本 ----

    @Test
    fun `拼行用换行且跳过空白`() {
        assertEquals("苹果\n蓝天", OcrTextCleaner.joinLines(listOf("苹果", "  ", "蓝天")))
        assertEquals("", OcrTextCleaner.joinLines(emptyList()))
    }

    @Test
    fun `清洗后的结果能直接被词语解析器接受`() {
        // 这是端到端的契约：清洗的产物必须能被 WordParser 正确解析，
        // 否则「识别完填进去」这个动作在词数上是错的
        val cleaned = OcrTextCleaner.toCandidates(
            listOf("小 朋 友", "1. 苹果", "１２３", "good morning")
        ).filter { it.selectedByDefault }.map { it.text }
        val words = cn.com.toobug.youxue.dictation.WordParser.parse(OcrTextCleaner.joinLines(cleaned))
        assertEquals(listOf("小朋友", "苹果", "good morning"), words)
    }

    @Test
    fun `横排词表端到端解析成多个词语`() {
        // 用户提的那件事：一行 `苹果 香蕉 橘子` 填进文本框后，
        // WordParser 必须按三个词听写，而不是当成一个词
        val cleaned = OcrTextCleaner.toCandidates(listOf("苹果 香蕉 橘子", "1、蓝天"))
            .filter { it.selectedByDefault }.map { it.text }
        val words = cn.com.toobug.youxue.dictation.WordParser.parse(OcrTextCleaner.joinLines(cleaned))
        assertEquals(listOf("苹果", "香蕉", "橘子", "蓝天"), words)
    }
}
