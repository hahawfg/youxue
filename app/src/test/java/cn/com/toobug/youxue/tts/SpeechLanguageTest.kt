package cn.com.toobug.youxue.tts

import cn.com.toobug.youxue.dictation.DictationViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 语言自动识别与朗读文本拼装的测试。
 *
 * 这两块都做成了纯函数，正是为了能在这里直接断言——
 * 语言判断错了会直接表现成「英文词被念成中文」，属于用户一眼能看出的问题。
 */
class SpeechLanguageTest {

    // ------------------------------------------------ detectFor

    @Test
    fun `纯中文判定为中文`() {
        assertEquals(SpeechLanguage.CHINESE, SpeechLanguage.detectFor("苹果"))
        assertEquals(SpeechLanguage.CHINESE, SpeechLanguage.detectFor("欣欣向荣"))
    }

    @Test
    fun `纯英文判定为英文`() {
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguage.detectFor("apple"))
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguage.detectFor("beautiful"))
    }

    @Test
    fun `首字母大写和全大写都判定为英文`() {
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguage.detectFor("Apple"))
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguage.detectFor("APPLE"))
    }

    @Test
    fun `英文短语含空格连字符也判定为英文`() {
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguage.detectFor("good morning"))
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguage.detectFor("ice-cream"))
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguage.detectFor("don't"))
    }

    @Test
    fun `纯数字回退中文`() {
        // 面向中文用户的听写应用，"123" 用中文念「一百二十三」更符合预期
        assertEquals(SpeechLanguage.CHINESE, SpeechLanguage.detectFor("123"))
        assertEquals(SpeechLanguage.CHINESE, SpeechLanguage.detectFor("2024"))
    }

    @Test
    fun `纯标点空白回退中文`() {
        assertEquals(SpeechLanguage.CHINESE, SpeechLanguage.detectFor("……"))
        assertEquals(SpeechLanguage.CHINESE, SpeechLanguage.detectFor("   "))
        assertEquals(SpeechLanguage.CHINESE, SpeechLanguage.detectFor(""))
    }

    @Test
    fun `字母多于汉字时判定英文`() {
        // "A 计划" → 1 个字母 vs 2 个汉字 → 中文
        assertEquals(SpeechLanguage.CHINESE, SpeechLanguage.detectFor("A 计划"))
        // "Apple 苹果" → 5 个字母 vs 2 个汉字 → 英文
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguage.detectFor("Apple 苹果"))
    }

    @Test
    fun `中英混排平局时回退中文`() {
        // "ab中文" → 2 vs 2，平局按中文处理
        assertEquals(SpeechLanguage.CHINESE, SpeechLanguage.detectFor("ab中文"))
    }

    @Test
    fun `汉字标点与数字不参与计分`() {
        // 「英语」2 个汉字，后面的数字/标点不影响判定
        assertEquals(SpeechLanguage.CHINESE, SpeechLanguage.detectFor("英语，第 1 课。"))
    }

    @Test
    fun `扩展区汉字能被识别为中文`() {
        // 㐀 落在扩展 A 区（0x3400..0x4DBF）
        assertEquals(SpeechLanguage.CHINESE, SpeechLanguage.detectFor("\u3400"))
    }

    // ------------------------------------------------ buildSpeakText

    @Test
    fun `中文开启序号时会念出第几个`() {
        assertEquals(
            "第 1 个，苹果",
            DictationViewModel.buildSpeakText("苹果", index = 0, speakIndex = true),
        )
        assertEquals(
            "第 3 个，欣欣向荣",
            DictationViewModel.buildSpeakText("欣欣向荣", index = 2, speakIndex = true),
        )
    }

    @Test
    fun `英文开启序号时只念单词本身`() {
        // 序号是中文、单词是英文，混在一起会夹一次语言切换，听起来很割裂
        assertEquals(
            "apple",
            DictationViewModel.buildSpeakText("apple", index = 0, speakIndex = true),
        )
        assertEquals(
            "beautiful",
            DictationViewModel.buildSpeakText("beautiful", index = 4, speakIndex = true),
        )
    }

    @Test
    fun `关闭序号时中英文都只念文本`() {
        assertEquals(
            "苹果",
            DictationViewModel.buildSpeakText("苹果", index = 0, speakIndex = false),
        )
        assertEquals(
            "apple",
            DictationViewModel.buildSpeakText("apple", index = 0, speakIndex = false),
        )
    }
}
