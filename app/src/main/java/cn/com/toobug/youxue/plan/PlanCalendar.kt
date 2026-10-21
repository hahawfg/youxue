package cn.com.toobug.youxue.plan

import cn.com.toobug.youxue.data.PlanRecord
import cn.com.toobug.youxue.data.StudyPlan
import cn.com.toobug.youxue.util.DayMath

/**
 * 详情页月历的数据装配。
 *
 * 同样是纯函数，理由同 [PlanSchedule]：界面只负责画，判定都在这里，
 * 免得「格子里到底算不算完成」这种事在 Composable 里各写一遍。
 */
object PlanCalendar {

    /**
     * 月历上的一格。
     *
     * 四个布尔值各自独立，不合并成一个枚举——界面上它们是四种**可以叠加**的
     * 视觉状态（比如「今天」既可能是重复日、也可能已完成），
     * 合成枚举会立刻出现「今天是重复日且已完成」这种要额外补的格子。
     */
    data class Cell(
        val day: Long,
        /** 属于当前显示的月份。false = 上月/下月的补位格 */
        val inMonth: Boolean,
        /** 按重复规则这天该做 */
        val repeatDay: Boolean,
        val done: Boolean,
        val today: Boolean,
        /** 还没到的日子。未来的格子不显示「漏卡」，那只是还没到 */
        val future: Boolean,
    )

    /** 一个月的成绩：该做几次、做了几次 */
    data class Summary(val due: Int, val done: Int) {
        /** 完成率，0..1。该做 0 次时按 1 处理，避免界面出现 0/0 */
        val rate: Float get() = if (due <= 0) 1f else done.toFloat() / due
    }

    /**
     * 生成 [year] 年 [month] 月的 42 格月历（周日起始）。
     *
     * 42 格是固定的 6 行，含上下月补位——高度恒定，翻月时网格不跳
     * （见 [DayMath.monthGrid]）。
     */
    fun monthCells(
        plan: StudyPlan,
        records: List<PlanRecord>,
        year: Int,
        month: Int,
        today: Long,
    ): List<Cell> {
        val doneDays = records.asSequence()
            .filter { it.planId == plan.id }
            .map { it.day }
            .toHashSet()

        return DayMath.monthGrid(year, month).map { day ->
            val ymd = DayMath.ymd(day)
            Cell(
                day = day,
                inMonth = ymd.year == year && ymd.month == month,
                repeatDay = PlanSchedule.isRepeatDay(plan, day),
                done = day in doneDays,
                today = day == today,
                future = day > today,
            )
        }
    }

    /**
     * 本月的成绩。
     *
     * 只看**本月已过去的日子**：把还没到的日子算进分母，
     * 月初打开会看到完成率惨不忍睹，那是假的。
     * 补位格（上月/下月）同样不计入。
     */
    fun summaryOfMonth(cells: List<Cell>): Summary {
        val counted = cells.filter { it.inMonth && !it.future }
        return Summary(
            due = counted.count { it.repeatDay },
            done = counted.count { it.repeatDay && it.done },
        )
    }

    /** 最近 [days] 天的成绩（含今天），详情页的「近 30 天完成率」用 */
    fun summaryOfRecent(
        plan: StudyPlan,
        records: List<PlanRecord>,
        today: Long,
        days: Int,
    ): Summary {
        val from = today - (days - 1)
        return Summary(
            due = PlanSchedule.dueDayCount(plan, from, today),
            done = PlanSchedule.doneDayCount(plan, records, from, today),
        )
    }
}
