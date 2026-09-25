package com.youxue.app.data

/**
 * 一条听写学习计划。
 *
 * 和 [TaskItem] 是**两种东西**，故意不复用同一张表：
 * 任务是「某段日期里要做的一次性事项」——区间内每天都出现，做完就结束了；
 * 计划是「每周固定几天重复的长期习惯」——这周做完了，下周还要再做，
 * 而且**每一天各自留一条完成记录**。把两者塞进一个模型，
 * 「今天要不要做」就得靠一堆开关去区分，迟早出错。
 *
 * [weekdays] 用 `0=周日 .. 6=周六`，与 [com.youxue.app.util.DayMath.dayOfWeek] 一致。
 * **空集合按「每天」处理**——这是解码坏数据时的兜底：一条哪天都不触发的计划
 * 等于凭空消失，用户会以为界面出了 bug。界面侧不会产生空集合，
 * 「每天」在 UI 上就是七个全选。
 *
 * 日期用 **epoch day**（1970-01-01 记为 0）而不是毫秒时间戳，理由同 [TaskItem]：
 * 「安排在哪一天」是日历概念，用时间戳会引入时区与夏令时偏移。
 */
data class StudyPlan(
    val id: String,
    val title: String,
    /** 每周哪几天要做。空集合 = 每天 */
    val weekdays: Set<Int>,
    /** 生效起始日（本地日历天）。早于这一天的日期一律不做 */
    val startDay: Long,
    /** 结束日（本地日历天），null = 长期有效 */
    val endDay: Long? = null,
    val enabled: Boolean = true,
    /** 创建**时刻**（毫秒）。同一天建的两条能分出先后，列表排序用 */
    val createdAt: Long = 0L,
) {
    companion object {
        fun newId(): String = java.util.UUID.randomUUID().toString()
    }
}
