package cn.com.toobug.youxue.ui.task

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.toobug.youxue.data.TaskItem
import cn.com.toobug.youxue.data.TaskRepository
import cn.com.toobug.youxue.task.TaskGrouper
import cn.com.toobug.youxue.task.TaskUiState
import cn.com.toobug.youxue.util.DayMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 任务页的状态持有者。
 *
 * 遵循项目既定约定：**UI 状态是唯一真源**，存储只做落盘。
 * 进入页面读一次存储，之后每次改动先更新内存（界面立刻响应），
 * 再异步把整份列表写回去。反过来「订阅存储并回灌界面」是错的——
 * 拖动任务时落盘的往返延迟会让卡片弹回原位，手感就是「拖不动」。
 *
 * 「今天」在创建时取一次就固定下来。跨零点时页面仍按旧日期算区间，
 * 属于可接受的取舍：为此引入定时刷新反而增加了一处会出错的活状态。
 */
class TaskViewModel(
    private val repository: TaskRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TaskUiState())
    val state: StateFlow<TaskUiState> = _state.asStateFlow()

    private val today: Long = DayMath.today()

    init {
        viewModelScope.launch {
            _state.value = recompute(repository.tasks.first(), today)
        }
    }

    // ------------------------------------------------------------- 写操作

    /**
     * 切换日期条上选中的那一天。列表内容整体换一套（过期 / 要做 / 这天完成的）。
     */
    fun selectDay(day: Long) {
        val current = _state.value
        if (current.selectedDay == day) return
        _state.value = recompute(current.tasks, day)
    }

    /**
     * 新建任务。
     *
     * [endDay] 允许为 null —— 这正是「日期非必填」的落点：
     * 先记下来，之后拖到日期上再安排。起始日由 [TaskItem.reschedule] 统一维护。
     */
    fun add(title: String, content: String, endDay: Long?) {
        val t = title.trim()
        if (t.isEmpty()) return
        val item = TaskItem(
            id = TaskItem.newId(),
            title = t,
            content = content.trim(),
            createdAt = System.currentTimeMillis(),
        ).reschedule(endDay, today)
        apply(_state.value.tasks + item)
    }

    fun update(id: String, title: String, content: String, endDay: Long?) {
        val t = title.trim()
        if (t.isEmpty()) return
        apply(
            _state.value.tasks.map {
                if (it.id != id) it
                else it.copy(title = t, content = content.trim()).reschedule(endDay, today)
            }
        )
    }

    /**
     * 拖动落点：把截止日改到某一天（[endDay] 为 null 表示取消日期）。
     *
     * 先比对再落盘：拖动常见的结果是「又放回了原来的格子」，
     * 这种情况不该触发一次无意义的写盘。
     */
    fun moveTo(id: String, endDay: Long?) {
        val current = _state.value.tasks
        val next = current.map { if (it.id == id) it.reschedule(endDay, today) else it }
        if (next == current) return
        apply(next)
    }

    fun toggleDone(id: String) {
        apply(
            _state.value.tasks.map {
                if (it.id != id) it
                else if (it.done) it.copy(done = false, completedAt = 0L)
                else it.copy(done = true, completedAt = System.currentTimeMillis())
            }
        )
    }

    fun remove(id: String) {
        apply(_state.value.tasks.filterNot { it.id == id })
    }

    // ------------------------------------------------------------- 内部

    private fun apply(tasks: List<TaskItem>) {
        _state.value = recompute(tasks, _state.value.selectedDay)
        viewModelScope.launch { repository.save(tasks) }
    }

    private fun recompute(tasks: List<TaskItem>, selectedDay: Long): TaskUiState {
        val strip = TaskGrouper.stripDays(tasks, today)
        return TaskUiState(
            loading = false,
            tasks = tasks,
            today = today,
            selectedDay = selectedDay,
            groups = TaskGrouper.group(tasks, selectedDay, today),
            stripDays = strip,
            dayCounts = TaskGrouper.dayCounts(tasks, strip),
        )
    }

    companion object {
        fun factory(repository: TaskRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TaskViewModel(repository) as T
            }
    }
}
