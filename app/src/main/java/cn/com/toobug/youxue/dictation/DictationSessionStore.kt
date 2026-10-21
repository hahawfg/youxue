package cn.com.toobug.youxue.dictation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 听写会话的「交接点」。
 *
 * 为什么需要它：听写页与结果页是两个独立的路由，各自持有独立的
 * [DictationViewModel] 实例（`viewModel()` 默认按 NavBackStackEntry 作用域）。
 * 听写页把结果写进自己的实例，结果页读自己的实例，什么也读不到——
 * 这正是之前结果页一片空白的原因。
 *
 * 解决办法是在两者之间放一个**应用级单例**做交接：听写结束时把结果放进来，
 * 结果页取走并消费掉。比把整个词语列表和时长塞进导航 URL 参数更干净，
 * 也不用把一个 ViewModel 跨路由共享（那样生命周期会很别扭）。
 */
object DictationSessionStore {

    private val _result = MutableStateFlow<DictationResult?>(null)

    /** 最近一次听写的结果，供结果页观察 */
    val result: StateFlow<DictationResult?> = _result.asStateFlow()

    /** 听写结束时写入结果 */
    fun publish(result: DictationResult) {
        _result.value = result
    }

    /**
     * 取走结果（读取后不清空）。
     *
     * 刻意不清空：结果页在配置变更（旋转屏幕、切深色模式）时会重组，
     * 清空会导致页面又变空白。改为在新一次听写开始时覆盖。
     */
    fun current(): DictationResult? = _result.value

    /** 开始新一次听写时调用，清掉上一次的残留 */
    fun clear() {
        _result.value = null
    }
}
