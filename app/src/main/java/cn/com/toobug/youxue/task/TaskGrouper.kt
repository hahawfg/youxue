package cn.com.toobug.youxue.task

import cn.com.toobug.youxue.data.TaskItem

/**
 * 分组的高亮语义。界面据此决定用什么颜色，而不是去匹配标题字符串。
 */
enum class GroupAccent {
    /** 普通 */
    NORMAL,

    /** 今天 */
    TODAY,

    /** 已过期 */
    OVERDUE,

    /** 弱化（未安排、已完成） */
    MUTED,
}

/**
 * 列表里的一组任务。
 *
 * 不再带「日期」字段。任务现在是一个**区间**，同一条任务会同时属于好几天的列表，
 * 「这组等于哪一天」这件事已经没有意义了——列表的内容由「选中的那一天」决定，
 * 分组只是在那一天内部再分个性质（过期 / 要做 / 未安排 / 已完成）。
 */
data class TaskGroup(
    val id: String,
    val label: String,
    val hint: String?,
    val accent: GroupAccent,
    val tasks: List<TaskItem>,
)

/**
 * 任务的切分逻辑。
 *
 * 做成不依赖 Android 的纯函数，是为了能直接单测——切分一旦出错
 * （比如过了期的被丢到看不见的地方），用户看到的就是任务凭空消失。
 *
 * 组织顺序：已过期 → 要做 → 未安排 → 已完成。
 * 过期的排最前是因为它最需要处理；未安排常驻在列表里，因为它是拖拽的来源；
 * 已完成沉到最后，不干扰前后两天。
 */
object TaskGrouper {

    /**
     * 日期条角标：第 [day] 天还剩几件。
     *
     * 一条任务在它的 开始日..截止日 之间的**每一天**都算数，所以角标会从今天
     * 连着亮到截止日——这是「截止日前每天都要提醒」的直接体现。
     * 只算 [days] 这个范围（也就是日期条实际画出来的那些格子），
     * 免得给永远不会显示的日期算计数。
     */
    fun dayCounts(tasks: List<TaskItem>, days: List<Long>): Map<Long, Int> {
        val open = tasks.filter { !it.done && it.startDay != null && it.endDay != null }
        if (open.isEmpty() || days.isEmpty()) return emptyMap()
        return buildMap {
            days.forEach { day ->
                val count = open.count { it.coversDay(day) }
                if (count > 0) put(day, count)
            }
        }
    }

    /**
     * 首页入口角标：到 [day] 为止还欠着几件。
     *
     * 口径与 [dayCounts] **刻意不同**，别顺手统一成一份：
     * - [dayCounts] 是日期条上「第几天」那个角标，只数当天落在区间内的任务。
     *   过期的任务不是「今天要做」，点亮今天那格会是假话。
     * - 这里是首页入口的角标，回答的是「我这儿还有没有活」，所以**把过期的一并算上**。
     *   否则用户今天没有新安排、却欠着三条昨天该做的，首页一个角标都不亮，
     *   点进去才发现有事——入口角标最不该有的失效方式就是「瞒着」。
     *
     * 判据写成「已过期 ∪ 今天要做」，而不是等价的 `startDay <= day`：
     * 这样它**恒等于任务页上「已过期」+「今天要做」两组之和**，
     * 首页显示的数字和点进去看到的条数一定对得上（有测试钉住）。
     * 未安排的任务不算——它没有日期，谈不上「该完成」。
     */
    fun pendingCount(tasks: List<TaskItem>, day: Long): Int =
        tasks.count { !it.done && (it.isOverdue(day) || it.coversDay(day)) }

