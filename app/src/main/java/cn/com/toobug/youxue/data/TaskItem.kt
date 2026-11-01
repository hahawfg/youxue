package cn.com.toobug.youxue.data

import cn.com.toobug.youxue.util.DayMath

/**
 * 一条学习任务。
 *
 * 日期语义是「**区间**」，不是「某一个点」：
 * [startDay] 是这条任务开始要做的日子（一般就是创建当天），
 * [endDay] 是**截止日**——用户拖到哪个日期格上、或者在编辑里选哪个日期，改的都是它。
 * 于是在 [startDay]..[endDay] 这段区间里的**每一天**，这条任务都算「要做」，
 * 这正是日期条上角标会从今天一直亮到截止日的原因。
 *
 * 用 **epoch day**（1970-01-01 记为 0）而不是毫秒时间戳，是因为
 * 「任务安排在哪一天」是日历概念而非时刻概念。用时间戳存会引入时区与
 * 夏令时问题——同一条任务在不同时区回看可能落到前后一天。
 *
 * [endDay] 为 null 表示**未安排**（录入时截止日期留空），这时 [startDay] 也必然为 null。
 * 不变量是 `startDay <= endDay`：区间为空的话，任务在列表里哪一天都不会出现。
 */
data class TaskItem(
    val id: String,
    val title: String,
    val content: String = "",
    /** 开始要做的日子（本地日历天）。null = 未安排 */
    val startDay: Long? = null,
    /** 截止日（本地日历天）。null = 未安排 */
    val endDay: Long? = null,
    val done: Boolean = false,
    val createdAt: Long = 0L,
    /** 完成**时刻**（毫秒）。同一天内能分出先后，用于「已完成」区排序；未完成为 0 */
    val completedAt: Long = 0L,
    /** 紧急程度。只影响颜色，不影响顺序 */
    val priority: TaskPriority = TaskPriority.DEFAULT,
    /**
     * 手动排序权重，**越大越靠上**。
     *
     * 默认取 [createdAt]，所以「新添加的排在最上面」不需要额外规则——
     * 新建时刻天然最大。老数据没有这一列时也回落到 [createdAt]，
     * 于是迁移是零成本的：不需要改写任何一条已存在的记录。
     *
     * 手动拖动后由 [cn.com.toobug.youxue.task.TaskOrder.move] 统一重排成整段编号，
     * 不在这里做「取中点」之类的增量插入——中点会被反复插入挤到相邻整数，
     * 早晚有一天挤不下。
     */
    val order: Long = createdAt,
) {

    /**
     * 这条任务在第 [day] 天是否需要做——即 [day] 落在 开始日..截止日 之间。
     *
     * 不判断 [done]：调用方各有各的口径（列表要排除已完成的，
     * 日期条角标也算排除），把「完成」这个条件塞进来反而会让人误读。
     */
    fun coversDay(day: Long): Boolean {
        val from = startDay ?: return false
        val to = endDay ?: return false
        return day in from..to
    }

    /** 是否已过期：截止日在 [day] 之前且没做完 */
    fun isOverdue(day: Long): Boolean = !done && endDay != null && endDay < day

    /** 在哪一天完成的（本地日历天）。未完成返回 null */
    fun completedDay(): Long? = if (completedAt > 0L) DayMath.localDayOf(completedAt) else null

    /**
     * 重新安排截止日。日期非必填，所以 [endDay] 允许为 null（表示退回未安排）。
     *
     * 起始日的维护规则集中放在这里，是为了让「拖到日期上」和「在编辑弹层里改日期」
     * 走完全同一条逻辑——两处各写一遍迟早会不一致。
     *
     * - 清空截止日：起始日一并清空（没有区间的概念了）
     * - 从无到有：起始日取「今天」与截止日的较小者。之所以要和截止日取小，
     *   是为了守住 `start <= end` —— 用户可能选一个已经过去的截止日，
     *   若起始日仍取今天，区间就是空的，任务会「哪一天都不出现」
     * - 已有安排：保留原起始日（任务已经开始了，改截止日不该抹掉它的历史），
     *   仅在新截止日更早时把它夹过去
     */
    fun reschedule(endDay: Long?, today: Long): TaskItem {
        if (endDay == null) return copy(startDay = null, endDay = null)
        val from = startDay?.let { minOf(it, endDay) } ?: minOf(today, endDay)
        return copy(startDay = from, endDay = endDay)
    }

    companion object {
        /** 新建任务的占位 id 规则集中在这里，避免各处各写一遍 */
        fun newId(): String = java.util.UUID.randomUUID().toString()
    }
}
