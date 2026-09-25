package com.youxue.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.youxue.app.data.PlanRepository
import com.youxue.app.data.SettingsRepository
import com.youxue.app.data.TaskRepository
import com.youxue.app.plan.PlanSchedule
import com.youxue.app.task.TaskGrouper
import com.youxue.app.util.DayMath
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 首页展示用的统计数据 */
data class HomeStats(
    val totalSessions: Int = 0,
    val totalMillis: Long = 0L,
)

class HomeViewModel(
    repository: SettingsRepository,
    planRepository: PlanRepository,
    taskRepository: TaskRepository,
) : ViewModel() {

    val stats: StateFlow<HomeStats> = repository.statistics
        .map { HomeStats(it.totalSessions, it.totalMillis) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HomeStats(),
        )

    /** 是否存在上次未清空的词语草稿，用于在入口卡片上提示 */
    val hasDraft: StateFlow<Boolean> = repository.draftWords
        .map { it.isNotBlank() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /**
     * 今天还没打卡的计划数，用于在「学习计划」入口上挂角标。
     *
     * 这里订阅存储而不是像计划页那样只在进入时读一次，是因为**打卡发生在别的页面**：
     * 用户在计划页打完卡返回首页，角标必须跟着消失。
     * 计划页读一次就够（它自己就是改数据的人），首页必须是响应式的。
     *
     * 顺带把「跨零点」也处理掉了：每次流的发射都重新取一次今天。
     */
    val pendingToday: StateFlow<Int> = combine(
        planRepository.plans,
        planRepository.records,
    ) { plans, records ->
        PlanSchedule.pendingCount(plans, records, DayMath.today())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0,
    )

    /**
     * 「学习任务」入口的角标：到目前还欠着几件（**含已过期**）。
     *
     * 与计划角标同一个道理——**改动发生在任务页，首页只能订阅**。
     * 用户在任务页勾掉一条返回首页，角标必须跟着减。
     *
     * 口径是「已过期 + 今天要做」（见 `TaskGrouper.pendingCount`），
     * 刻意比日期条今天那格的角标多算了过期的：入口角标不能瞒着用户，
     * 今天没有新安排但欠着旧的，也得亮。
     */
    val taskPending: StateFlow<Int> = taskRepository.tasks
        .map { TaskGrouper.pendingCount(it, DayMath.today()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0,
        )

    companion object {
        fun factory(
            repository: SettingsRepository,
            planRepository: PlanRepository,
            taskRepository: TaskRepository,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return HomeViewModel(repository, planRepository, taskRepository) as T
                }
            }
    }
}
