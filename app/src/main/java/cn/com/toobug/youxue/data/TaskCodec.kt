package cn.com.toobug.youxue.data

/**
 * 任务列表的文本编解码。
 *
 * 为什么不用 JSON：`kotlinx-serialization` 需要一个额外的编译器插件，
 * 项目依赖目录里没有；而 Android 自带的 `org.json` 在 JVM 单测里是空实现，
 * 没法验证。所以这里用「一行一条、字段用 `|` 分隔」的扁平格式——
 * 零新增依赖、纯 Kotlin，可以被单测完整覆盖。
 *
 * 文本字段里的 `|`、换行、反斜杠在编码时转义，所以**任何**用户输入都能安全往返。
 * 也正因为转义，字段分割可以用朴素的 `split('|')`，不需要写带状态的切分器。
 *
 * 解码对坏数据是宽容的：解析失败的行直接丢弃而不是抛异常。
 * 宁可少一条任务，也不能让整个列表打不开。
 */
object TaskCodec {

    private const val FIELD_SEP = '|'
    private const val LINE_SEP = '\n'

    /** id | 标题 | 内容 | 开始日 | 截止日 | 完成 | 创建时刻 | 完成时刻 */
    private const val FIELD_COUNT = 8

    /**
     * 早先的格式没有「开始日」，日期只存一个。
     * 那段数据还躺在用户设备上，所以 7 字段的行必须继续认——
     * 读进来按「那一天当天要做」处理（`开始日 = 截止日`），等价于旧语义。
     */
    private const val FIELD_COUNT_LEGACY = 7

    fun encode(tasks: List<TaskItem>): String =
        tasks.joinToString(LINE_SEP.toString()) { t ->
            buildString {
                append(escape(t.id)); append(FIELD_SEP)
                append(escape(t.title)); append(FIELD_SEP)
                append(escape(t.content)); append(FIELD_SEP)
                append(t.startDay?.toString() ?: ""); append(FIELD_SEP)
                append(t.endDay?.toString() ?: ""); append(FIELD_SEP)
                append(if (t.done) "1" else "0"); append(FIELD_SEP)
                append(t.createdAt); append(FIELD_SEP)
                append(t.completedAt)
            }
        }

    fun decode(raw: String): List<TaskItem> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(LINE_SEP).mapNotNull(::parseLine)
    }

    private fun parseLine(line: String): TaskItem? {
        if (line.isEmpty()) return null
        val parts = line.split(FIELD_SEP)
        return when (parts.size) {
            FIELD_COUNT -> parseCurrent(parts)
            FIELD_COUNT_LEGACY -> parseLegacy(parts)
            else -> null
        }
    }

    private fun parseCurrent(parts: List<String>): TaskItem? {
        val id = unescape(parts[0])
        val title = unescape(parts[1])
        // id/标题缺失的记录无法定位也无法展示，视为坏行
        if (id.isEmpty() || title.isEmpty()) return null

        val rawStart = parts[3].toLongOrNull()
        val end = parts[4].toLongOrNull()

        return TaskItem(
            id = id,
            title = title,
            content = unescape(parts[2]),
            startDay = normalizeStart(rawStart, end),
            endDay = end,
            done = parts[5] == "1",
            createdAt = parts[6].toLongOrNull() ?: 0L,
            completedAt = parts[7].toLongOrNull() ?: 0L,
        )
    }

    /**
     * 守住 `startDay <= endDay` 这条不变量。
     *
     * 区间为空的任务在任何分组里都不会出现——等于任务消失了。所以解码时宁可纠正：
     * 只有截止日没有开始日的，按「当天要做」；开始日跑到截止日后面的，夹回截止日。
     */
    private fun normalizeStart(start: Long?, end: Long?): Long? = when {
        end == null -> start
        start == null -> end
        start > end -> end
        else -> start
    }

    /** 旧格式：id | 标题 | 内容 | 日期 | 完成 | 创建时刻 | 完成时刻 */
    private fun parseLegacy(parts: List<String>): TaskItem? {
        val id = unescape(parts[0])
        val title = unescape(parts[1])
        if (id.isEmpty() || title.isEmpty()) return null

        val day = parts[3].toLongOrNull()
        return TaskItem(
            id = id,
            title = title,
            content = unescape(parts[2]),
            startDay = day,
            endDay = day,
            done = parts[4] == "1",
            createdAt = parts[5].toLongOrNull() ?: 0L,
            completedAt = parts[6].toLongOrNull() ?: 0L,
        )
    }

    /**
     * 转义分隔符与转义符本身。
     *
     * 只需要处理 `\`、`|`、`\n`、`\r` 四个字符——因为记录之间靠换行分隔、
     * 字段之间靠竖线分隔，把这两类字符封住之后，其余字符原样透传都不可能破坏结构。
     */
    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '|' -> append("\\p")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

    /** 转义是 `\` 引导的双字符序列，遇到无法识别的组合就按字面量处理 */
    private fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val decoded = if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> '\\'
                    'p' -> '|'
                    'n' -> '\n'
                    'r' -> '\r'
                    else -> null
                }
            } else {
                null
            }
            if (decoded != null) {
                out.append(decoded)
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
