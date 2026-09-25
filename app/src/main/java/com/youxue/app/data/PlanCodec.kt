package com.youxue.app.data

/**
 * 学习计划与打卡记录的文本编解码。
 *
 * 沿用 [TaskCodec] 的路子（为什么不用 JSON 见那边的说明），
 * 但计划多了一个**集合字段** `weekdays`：编码成 `1,3,5` 这样的逗号列表，
 * 空串表示空集合（即「每天」）。
 *
 * 两套数据各编各的（[encodePlans] / [encodeRecords]），而不是把记录嵌进计划里。
 * 理由是「查看完成记录」既要按计划看，也要跨计划统计，
 * 记录独立成行就不用为了取一部分而解析全部计划。
 *
 * 解码对坏数据宽容：解析失败的行直接丢弃而不是抛异常。
 * 宁可少一条计划，也不能让整个列表打不开。
 */
object PlanCodec {

    private const val FIELD_SEP = '|'
    private const val LINE_SEP = '\n'
    private const val SET_SEP = ','

    /** id | 标题 | 重复日 | 起始日 | 结束日 | 启用 | 创建时刻 */
    private const val PLAN_FIELDS = 7

    /** 计划 id | 打卡日 | 打卡时刻 */
    private const val RECORD_FIELDS = 3

    // ------------------------------------------------------------------ 计划

    fun encodePlans(plans: List<StudyPlan>): String =
        plans.joinToString(LINE_SEP.toString()) { p ->
            buildString {
                append(TextEscape.escape(p.id)); append(FIELD_SEP)
                append(TextEscape.escape(p.title)); append(FIELD_SEP)
                // 排序后再写，让同一份集合每次编码出的字符串完全一致，
                // 「值没变就不落盘」这种判断才可靠
                append(p.weekdays.sorted().joinToString(SET_SEP.toString())); append(FIELD_SEP)
                append(p.startDay); append(FIELD_SEP)
                append(p.endDay?.toString() ?: ""); append(FIELD_SEP)
                append(if (p.enabled) "1" else "0"); append(FIELD_SEP)
                append(p.createdAt)
            }
        }

    fun decodePlans(raw: String): List<StudyPlan> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(LINE_SEP).mapNotNull(::parsePlan)
    }

    private fun parsePlan(line: String): StudyPlan? {
        if (line.isEmpty()) return null
        val parts = line.split(FIELD_SEP)
        if (parts.size != PLAN_FIELDS) return null

        val id = TextEscape.unescape(parts[0])
        val title = TextEscape.unescape(parts[1])
        // id 缺失则无法定位、标题缺失则无法展示，都视为坏行
        if (id.isEmpty() || title.isEmpty()) return null

        // 起始日是「哪天开始生效」的唯一依据，缺了就没法判定，整行丢弃。
        // 与它不同，结束日、创建时刻缺失都还能用默认值兜住
        val start = parts[3].toLongOrNull() ?: return null
        val end = parts[4].toLongOrNull()
        // 结束日早于起始日的区间是空的，计划会「哪天都不出现」。
        // 与 TaskCodec 的取舍一致：宁可纠正也不让记录凭空消失
        val safeEnd = end?.takeIf { it >= start }

        return StudyPlan(
            id = id,
            title = title,
            weekdays = parseWeekdays(parts[2]),
            startDay = start,
            endDay = safeEnd,
            enabled = parts[5] != "0",
            createdAt = parts[6].toLongOrNull() ?: 0L,
        )
    }

    /** `1,3,5` → `{1,3,5}`。空串、越界值、非数字一律宽容处理，只留下合法的那几个 */
    private fun parseWeekdays(s: String): Set<Int> {
        if (s.isBlank()) return emptySet()
        return s.split(SET_SEP)
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..6 }
            .toSet()
    }

    // ------------------------------------------------------------------ 记录

    fun encodeRecords(records: List<PlanRecord>): String =
        records.joinToString(LINE_SEP.toString()) { r ->
            buildString {
                append(TextEscape.escape(r.planId)); append(FIELD_SEP)
                append(r.day); append(FIELD_SEP)
                append(r.completedAt)
            }
        }

    fun decodeRecords(raw: String): List<PlanRecord> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(LINE_SEP).mapNotNull(::parseRecord)
    }

    private fun parseRecord(line: String): PlanRecord? {
        if (line.isEmpty()) return null
        val parts = line.split(FIELD_SEP)
        if (parts.size != RECORD_FIELDS) return null

        val planId = TextEscape.unescape(parts[0])
        if (planId.isEmpty()) return null
        // 日期和时刻都缺就没有意义了，直接丢
        val day = parts[1].toLongOrNull() ?: return null
        val at = parts[2].toLongOrNull() ?: return null

        return PlanRecord(planId = planId, day = day, completedAt = at)
    }
}
