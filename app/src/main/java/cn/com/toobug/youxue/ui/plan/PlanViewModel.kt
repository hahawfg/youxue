package cn.com.toobug.youxue.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.toobug.youxue.data.PlanRecord
import cn.com.toobug.youxue.data.PlanRepository
import cn.com.toobug.youxue.data.StudyPlan
import cn.com.toobug.youxue.plan.PlanSchedule
import cn.com.toobug.youxue.util.DayMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 列表里的一个计划条目。派生字段在这里算好，Composable 只负责画。
 */
data class PlanEntry(
    val plan: StudyPlan,
    /** 今天打过卡没有 */
    val done: Boolean,
    val streak: Int,
    /** 重复规则的中文摘要，如「工作日」 */
    val repeatLabel: String,
    /** 今天不用做的那些，下一次是哪天。已停用或已结束可能为 null */
    val nextDay: Long?,
)

/**
 * 计划页的完整状态。
 *
 * 界面只读这一份，分组和统计都在这里算好，避免在 Composable 里各算各的导致不一致。
 */
data class PlanUiState(
    /** 是否还没从存储读到过数据。用来把「加载中」和「真的没有计划」区分开 */
    val loading: Boolean = true,
    val today: Long = 0L,
    val plans: List<StudyPlan> = emptyList(),
    val records: List<PlanRecord> = emptyList(),
    /** 今天要做的（没做的在前） */
    val dueToday: List<PlanEntry> = emptyList(),
    /** 今天不用做的 */
    val others: List<PlanEntry> = emptyList(),
) {
    /** 今天还差几项没打卡。首页入口的角标也用它 */
    val pendingCount: Int get() = dueToday.count { !it.done }

    /** 今天该做的都做完了 */
    val allDoneToday: Boolean get() = dueToday.isNotEmpty() && pendingCount == 0

    /** 一条计划都没有 */
    val isEmpty: Boolean get() = !loading && plans.isEmpty()

    /** 有计划，但今天没有要做的 */
    val isTodayEmpty: Boolean get() = !loading && plans.isNotEmpty() && dueToday.isEmpty()

    fun planById(id: String): StudyPlan? = plans.firstOrNull { it.id == id }
}

/**
 * 计划页的状态持有者。
 *
 * 遵循项目既定约定：**UI 状态是唯一真源**，存储只做落盘。
 * 进入页面读一次存储，之后每次改动先更新内存（界面立刻响应），
 * 再异步写回去。反过来「订阅存储并回灌界面」是错的——打卡时落盘的往返延迟
 * 会让勾选状态闪回未勾选，看着就像「点了没反应」。
 *
 * 「今天」在创建时取一次就固定下来（同 [cn.com.toobug.youxue.ui.task.TaskViewModel]）。
 * 跨零点时页面仍按旧日期算，属于可接受的取舍：为此引入定时刷新
 * 反而多一处会出错的活状态。
 *
 * 这个 ViewModel 被**列表页和详情页共用**（详情页通过导航图的
 * `getBackStackEntry` 拿到同一个实例），所以详情页里打卡、列表页能立刻看到——
 * 两个路由各持一个实例的话，从详情页返回列表页会看到过期的勾选状态。
 */
class PlanViewModel(
    private val repository: PlanRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PlanUiState())
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    private val today: Long = DayMath.today()

    init {
        viewModelScope.launch {
            _state.value = recompute(
                plans = repository.plans.first(),
                records = repository.records.first(),
            )
        }
    }

    // ------------------------------------------------------------- 写操作

    /**
     * 新建计划。
     *
     * [weekdays] 为空按「每天」处理（见 [StudyPlan.weekdays]），
     * 界面上不允许取消到零个，这里只是兜底。
     */
    fun add(
        title: String,
        weekdays: Set<Int>,
        startDay: Long,
        endDay: Long?,
    ) {
        val t = title.trim()
        if (t.isEmpty()) return
        val plan = StudyPlan(
            id = StudyPlan.newId(),
            title = t,
            weekdays = weekdays,
            startDay = startDay,
            endDay = endDay?.takeIf { it >= startDay },
            enabled = true,
            createdAt = System.currentTimeMillis(),
        )
        applyPlans(_state.value.plans + plan)
    }

    /**
     * 编辑。历史打卡记录**不动**——改规则不该把已经打过的卡抹掉。
     *
     * [enabled] 一并在这里改，而不是保存后再单独调一次 [setEnabled]：
     * 两次调用就是两次整体落盘，中间还会有一帧「规则已改但还没停用」的中间态。
     */
    fun update(
        id: String,
        title: String,
        weekdays: Set<Int>,
        startDay: Long,
        endDay: Long?,
        enabled: Boolean,
    ) {
        val t = title.trim()
        if (t.isEmpty()) return
        applyPlans(
            _state.value.plans.map {
                if (it.id != id) it
                else it.copy(
                    title = t,
                    weekdays = weekdays,
                    startDay = startDay,
                    endDay = endDay?.takeIf { e -> e >= startDay },
                    enabled = enabled,
                )
            }
        )
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val current = _state.value.plans
        val next = current.map { if (it.id == id) it.copy(enabled = enabled) else it }
        if (next == current) return
        applyPlans(next)
    }

    /**
     * 今天的打卡开关。
     *
     * 同一天重复点不会产生第二条记录（过滤掉旧的再加），
     * 所以「连续打卡 N 天」不会被连点灌水。
     */
    fun toggleToday(id: String) {
        val records = _state.value.records
        val next = if (PlanSchedule.isDoneOn(id, today, records)) {
            records.filterNot { it.planId == id && it.day == today }
        } else {
            records + PlanRecord(
                planId = id,
                day = today,
                completedAt = System.currentTimeMillis(),
            )
        }
        applyRecords(next)
    }

    /** 删除计划，**连同它的打卡记录一起**——留着孤儿记录只会越积越多 */
    fun remove(id: String) {
        applyPlans(_state.value.plans.filterNot { it.id == id })
        applyRecords(_state.value.records.filterNot { it.planId == id })
    }

    // ------------------------------------------------------------- 内部

    private fun applyPlans(plans: List<StudyPlan>) {
        _state.value = recompute(plans, _state.value.records)
        viewModelScope.launch { repository.savePlans(plans) }
    }

    private fun applyRecords(records: List<PlanRecord>) {
        _state.value = recompute(_state.value.plans, records)
        viewModelScope.launch { repository.saveRecords(records) }
    }

    private fun recompute(
        plans: List<StudyPlan>,
        records: List<PlanRecord>,
    ): PlanUiState = PlanUiState(
        loading = false,
        today = today,
        plans = plans,
        records = records,
        dueToday = PlanSchedule.dueToday(plans, records, today).map { entryOf(it, records, true) },
        others = PlanSchedule.notDueToday(plans, today).map { entryOf(it, records, false) },
    )

    private fun entryOf(
        plan: StudyPlan,
        records: List<PlanRecord>,
        due: Boolean,
    ): PlanEntry = PlanEntry(
        plan = plan,
        done = PlanSchedule.isDoneOn(plan.id, today, records),
        streak = PlanSchedule.streak(plan, records, today),
        repeatLabel = PlanSchedule.repeatLabel(plan.weekdays),
        // 今天要做的就不用再算下次了，少一次查找
        nextDay = if (due) null else PlanSchedule.nextDueDay(plan, today),
    )

    companion object {
        fun factory(repository: PlanRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlanViewModel(repository) as T
            }
    }
}
