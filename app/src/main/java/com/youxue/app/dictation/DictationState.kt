package com.youxue.app.dictation

/**
 * 听写过程中每个词语的状态。
 */
enum class WordStatus {
    /** 还没轮到 */
    PENDING,

    /** 正在朗读 */
    SPEAKING,

    /** 朗读完毕，正在等待书写（倒计时中） */
    WAITING,

    /** 已过完，等待用户完成 */
    DONE,
}

/**
 * 听写会话的阶段。
 */
sealed interface DictationPhase {
    /** 开始前的预备倒计时，给学生拿笔 */
    data class Preparing(val secondsLeft: Int) : DictationPhase

    /** 正在朗读或用倒计时等待书写 */
    data object Running : DictationPhase

    /** 用户主动暂停 */
    data object Paused : DictationPhase

    /** 已结束 */
    data object Finished : DictationPhase
}

/**
 * 单个词语在会话中的运行时数据。
 */
data class WordRuntime(
    val index: Int,
    val text: String,
    /** 该词被分配的书写间隔（毫秒），由字数推导 */
    val intervalMillis: Long,
    val status: WordStatus = WordStatus.PENDING,
    /** 用户是否标记为「没听清」（仅作提醒，不影响流程） */
    val flagged: Boolean = false,
)

/**
 * 完整的会话 UI 状态。界面只读这一份状态，避免多处散落导致不一致。
 */
data class DictationUiState(
    val phase: DictationPhase = DictationPhase.Preparing(3),
    val words: List<WordRuntime> = emptyList(),
    val currentIndex: Int = 0,
    /** 当前等待阶段的剩余毫秒 */
    val remainingMillis: Long = 0L,
    /** 当前词语分配的总间隔毫秒，用于画环形进度 */
    val currentIntervalMillis: Long = 0L,
    /** 已用总时长（毫秒），从听写真正开始计算 */
    val elapsedMillis: Long = 0L,
    /** 最近一次错误提示，展示后可清空 */
    val errorMessage: String? = null,
    /** 是否正在朗读（用于界面上的声波动画） */
    val isSpeaking: Boolean = false,
) {
    val total: Int get() = words.size
    val currentWord: WordRuntime? get() = words.getOrNull(currentIndex)
    val isFinished: Boolean get() = phase is DictationPhase.Finished
    val isPreparing: Boolean get() = phase is DictationPhase.Preparing
    val isPaused: Boolean get() = phase is DictationPhase.Paused

    /** 已完成（已轮过）的词数 */
    val completedCount: Int get() = words.count { it.status == WordStatus.DONE }

    val progressFraction: Float
        get() = if (total == 0) 0f else (currentIndex + 1).toFloat() / total

    /** 当前等待倒计时的剩余比例，1 → 0 */
    val remainingFraction: Float
        get() = if (currentIntervalMillis <= 0) 0f
        else (remainingMillis.toFloat() / currentIntervalMillis).coerceIn(0f, 1f)
}

/**
 * 听写结果，用于完成页展示。
 */
data class DictationResult(
    val words: List<String>,
    /** 总用时毫秒 */
    val elapsedMillis: Long,
    /** 是否自然走完全部词语（false 表示用户中途结束） */
    val completed: Boolean,
) {
    val wordCount: Int get() = words.size

    /** 平均每词用时 */
    val averageMillisPerWord: Long
        get() = if (wordCount == 0) 0 else elapsedMillis / wordCount
}
