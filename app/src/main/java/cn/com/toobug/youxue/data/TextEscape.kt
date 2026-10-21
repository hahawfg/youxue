package cn.com.toobug.youxue.data

/**
 * 「一行一条、字段用 `|` 分隔」这类扁平文本格式的转义工具。
 *
 * 只需要封住 `\`、`|`、`\n`、`\r` 四个字符：记录之间靠换行分隔、
 * 字段之间靠竖线分隔，把这两类封住之后，其余字符原样透传都不可能破坏结构。
 * 也正因为转义做得全，字段分割可以用朴素的 `split('|')`，
 * 不需要写带状态的切分器。
 *
 * 注：[TaskCodec] 里有一份等价的私有实现，先于本文件存在，且被 21 条单测
 * 保护着，所以没去动它。新增的 [PlanCodec] 走这里，避免再复制第三份。
 * 以后若嫌两份重复，可以把 TaskCodec 也切过来——两边行为完全一致，
 * 切换是纯重构，不该有测试需要改。
 */
object TextEscape {

    fun escape(s: String): String = buildString(s.length) {
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
    fun unescape(s: String): String {
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