    /**
     * 按「选中的那一天」切出列表内容。
     *
     * - **已过期**：截止日在选中日之前且没做完。刻意不隐藏——不管切到哪一天都看得见，
     *   否则一次漏做就再也找不回来了。
     * - **要做**：选中日落在 开始日..截止日 区间内。
     * - **未安排**：没有日期的任务。**不随日期切换消失**，因为它是拖拽的来源，
     *   跟着日期藏起来的话就没法把它拖到别的日期上了。
     * - **已完成**：选中日这一天完成的。既给「今天做完了什么」一个交代，
     *   也留一个勾错之后的撤销入口。
     */
    fun group(tasks: List<TaskItem>, selectedDay: Long, today: Long): List<TaskGroup> {
        val groups = mutableListOf<TaskGroup>()

        val overdue = tasks.asSequence()
            .filter { it.isOverdue(selectedDay) }
            .sortedWith(compareBy({ it.endDay }, { it.createdAt }))
            .toList()
        if (overdue.isNotEmpty()) {
            groups += TaskGroup(
                id = GROUP_OVERDUE,
                label = "已过期",
                hint = "截止日已经过去，还没做完",
                accent = GroupAccent.OVERDUE,
                tasks = overdue,
            )
        }

        val active = tasks.asSequence()
            .filter { !it.done && it.coversDay(selectedDay) }
            .sortedWith(compareBy({ it.endDay }, { it.createdAt }))
            .toList()
        if (active.isNotEmpty()) {
            groups += TaskGroup(
                id = GROUP_ACTIVE,
                label = if (selectedDay == today) "今天要做" else "要做",
                hint = null,
                accent = if (selectedDay == today) GroupAccent.TODAY else GroupAccent.NORMAL,
                tasks = active,
            )
        }

        val unscheduled = tasks.asSequence()
            .filter { !it.done && it.endDay == null }
            .sortedByDescending { it.createdAt }
            .toList()
        if (unscheduled.isNotEmpty()) {
            groups += TaskGroup(
                id = GROUP_UNSCHEDULED,
                label = "未安排",
                hint = "长按卡片，拖到上面日期上",
                accent = GroupAccent.MUTED,
                tasks = unscheduled,
            )
        }

        val doneOnDay = tasks.asSequence()
            .filter { it.done && it.completedDay() == selectedDay }
            .sortedByDescending { it.completedAt }
            .toList()
        if (doneOnDay.isNotEmpty()) {
            groups += TaskGroup(
                id = GROUP_DONE,
                label = if (selectedDay == today) "今天完成" else "这天完成",
                hint = null,
                accent = GroupAccent.MUTED,
                tasks = doneOnDay,
            )
        }

        return groups
    }

    /**
     * 日期条要覆盖的天。
     *
     * 默认是「今天往前 [BACK_DAYS] 天、往后 [FORWARD_DAYS] 天」的**连续**区间。
     * 之所以还要把已有任务的日期并进来：如果用户用日期选择器把截止日排到了区间外，
     * 那个日期必须也有格子可拖、有角标可看，否则任务在列表里有、在日期条上找不到。
     *
     * 区间两端都要纳入：起始日决定「从哪天开始有角标」，截止日决定「哪天停」。
     *
     * 只算**未完成**的任务——已完成的不需要格子也不需要角标，
     * 若把它们的历史日期也算进来，用久了日期条会被越撑越长。
     *
     * 连续很重要：不能只画「有任务的那几天」，否则相邻两格可能相差一个月，
     * 看上去却是挨着的，会误导人。
     */
    fun stripDays(tasks: List<TaskItem>, today: Long): List<Long> {
        var start = today - BACK_DAYS
        var end = today + FORWARD_DAYS

        tasks.asSequence().filter { !it.done }.forEach { t ->
            val from = t.startDay ?: t.endDay ?: return@forEach
            val to = t.endDay ?: return@forEach
            if (from < start) start = maxOf(from, today - MAX_SPAN_DAYS)
            if (to > end) end = minOf(to, today + MAX_SPAN_DAYS)
        }
        return (start..end).toList()
    }

    const val GROUP_OVERDUE = "overdue"
    const val GROUP_ACTIVE = "active"
    const val GROUP_UNSCHEDULED = "unscheduled"
    const val GROUP_DONE = "done"

    /** 默认往前看一周，够回看刚过去的安排 */
    const val BACK_DAYS = 7L

    /** 默认往后看两周，覆盖「这个月底前」这类常见计划 */
    const val FORWARD_DAYS = 14L

    /** 超出范围的日期最多把日期条撑到这么远，防止有人填了 2099 年把格子刷爆 */
    const val MAX_SPAN_DAYS = 180L
}
