package cn.com.toobug.youxue.data

/**
 * 一次打卡：某条计划在某一天被标记为已完成。
 *
 * 只记「哪一天」和「什么时候点的」两件事，刻意不记听写了几个词、用了多久——
 * 计划目前和听写流程是分开的（用户明确选了「纯打卡提醒」），
 * 那些数据在计划这一侧根本拿不到。留两个恒为 0 的字段只会让人误以为它们有用。
 *
 * [day] 按 **epoch day** 存，而不是从 [completedAt] 现推：
 * 「9 月 26 日完成了」是日历概念，用时间戳换算会受时区影响
 * （详见 [cn.com.toobug.youxue.util.DayMath] 的说明）。
 * 同时保留 [completedAt] 这个真实**时刻**，是为了同一天内能排出先后，
 * 也让「几点打的卡」有据可查。
 *
 * 不变量：**同一条计划同一天最多一条记录**。重复点击是覆盖而不是新增
 * （由 PlanViewModel 保证），否则「连续打卡 N 天」会被连点搞乱。
 */
data class PlanRecord(
    val planId: String,
    val day: Long,
    val completedAt: Long,
)
