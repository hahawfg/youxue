package com.youxue.app.task

import com.youxue.app.data.TaskItem

/**
 * 任务页的完整状态。
 *
 * 界面只读这一份，所有派生数据（分组、每日期角标数、日期条范围）都在这里算好，
 * 避免在 Composable 里各算各的导致不一致。
 */
data class TaskUiState(
    /** 是否还没从存储读到过数据。用来把「加载中」和「真的没有任务」区分开 */
    val loading: Boolean = true,
    val tasks: List<TaskItem> = emptyList(),
    val today: Long = 0L,
    /** 日期条上选中的那一天，同时也是列表内容的口径 */
    val selectedDay: Long = 0L,
    val groups: List<TaskGroup> = emptyList(),
    /** 日期条覆盖的连续日期区间 */
    val stripDays: List<Long> = emptyList(),
    /** 每个日期还没做完的件数，用于角标。只包含大于 0 的日期 */
    val dayCounts: Map<Long, Int> = emptyMap(),
) {
    /** 待办总数（未完成） */
    val openCount: Int get() = tasks.count { !it.done }

    /** 已过期的待办数。相对「今天」而不是选中日——顶栏说的是全局状况 */
    val overdueCount: Int get() = tasks.count { it.isOverdue(today) }

    /** 选中日需要做的件数 */
    val selectedCount: Int
        get() = groups.firstOrNull { it.id == TaskGrouper.GROUP_ACTIVE }?.tasks?.size ?: 0

    /** 一条任务都没有 */
    val isEmpty: Boolean get() = !loading && tasks.isEmpty()

    /** 有任务，但选中的这一天没有内容可显示 */
    val isDayEmpty: Boolean get() = !loading && tasks.isNotEmpty() && groups.isEmpty()

    fun countOn(day: Long): Int = dayCounts[day] ?: 0
}
