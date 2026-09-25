package com.youxue.app.plan

import com.youxue.app.data.PlanRecord
import com.youxue.app.data.StudyPlan
import com.youxue.app.util.DayMath

/**
 * 计划的重复规则判定与统计。
 *
 * 全是纯函数：不碰 Android、不碰存储、不读当前时间（「今天」一律由调用方传入）。
 * 这样做不只是为了好测——**「今天」是个会变的值**，藏在函数内部读系统时钟的话，
 * 跨零点时同一份数据会算出不同结果，界面和统计就对不上了。
 *
 * 两个概念要分清，它们只差一个 `enabled`：
 * - [isRepeatDay]「按重复规则，这天该做吗」—— 统计连续打卡用这个。
 *   停用计划不会让历史连胜凭空归零（停用期间该做的日子仍是那些日子）。
 * - [isDueOn]「今天该做吗」—— 列表和角标用这个，停用就是不做。
 */
object PlanSchedule {

    /** 星期几的短标签，索引即 [DayMath.dayOfWeek] 的返回值（0=周日） */
    val WEEK_SHORT = listOf("日", "一", "二", "三", "四", "五", "六")

    /** 展示顺序按中文习惯从周一排起，周日放最后 */
    private val DISPLAY_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)

    /** 连续打卡的回溯上限（10 年）。防的是坏数据让下面的 while 空转 */
    private const val STREAK_LIMIT_DAYS = 3660

    /** 找「下一次该做」时最多往后找多少天，兜住永远找不到的情况 */
    private const val NEXT_SEARCH_DAYS = 400

    // ------------------------------------------------------------------ 判定

    /**
     * 这天在不在重复规则里：在起止区间内，且星期几命中。
     *
     * **不看 `enabled`**，也不判断有没有打过卡——调用方各有各的口径。
     * 空 [StudyPlan.weekdays] 按「每天」处理（见该字段的说明）。
     */
    fun isRepeatDay(plan: StudyPlan, day: Long): Boolean {
        if (day < plan.startDay) return false
        val end = plan.endDay
        if (end != null && day > end) return false
        if (plan.weekdays.isEmpty()) return true
        return DayMath.dayOfWeek(day) in plan.weekdays
    }

    /** 这天该做吗：重复规则命中 **且** 计划处于启用中 */
    fun isDueOn(plan: StudyPlan, day: Long): Boolean =
        plan.enabled && isRepeatDay(plan, day)

    fun isDoneOn(planId: String, day: Long, records: List<PlanRecord>): Boolean =
        records.any { it.planId == planId && it.day == day }

    /**
     * 今天该做却还没打卡的条数。首页入口的角标用它。
     *
     * 只数「今天该做」的，不数过期的——计划不是任务，昨天没打卡不该
     * 变成今天的债，那样数字只会越滚越大最后失去意义。
     */
    fun pendingCount(plans: List<StudyPlan>, records: List<PlanRecord>, day: Long): Int =
        plans.count { isDueOn(it, day) && !isDoneOn(it.id, day, records) }

    /**
     * 今天该做的计划。**没做的排在前面**，其次按创建时刻——
     * 一进页面最想看到的「还剩哪几件」浮在顶上，做完的沉下去。
     */
    fun dueToday(plans: List<StudyPlan>, records: List<PlanRecord>, day: Long): List<StudyPlan> =
        plans.filter { isDueOn(it, day) }
            .sortedWith(
                compareBy(
                    { isDoneOn(it.id, day, records) },
                    { it.createdAt },
                )
            )

    /** 今天不用做的计划：还没到起始日、已过结束日、星期几没命中，或已停用 */
    fun notDueToday(plans: List<StudyPlan>, day: Long): List<StudyPlan> =
        plans.filterNot { isDueOn(it, day) }.sortedBy { it.createdAt }

    /**
     * 从 [fromDay]（含）起，下一次该做的日子。找不到返回 null。
     *
     * 同样不看 `enabled`——调用方要判断「停用」自己看这个字段，
     * 「已停用，下次 9/28」比「已停用，下次 无」信息量大得多。
     */
    fun nextDueDay(plan: StudyPlan, fromDay: Long, searchDays: Int = NEXT_SEARCH_DAYS): Long? {
        var day = fromDay
        repeat(searchDays) {
            if (isRepeatDay(plan, day)) return day
            day++
        }
        return null
    }

    // ------------------------------------------------------------------ 统计

    /**
     * 连续打卡天数。
     *
     * 只沿着**计划自己的重复日**往回数，中间不是重复日的那些天直接跳过——
     * 只排周一三五的计划，周二没做不该算断卡。这是「按天连续」和
     * 「按次连续」的区别，也是这个功能最容易写错的地方。
     *
     * 今天该做但还没打卡时**不视为断卡**：今天还没过完，
     * 清早打开一看连胜归零会让人以为数据丢了。这时从上一个重复日往回数。
     *
     * 起点早于 [StudyPlan.startDay] 就停——那是计划还没生效的日子，
     * 不该拿它来要求用户打过卡。
     */
    fun streak(plan: StudyPlan, records: List<PlanRecord>, today: Long): Int {
        val done = records.asSequence()
            .filter { it.planId == plan.id }
            .map { it.day }
            .toHashSet()
        if (done.isEmpty()) return 0

        // 计划已结束的话，从结束日往回数（结束之后的日子不该继续要求打卡）
        var cursor = plan.endDay?.let { minOf(today, it) } ?: today
        if (cursor < plan.startDay) return 0

        if (isRepeatDay(plan, cursor) && cursor !in done) cursor--

        var count = 0
        var scanned = 0
        while (cursor >= plan.startDay && scanned < STREAK_LIMIT_DAYS) {
            if (isRepeatDay(plan, cursor)) {
                if (cursor in done) count++ else break
            }
            cursor--
            scanned++
        }
        return count
    }

    /**
     * 区间内「该做」的天数（含首尾）。区间会被裁剪到计划的有效期内，
     * 所以调用方不必自己先夹一遍起止日。
     */
    fun dueDayCount(plan: StudyPlan, fromDay: Long, toDay: Long): Int {
        val from = maxOf(fromDay, plan.startDay)
        val to = minOf(toDay, plan.endDay ?: toDay)
        if (from > to) return 0
        var n = 0
        var d = from
        while (d <= to) {
            if (isRepeatDay(plan, d)) n++
            d++
        }
        return n
    }

    /** 区间内**实际打了卡**的天数。只算该做的日子——非重复日的记录不计入 */
    fun doneDayCount(
        plan: StudyPlan,
        records: List<PlanRecord>,
        fromDay: Long,
        toDay: Long,
    ): Int {
        val from = maxOf(fromDay, plan.startDay)
        val to = minOf(toDay, plan.endDay ?: toDay)
        if (from > to) return 0
        return records.asSequence()
            .filter { it.planId == plan.id && it.day in from..to && isRepeatDay(plan, it.day) }
            .map { it.day }
            .distinct()
            .count()
    }

    /** 累计打卡天数（不限区间），详情页的「共完成 N 次」用 */
    fun totalDoneDays(plan: StudyPlan, records: List<PlanRecord>): Int =
        records.asSequence()
            .filter { it.planId == plan.id }
            .map { it.day }
            .distinct()
            .count()

    // ------------------------------------------------------------------ 展示

    private val WORKDAYS = setOf(1, 2, 3, 4, 5)
    private val WEEKEND = setOf(0, 6)

    /**
     * 重复规则的中文摘要。
     *
     * 「每天」「工作日」「周末」比「周一、周二…周日」好认得多，
     * 所以这几个常见组合单独成词；其余才逐日罗列。
     */
    fun repeatLabel(weekdays: Set<Int>): String = when {
        weekdays.isEmpty() || weekdays.size == 7 -> "每天"
        weekdays == WORKDAYS -> "工作日"
        weekdays == WEEKEND -> "周末"
        else -> DISPLAY_ORDER.filter { it in weekdays }
            .joinToString("、") { "周${WEEK_SHORT[it]}" }
    }
}
