package cn.com.toobug.youxue.tts

import kotlinx.coroutines.flow.StateFlow

/**
 * 一次朗读请求的结果。
 */
sealed interface SpeakResult {
    /** 正常朗读完毕 */
    data object Done : SpeakResult

    /** 被 [TtsEngine.stop] 主动中断 */
    data object Interrupted : SpeakResult

    /** 发生错误 */
    data class Error(val message: String) : SpeakResult
}

/**
 * 语音合成引擎抽象。
 *
 * 之所以抽出接口，是因为业务层（听写会话）不应该关心底层是系统 TTS 还是
 * 内置的离线神经网络模型。将来若要接入 sherpa-onnx 之类的开源离线模型，
 * 只需新增一个实现并替换注入即可，听写逻辑与界面完全不用改。
 */
interface TtsEngine {

    /** 引擎当前状态 */
    val state: StateFlow<TtsState>

    /**
     * 以指定参数朗读文本，协程挂起直到朗读结束（或被中断/出错）。
     *
     * @param text      待朗读文本
     * @param rate      语速倍率，1.0 为正常
     * @param pitch     音调倍率，1.0 为正常
     * @param repeat    重复朗读次数（>= 1）
     */
    suspend fun speak(
        text: String,
        rate: Float = 1.0f,
        pitch: Float = 1.0f,
        repeat: Int = 1,
    ): SpeakResult

    /**
     * 判断某个语言当前是否可用。
     *
     * 供界面提前提示用——例如用户录入了英文词但设备没装英文语音包时，
     * 应该在开始听写前就告诉他，而不是读到一半才发现读不出来。
     */
    fun isLanguageAvailable(language: SpeechLanguage): Boolean

    /** 立即停止当前朗读 */
    fun stop()

    /** 释放底层资源 */
    fun shutdown()
}

/**
 * 朗读语言。
 *
 * 只区分中英两种，是因为听写场景实际只需要这两类。
 * 判定由 [SpeechLanguage.detectFor] 按字符合成占比自动完成，用户无需手动切换。
 */
enum class SpeechLanguage(val tag: String, val displayName: String) {
    CHINESE("zh", "中文"),
    ENGLISH("en", "英文");

    companion object {
        /**
         * 根据文本自动判断该用哪种语言朗读。
         *
         * 判断依据是「谁的字多」：
         * - 汉字（CJK 统一表意文字）计为中文
         * - ASCII 字母计为英文
         * - 数字、标点、空白都不计分——因为两者都不会因此改变发音方式
         *
         * 平局或纯符号时回退中文，因为这是面向中文用户的听写应用，
         * 而纯数字（如 "123"）用中文念「一百二十三」更符合预期。
         */
        fun detectFor(text: String): SpeechLanguage {
            var han = 0
            var latin = 0
            for (c in text) {
                when {
                    isHan(c) -> han++
                    c.isLetter() && c.code < 128 -> latin++
                }
            }
            return if (latin > han) ENGLISH else CHINESE
        }

        /** 是否为汉字（含扩展 A 区与常用兼容区） */
        private fun isHan(c: Char): Boolean {
            val code = c.code
            return (code in 0x4E00..0x9FFF) ||   // CJK 统一表意文字
                (code in 0x3400..0x4DBF) ||       // 扩展 A
                (code in 0xF900..0xFAFF)          // 兼容表意文字
        }
    }
}

/**
 * 引擎状态。
 */
sealed interface TtsState {
    /** 尚未初始化完成 */
    data object Initializing : TtsState

    /** 就绪可用 */
    data object Ready : TtsState

    /**
     * 部分可用：引擎本身正常，但缺少某种语言的语音包。
     *
     * 单独分一个状态而不是直接判为不可用，是因为缺英文不影响中文听写，
     * 反过来也一样。让用户知道「能听写，但只有一种语言」比直接报错更友好。
     */
    data class PartiallyAvailable(
        val missingLanguage: SpeechLanguage,
        val reason: String,
    ) : TtsState

    /**
     * 不可用。[reason] 为面向用户的说明文案，
     * [canRetry] 表示是否值得让用户重试（例如去装了语音包再回来）。
     */
    data class Unavailable(
        val reason: String,
        val canRetry: Boolean = true,
    ) : TtsState
}
