package cn.com.toobug.youxue.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DictationSettings] 参数约束测试。
 *
 * 重点守住滑块配置的自洽性：滑块步数和量程必须能整除，
 * 否则用户拖出来的值会带上 2.375 这类脏小数。
 */
class DictationSettingsTest {

    /** 滑块可选值个数 = steps + 2（首尾两端各算一个） */
    private fun stepSize(min: Float, max: Float, steps: Int): Float = (max - min) / (steps + 1)

    @Test
    fun `每字时长滑块步长整齐`() {
        val size = stepSize(
            DictationSettings.PER_CHAR_MIN,
            DictationSettings.PER_CHAR_MAX,
            DictationSettings.PER_CHAR_STEPS,
        )
        assertEquals("每字时长应为每步 0.5 秒", 0.5f, size, 0.0001f)
    }

    @Test
    fun `最短间隔滑块步长整齐`() {
        val size = stepSize(
            DictationSettings.MIN_INTERVAL_MIN,
            DictationSettings.MIN_INTERVAL_MAX,
            DictationSettings.MIN_INTERVAL_STEPS,
        )
        assertEquals("最短间隔应为每步 1 秒", 1f, size, 0.0001f)
    }

    @Test
    fun `最长间隔滑块步长整齐`() {
        val size = stepSize(
            DictationSettings.MAX_INTERVAL_MIN,
            DictationSettings.MAX_INTERVAL_MAX,
            DictationSettings.MAX_INTERVAL_STEPS,
        )
        assertEquals("最长间隔应为每步 5 秒", 5f, size, 0.0001f)
    }

    @Test
    fun `语速滑块步长整齐`() {
        val size = stepSize(
            DictationSettings.RATE_MIN,
            DictationSettings.RATE_MAX,
            DictationSettings.RATE_STEPS,
        )
        assertEquals("语速应为每步 0.1 倍", 0.1f, size, 0.0001f)
    }

    @Test
    fun `预备时间滑块步长整齐`() {
        val size = stepSize(
            DictationSettings.PREPARE_MIN.toFloat(),
            DictationSettings.PREPARE_MAX.toFloat(),
            DictationSettings.PREPARE_STEPS,
        )
        assertEquals("预备时间应为每步 1 秒", 1f, size, 0.0001f)
    }

    @Test
    fun `重复朗读滑块步长整齐`() {
        val size = stepSize(
            DictationSettings.REPEAT_MIN.toFloat(),
            DictationSettings.REPEAT_MAX.toFloat(),
            DictationSettings.REPEAT_STEPS,
        )
        assertEquals("重复遍数应为每步 1 遍", 1f, size, 0.0001f)
    }

    @Test
    fun `默认值都落在各自量程内`() {
        val d = DictationSettings.DEFAULT
        assertTrue(d.perCharSeconds in DictationSettings.PER_CHAR_MIN..DictationSettings.PER_CHAR_MAX)
        assertTrue(d.minSeconds in DictationSettings.MIN_INTERVAL_MIN..DictationSettings.MIN_INTERVAL_MAX)
        assertTrue(d.maxSeconds in DictationSettings.MAX_INTERVAL_MIN..DictationSettings.MAX_INTERVAL_MAX)
        assertTrue(d.speechRate in DictationSettings.RATE_MIN..DictationSettings.RATE_MAX)
        assertTrue(d.repeatCount in DictationSettings.REPEAT_MIN..DictationSettings.REPEAT_MAX)
        assertTrue(d.prepareSeconds in DictationSettings.PREPARE_MIN..DictationSettings.PREPARE_MAX)
    }

    @Test
    fun `默认最短间隔不超过默认最长间隔`() {
        val d = DictationSettings.DEFAULT
        assertTrue(
            "minSeconds(${d.minSeconds}) 不应大于 maxSeconds(${d.maxSeconds})，否则夹取逻辑会退化",
            d.minSeconds <= d.maxSeconds,
        )
    }

    @Test
    fun `默认每字时长乘以常见词长仍在最长间隔内`() {
        val d = DictationSettings.DEFAULT
        // 默认配置下，2~6 字词的间隔都不该被上限截断（体验才线性可预期）
        for (n in 2..6) {
            val raw = n * d.perCharSeconds
            assertTrue("$n 字词的间隔 $raw 秒被最长间隔截断了", raw <= d.maxSeconds)
        }
    }
}
