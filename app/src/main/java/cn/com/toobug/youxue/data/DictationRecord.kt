package cn.com.toobug.youxue.data

/**
 * 一次听写的历史记录。
 *
 * 用户要的是「记录听写词语」——所以核心字段就是那批词语本身，
 * 附带记下**开始时刻**与**用时**，方便回顾「什么时候练的、练了多久」。
 * 刻意不记正确率：本 App 是家长/学生自己对着写，没有判分环节，
 * 硬塞一个「正确率」只能是编出来的数字。
 */
data class DictationRecord(
    /** 完成时刻（毫秒）。同一天内要分先后，所以用毫秒而不是日历天 */
    val completedAt: Long,
    /** 本批词语。只存词语，不存每个词的对错、耗时 */
    val words: List<String>,
    /** 本次听写用时（毫秒） */
    val elapsedMillis: Long,
) {
    val wordCount: Int get() = words.size
}
