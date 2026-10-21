package cn.com.toobug.youxue.data

/**
 * 听写历史的文本编解码。
 *
 * 沿用 [TaskCodec] / [PlanCodec] 的路子（为什么不用 JSON 见 TaskCodec 的说明）。
 *
 * 格式：**一行一条记录**，记录内字段用 `|` 分隔：
 * ```
 * 完成时刻 | 用时 | 词语1 | 词语2 | ...
 * ```
 * 词语是**变长**的——一次听写几个词由用户决定，所以词表放在最后、
 * 占满剩余的所有字段。前面两个字段位置固定，往后加字段也是加在它们之后、
 * 词表之前（解析时按 `parts.size` 给旧格式分支）。
 *
 * 词与词之间本来就有歧义空间：`苹果|香蕉` 与「一个词里带竖线」编码后一样。
 * 但词语字段逐个做了转义，所以真正带 `|` 的词语会写成 `\p`，不会串到下一格去。
 */
object DictationHistoryCodec {

    private const val FIELD_SEP = '|'
    private const val LINE_SEP = '\n'

    /** 完成时刻 | 用时 | 词1 | 词2 | ... → 最少要有前两个字段再加至少一个词 */
    private const val FIXED_FIELDS = 2

    fun encode(records: List<DictationRecord>): String =
        records.joinToString(LINE_SEP.toString()) { r ->
            buildString {
                append(r.completedAt); append(FIELD_SEP)
                append(r.elapsedMillis)
                // 词语顺序就是听写顺序，不能排序——用户复习时按顺序读才对得上
                for (w in r.words) {
                    append(FIELD_SEP)
                    append(TextEscape.escape(w))
                }
            }
        }

    fun decode(raw: String): List<DictationRecord> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(LINE_SEP).mapNotNull(::parseLine)
    }

    private fun parseLine(line: String): DictationRecord? {
        if (line.isEmpty()) return null
        val parts = line.split(FIELD_SEP)
        if (parts.size < FIXED_FIELDS + 1) return null

        val at = parts[0].toLongOrNull() ?: return null
        val elapsed = parts[1].toLongOrNull() ?: 0L

        val words = parts.drop(FIXED_FIELDS)
            .map { TextEscape.unescape(it) }
            // 空词丢弃：空串在听写里没有意义，多半是坏数据留下的尾巴
            .filter { it.isNotBlank() }

        // 一个词都没有的记录等于「听写过但什么都没记」，留着只会让列表出现空条目
        if (words.isEmpty()) return null

        return DictationRecord(
            completedAt = at,
            words = words,
            elapsedMillis = elapsed.coerceAtLeast(0L),
        )
    }

    /**
     * 只保留最近 [limit] 条。
     *
     * 历史写在**同一个 Preferences 键**里，没有分页也没有清理机制，
     * 不设上限的话这个字符串会一直长下去，每次读设置都要把它整个解析一遍。
     * 听写记录是「回看最近练了什么」，几十条足够，再早的没有价值。
     */
    fun trim(records: List<DictationRecord>, limit: Int): List<DictationRecord> =
        if (records.size <= limit) records else records.takeLast(limit)
}
