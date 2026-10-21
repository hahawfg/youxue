package cn.com.toobug.youxue.data

/**
 * 听写参数设置。
 *
 * 之所以把「间隔」拆成三个参数而不是一个固定秒数，是因为固定值无法同时适配
 * 单字词（如"日"）和长词（如"中华人民共和国"）。用户只需调一个「每字时长」的
 * 直觉参数，具体每个词的等待时间由字数自动推导出来。
 */
data class DictationSettings(
    /** 每个字分配的书写秒数，间隔 = 字数 × 该值 */
    val perCharSeconds: Float = 1.5f,

    /** 单个词语的最小间隔秒数 */
    val minSeconds: Float = 1.5f,

    /** 单个词语的最大间隔秒数 */
    val maxSeconds: Float = 20f,

    /** 每个词语重复朗读的次数 */
    val repeatCount: Int = 1,

    /** 朗读语速倍率 */
    val speechRate: Float = 0.9f,

    /** 开始前的预备倒计时秒数，给学生拿笔的时间 */
    val prepareSeconds: Int = 3,

    /** 朗读完后是否自动进入下一词（关闭后需手动点击"下一个"） */
    val autoAdvance: Boolean = true,

    /** 是否朗读词语的序号，例如"第 1 个，苹果" */
    val speakIndex: Boolean = true,

    /** 是否在最后一个词后朗读"听写结束" */
    val speakFinishAnnouncement: Boolean = true,
) {
    companion object {
        const val PER_CHAR_MIN = 0.5f
        const val PER_CHAR_MAX = 5f
        const val MIN_INTERVAL_MIN = 1f
        const val MIN_INTERVAL_MAX = 10f
        const val MAX_INTERVAL_MIN = 5f
        const val MAX_INTERVAL_MAX = 60f
        const val REPEAT_MIN = 1
        const val REPEAT_MAX = 3
        const val RATE_MIN = 0.5f
        const val RATE_MAX = 1.5f
        const val PREPARE_MIN = 0
        const val PREPARE_MAX = 10

        /**
         * 各滑块的步数。
         *
         * Compose Slider 的 `steps` 是「中间刻度数」，即实际可选位置 = steps + 2。
         * 注意一个容易踩的坑：**步数必须能整除量程**，否则拖动时会被吸附到
         * 非整值上（例如 0.5~5.0 分 17 步 → 每步 0.25，但首尾对齐后会出现
         * 2.375 这种脏值）。这里统一按「每步 0.5」设计，保证值都是整齐的。
         */
        const val PER_CHAR_STEPS = 8   // 0.5 → 5.0，每步 0.5，共 10 个档位
        const val MIN_INTERVAL_STEPS = 8   // 1 → 10，每步 1，共 10 个档位
        const val MAX_INTERVAL_STEPS = 10  // 5 → 60，每步 5，共 12 个档位
        const val RATE_STEPS = 9       // 0.5 → 1.5，每步 0.1，共 11 个档位
        const val REPEAT_STEPS = 1     // 1 → 3，共 3 个档位
        const val PREPARE_STEPS = 9    // 0 → 10，每步 1，共 11 个档位

        val DEFAULT = DictationSettings()
    }
}
