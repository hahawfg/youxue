package cn.com.toobug.youxue.dictation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.toobug.youxue.data.DictationRecord
import cn.com.toobug.youxue.data.DictationSettings
import cn.com.toobug.youxue.data.SettingsRepository
import cn.com.toobug.youxue.tts.SpeakResult
import cn.com.toobug.youxue.tts.SpeechLanguage
import cn.com.toobug.youxue.tts.TtsEngine
import cn.com.toobug.youxue.tts.TtsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 听写会话的驱动器。
 *
 * 整个会话由一条协程顺序推进，逐个处理词语：
 *
 * ```
 * 预备倒计时 → for 每个词 { 朗读 → 等待书写 } → 结束语 → 结算
 * ```
 *
 * 「自动推进」和「手动点击下一个」共用同一条等待逻辑：自动模式下等待
 * 倒计时走完，手动模式下等待用户点击。这样两种模式的行为不会分叉。
 */
class DictationViewModel(
    private val ttsEngine: TtsEngine,
    private val repository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DictationUiState())
    val uiState: StateFlow<DictationUiState> = _uiState.asStateFlow()

    private val _result = MutableStateFlow<DictationResult?>(null)
    val result: StateFlow<DictationResult?> = _result.asStateFlow()

    /**
     * 当前「每字时长」，供听写页的滑块显示。
     *
     * 由会话快照 [settings] 单独镜像一份而不是让 UI 去读 DataStore：
     * UI 拿到的必须是**本次会话正在用**的那个值（改完立刻生效），
     * 而 DataStore 的往返有一次落盘延迟，直接订阅会让滑块在拖动后弹回去。
     */
    private val _perCharSeconds = MutableStateFlow(DictationSettings.DEFAULT.perCharSeconds)
    val perCharSeconds: StateFlow<Float> = _perCharSeconds.asStateFlow()

    val ttsState: StateFlow<TtsState> = ttsEngine.state

    /** 本次会话用的设置快照 */
    private var settings: DictationSettings = DictationSettings.DEFAULT

    /** 主循环 */
    private var sessionJob: Job? = null

    /**
     * 当前词的**等待截止时刻**（`System.currentTimeMillis()` 口径），0 表示不在等待。
     *
     * 用「还剩多少毫秒」的递减计数器做不到「听写中改每字时长，当前词重新计时」：
     * 计数器在等待循环里是个局部变量，外部改了 `uiState.remainingMillis`
     * 下一帧就被真实值覆盖回去——这个坑在「下一个」按钮上已经踩过一次
     * （见 `waitForInterval` 的注释）。
     *
     * 换成截止时刻后，**调时长就等于挪动截止时刻**：等待循环每帧用
     * `deadline - now` 重算剩余，外部改动自然生效，也没有累积误差。
     */
    @Volatile
    private var waitDeadline = 0L

    /** 当前等待的总时长，用于算进度环比例；与 [waitDeadline] 一起更新 */
    @Volatile
    private var waitTotalMillis = 0L

    /** 用户点「下一个」时完成它，用于立即结束当前等待（两种模式都会登记） */
    private var skipSignal: CompletableDeferred<Unit>? = null

    /**
     * 当前词还在朗读、尚未进入等待阶段时用户就点了「下一个」——先记下来，
     * 朗读一结束立刻免掉这一词的等待。
     *
     * 不这么做的话，用户在朗读那两三秒里点按钮会「点了没反应」，
     * 只能等朗读结束、进入等待后再点一次。
     */
    private var skipRequested = false

    /** 进入听写的时刻（暂停时不推进它，保证 elapsed 不含暂停时长） */
    private var runStartedAt = 0L

    /** 暂停前已累计的时长 */
    private var accumulatedMillis = 0L

    // ------------------------------------------------------------- 启动

    /** 开始一次听写。[rawText] 为多行词语文本 */
    fun start(rawText: String) {
        val words = WordParser.parse(rawText)
        if (words.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "请至少录入一个词语") }
            return
        }
        viewModelScope.launch {
            settings = repository.loadSettings()
            repository.saveDraft(rawText)
            launchSession(words)
        }
    }

    /** 从草稿恢复并开始（听写页进入时调用） */
    fun startFromDraftIfNeeded() {
        if (sessionJob?.isActive == true) return
        if (_uiState.value.words.isNotEmpty()) return
        viewModelScope.launch {
            val draft = repository.draftWords.first()
            if (draft.isNotBlank()) start(draft)
        }
    }

    /**
     * 用同一批词语再来一遍。
     *
     * 优先用当前实例里的词语，没有则回退到草稿——因为「再听一遍」是在结果页
     * 触发的，而结果页是另一个 ViewModel 实例，它内部没有词语列表。
     */
    fun restart() {
        val words = _uiState.value.words.map { it.text }
        if (words.isNotEmpty()) {
            launchSession(words)
            return
        }
        viewModelScope.launch {
            settings = repository.loadSettings()
            val draft = repository.draftWords.first()
            if (draft.isNotBlank()) {
                val parsed = WordParser.parse(draft)
                if (parsed.isNotEmpty()) launchSession(parsed)
            }
        }
    }

    private fun launchSession(words: List<String>) {
        sessionJob?.cancel()
        _result.value = null
        DictationSessionStore.clear()
        accumulatedMillis = 0L
        skipRequested = false
        waitDeadline = 0L
        _perCharSeconds.value = settings.perCharSeconds
        _uiState.value = buildInitialState(words, settings)
        sessionJob = viewModelScope.launch { runSession() }
    }

    private fun buildInitialState(
        words: List<String>,
        settings: DictationSettings,
    ): DictationUiState {
        val runtimes = words.mapIndexed { i, w ->
            WordRuntime(
                index = i,
                text = w,
                intervalMillis = WordParser.intervalMillis(
                    w, settings.perCharSeconds, settings.minSeconds, settings.maxSeconds,
                ),
            )
        }
        return DictationUiState(
            phase = DictationPhase.Preparing(settings.prepareSeconds.coerceAtLeast(0)),
            words = runtimes,
            currentIndex = 0,
            remainingMillis = 0L,
            currentIntervalMillis = 0L,
            elapsedMillis = 0L,
        )
    }

    // ----------------------------------------------------------- 主循环

    private suspend fun CoroutineScope.runSession() {
        // ---- 预备倒计时 ----
        val prepare = settings.prepareSeconds
        if (prepare > 0) {
            for (s in prepare downTo 1) {
                if (!isActive) return
                _uiState.update { it.copy(phase = DictationPhase.Preparing(s)) }
                delay(1000)
            }
        }

        // ---- 计时开始 ----
        runStartedAt = System.currentTimeMillis()
        _uiState.update { it.copy(phase = DictationPhase.Running) }

        val ticker = launch { runTicker() }
        try {
            for (i in _uiState.value.words.indices) {
                _uiState.update { it.copy(currentIndex = i, phase = DictationPhase.Running) }
                if (!advanceOneWord(i)) return
            }

            if (settings.speakFinishAnnouncement) {
                _uiState.update { it.copy(isSpeaking = true) }
                ttsEngine.speak("听写结束", settings.speechRate, 1.0f, 1)
                _uiState.update { it.copy(isSpeaking = false) }
            }
        } finally {
            ticker.cancel()
        }

        finish(completed = true)
    }

    /**
     * 处理单个词语：朗读 → 等待书写。
     * @return false 表示会话应中止
     */
    private suspend fun advanceOneWord(index: Int): Boolean {
        val word = _uiState.value.words.getOrNull(index) ?: return false

        // ---- 朗读 ----
        _uiState.update { st ->
            st.copy(
                isSpeaking = true,
                words = st.words.map {
                    if (it.index == index) it.copy(status = WordStatus.SPEAKING) else it
                },
            )
        }

        val speakText = buildSpeakText(word.text, index, settings.speakIndex)

        when (ttsEngine.speak(speakText, settings.speechRate, 1.0f, settings.repeatCount)) {
            is SpeakResult.Error -> {
                // 朗读失败不中断整套流程：至少让学生按字数把时间等满
                _uiState.update {
                    it.copy(
                        errorMessage = "部分词语朗读失败，已跳过朗读只保留书写时间",
                        isSpeaking = false,
                    )
                }
            }
            else -> Unit
        }

        if (!sessionActive()) return false

        // ---- 等待书写 ----
        // 间隔在这里**按当前设置重算**，而不是直接用 `word.intervalMillis`：
        // 那是建会话时算好的快照，听写中调了每字时长就过期了。
        // 反正 TTS 已经念完了，重算一次是纯内存算术，代价可忽略
        val interval = WordParser.intervalMillis(
            word.text,
            settings.perCharSeconds,
            settings.minSeconds,
            settings.maxSeconds,
        )
        _uiState.update { st ->
            st.copy(
                isSpeaking = false,
                currentIntervalMillis = interval,
                remainingMillis = interval,
                words = st.words.map {
                    if (it.index == index) {
                        it.copy(status = WordStatus.WAITING, intervalMillis = interval)
                    } else {
                        it
                    }
                },
            )
        }

        waitForInterval(interval)

        if (!sessionActive()) return false

        _uiState.update { st ->
            st.copy(
                words = st.words.map {
                    if (it.index == index) it.copy(status = WordStatus.DONE) else it
                },
                remainingMillis = 0L,
            )
        }
        return true
    }

    /**
     * 等待书写间隔。
     *
     * 分片推进（每 50ms 一帧）而不是一次 delay，是为了：
     * 1) 倒计时环能平滑走动；
     * 2) 能及时响应暂停、「下一个」、结束。
     *
     * **两种模式都必须登记跳过信号。** 这里踩过：早期版本只在手动模式下登记，
     * 自动模式那支只是把界面上的剩余时间改成 0 —— 而循环的剩余量 `remaining`
     * 是个局部变量，压根不受 UI 状态影响，50ms 后还会被真实值覆盖回去。
     * 结果是自动推进（默认就是开着的）下点「下一个」**完全没有反应**。
     * 「这一词不等了、直接下一个」在自动模式下同样是常用操作，必须支持。
     */
    private suspend fun waitForInterval(totalMillis: Long) {
        // 朗读期间点过「下一个」：这一词的等待直接免掉
        if (skipRequested) {
            skipRequested = false
            _uiState.update { it.copy(remainingMillis = 0L) }
            return
        }

        val signal = CompletableDeferred<Unit>()
        skipSignal = signal

        waitTotalMillis = totalMillis
        waitDeadline = System.currentTimeMillis() + totalMillis

        try {
            if (!settings.autoAdvance) {
                // 手动模式：界面上仍展示分配的时间，但不倒计时消耗，一直等到点击
                _uiState.update { it.copy(remainingMillis = totalMillis) }
                signal.await()
                return
            }

            val step = 50L
            while (true) {
                if (!sessionActive()) return

                // 每帧用「截止时刻 - 现在」重算剩余，而不是在局部计数器上递减。
                // 这样听写中调每字时长（`updatePerCharSeconds` 挪动 deadline）
                // 下一帧就生效，也不会有 50ms 分片带来的累积误差
                val remaining = (waitDeadline - System.currentTimeMillis()).coerceAtLeast(0L)
                _uiState.update { it.copy(remainingMillis = remaining) }
                if (remaining <= 0L) return

                // 暂停时挂起等恢复，同时不计入 elapsed。
                // 这里**不**消费跳过信号：暂停中推进会让下一词在暂停状态下朗读出声。
                // 用户在暂停期间点的「下一个」会留到恢复后立刻生效，不会白点。
                // 暂停期间也要把 deadline 一起往后推，否则恢复后会发现时间早就走完了
                if (_uiState.value.phase is DictationPhase.Paused) {
                    waitDeadline += 80
                    delay(80)
                    continue
                }

                // 等一帧：正常走完 step 就继续推进；被「下一个」提前完成就立刻收尾。
                // 不用「每帧看一眼标记」是因为信号完成是挂起等待，能立刻醒来，
                // 不必等下一帧才发现。
                if (withTimeoutOrNull(step) { signal.await() } != null) {
                    _uiState.update { it.copy(remainingMillis = 0L) }
                    return
                }
            }
        } finally {
            skipSignal = null
            waitDeadline = 0L
        }
    }

    /** 会话是否仍在有效推进中 */
    private fun sessionActive(): Boolean =
        sessionJob?.isActive == true && _uiState.value.phase !is DictationPhase.Finished

    /** 高频刷新总用时（暂停期间冻结） */
    private suspend fun runTicker() {
        while (true) {
            val st = _uiState.value
            if (st.phase !is DictationPhase.Paused && st.phase !is DictationPhase.Preparing) {
                val elapsed = accumulatedMillis + (System.currentTimeMillis() - runStartedAt)
                _uiState.update { it.copy(elapsedMillis = elapsed) }
            }
            delay(200)
        }
    }

    // --------------------------------------------------------- 用户操作

    /**
     * 下一个：结束当前等待，立即进入下一词。朗读途中点击也算数。
     *
     * 对已完成的信号重复 `complete` 是无副作用的（返回 false），
     * 所以连点两下不会把「跳过」带到再下一个词——这正是要的效果。
     */
    fun nextWord() {
        val signal = skipSignal
        if (signal != null) {
            signal.complete(Unit)
        } else {
            // 还在朗读、信号尚未登记：记下来，朗读一结束就跳过等待
            skipRequested = true
        }
    }

    /** 重听当前词 */
    fun replayCurrent() = replayWord(_uiState.value.currentIndex)

    /**
     * 朗读一个指定文本，不依赖会话状态。
     *
     * 结果页需要重听某个词，但结果页是另一个 ViewModel 实例、内部没有词语列表，
     * 所以由调用方直接把词传进来。
     */
    fun replayText(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            ttsEngine.speak(text, settings.speechRate, 1.0f, settings.repeatCount)
        }
    }

    /** 单独重听某个词 */
    fun replayWord(index: Int) {
        val word = _uiState.value.words.getOrNull(index) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSpeaking = true) }
            ttsEngine.speak(word.text, settings.speechRate, 1.0f, settings.repeatCount)
            _uiState.update { it.copy(isSpeaking = false) }
        }
    }

    /** 回到上一个词重听。会打断当前流程，从该词重新开始推进。 */
    fun previousWord() {
        val target = (_uiState.value.currentIndex - 1).coerceAtLeast(0)
        jumpTo(target)
    }

    /**
     * 跳到指定下标，从那里继续往下听。
     *
     * 「上一个」就是本方法的特例（`currentIndex - 1`），所以两者共用一份实现——
     * 回退要处理的那些事（停掉朗读、换掉主循环、把状态重置）在跳转里一件都不少，
     * 分成两份写迟早会漏掉其中一处。
     *
     * 已经听过的词保留 `DONE`（这样 `completedCount` 还是真实的「写过几个」），
     * 目标及其之后的词重置为 `PENDING`，等主循环重新推进。
     *
     * 注意主循环是 `for (i in startIndex until words.size)`，所以只能**往后**续着走：
     * 跳到前面的词会从那里重新听到结尾，这与「上一个」的既有行为一致。
     */
    fun jumpTo(index: Int) {
        val st = _uiState.value
        if (st.words.isEmpty()) return
        if (st.isFinished) return
        val target = index.coerceIn(0, st.words.lastIndex)
        if (target == st.currentIndex && sessionJob?.isActive == true) return

        ttsEngine.stop()
        sessionJob?.cancel()
        skipRequested = false
        // 跳转前用掉的时间要计入，否则跳一次总用时就被清一次
        accumulatedMillis = st.elapsedMillis
        runStartedAt = System.currentTimeMillis()

        _uiState.update { s ->
            s.copy(
                currentIndex = target,
                phase = DictationPhase.Running,
                remainingMillis = 0L,
                currentIntervalMillis = 0L,
                words = s.words.map {
                    when {
                        it.index < target -> it.copy(status = WordStatus.DONE)
                        else -> it.copy(status = WordStatus.PENDING)
                    }
                },
            )
        }
        sessionJob = viewModelScope.launch { runSessionFrom(target) }
    }

    /**
     * 调整「每字时长」（每个字给几秒书写时间）。
     *
     * 与朗读语速不是一回事：语速管 TTS 念得多快，每字时长管**等待要等多久**，
     * 也就是听写的节奏本身。学生觉得「来不及写」时调的是后者。
     *
     * 生效范围（依用户选择）：
     * 1. 立刻写回 `settings`，后续的词按新值重算间隔；
     * 2. **当前词也重新计时**——正在等待时，按新的每字时长把剩余时间重算一遍。
     *
     * 「写回持久层」与「重算当前词」是两回事，所以分开做：
     * 前者要落盘（用户要的是存成默认），后者纯内存操作、必须立即，
     * 不能等 DataStore 往返回来再动界面，否则拖滑块时倒计时会明显滞后。
     */
    fun updatePerCharSeconds(seconds: Float) {
        val clamped = seconds.coerceIn(
            DictationSettings.PER_CHAR_MIN,
            DictationSettings.PER_CHAR_MAX,
        )
        if (clamped == settings.perCharSeconds) return
        settings = settings.copy(perCharSeconds = clamped)
        _perCharSeconds.value = clamped

        // 当前词重新计时：拖滑块时看得见倒计时跟着变，才知道自己在调什么
        recomputeCurrentInterval()

        viewModelScope.launch { repository.saveSettings(settings) }
    }

    /**
     * 按新的每字时长重算**正在等待的那个词**。
     *
     * 做法是挪动 [waitDeadline] 而不是直接改 `remainingMillis`：
     * 剩余时间由等待循环每帧从 deadline 重算，所以只要 deadline 变了，
     * 下一帧（最多 50ms）就会反映到界面上，不需要去戳循环里的局部变量
     * （也戳不到——那正是「下一个」按钮早期失效的原因）。
     *
     * 剩余量按「已过去多少」折算，而不是直接换成新的总时长：
     * 那样会让一个已经等了 10 秒的词突然又剩 20 秒，像倒着走。
     * 折算后拖长时长是「再多给一点」、拖短是「快到了」，方向一致。
     */
    private fun recomputeCurrentInterval() {
        val st = _uiState.value
        if (st.phase !is DictationPhase.Running) return
        val word = st.currentWord ?: return
        // 只在等待阶段生效：朗读中的词还没开始计时，它会自然用新值算间隔
        if (word.status != WordStatus.WAITING) return
        // 手动模式不倒计时，展示的就是分配的时长，同样跟着改
        if (waitDeadline == 0L && settings.autoAdvance) return

        val next = WordParser.intervalMillis(
            word.text,
            settings.perCharSeconds,
            settings.minSeconds,
            settings.maxSeconds,
        )

        val now = System.currentTimeMillis()
        val elapsed = (waitTotalMillis - (waitDeadline - now)).coerceIn(0L, waitTotalMillis)
        val remaining = (next - elapsed).coerceAtLeast(0L)

        waitTotalMillis = next
        waitDeadline = if (settings.autoAdvance) now + remaining else 0L

        _uiState.update { s ->
            s.copy(currentIntervalMillis = next, remainingMillis = remaining)
        }
    }

    /** 从指定下标起继续推进主循环（供回退重听使用） */
    private suspend fun CoroutineScope.runSessionFrom(startIndex: Int) {
        val ticker = launch { runTicker() }
        try {
            for (i in startIndex until _uiState.value.words.size) {
                if (!isActive) return
                _uiState.update { it.copy(currentIndex = i, phase = DictationPhase.Running) }
                if (!advanceOneWord(i)) return
            }
            if (settings.speakFinishAnnouncement) {
                _uiState.update { it.copy(isSpeaking = true) }
                ttsEngine.speak("听写结束", settings.speechRate, 1.0f, 1)
                _uiState.update { it.copy(isSpeaking = false) }
            }
        } finally {
            ticker.cancel()
        }
        finish(completed = true)
    }

    /** 暂停 / 继续 */
    fun togglePause() {
        when (_uiState.value.phase) {
            is DictationPhase.Paused -> {
                // 恢复：把暂停期间的时间从基准里扣掉
                runStartedAt = System.currentTimeMillis()
                _uiState.update { it.copy(phase = DictationPhase.Running) }
            }
            is DictationPhase.Running -> {
                accumulatedMillis = _uiState.value.elapsedMillis
                _uiState.update { it.copy(phase = DictationPhase.Paused) }
                ttsEngine.stop()
            }
            else -> Unit
        }
    }

    /** 完成听写（用户主动点，或自然走完） */
    fun complete() {
        if (_uiState.value.isFinished) return
        val st = _uiState.value
        val completed = st.total > 0 && st.completedCount >= st.total
        // finish 是挂起函数（要写 DataStore），换到协程里执行；
        // 先把状态切到 Finished，避免用户在写入完成前重复点击
        sessionJob?.cancel()
        ttsEngine.stop()
        _uiState.update { it.copy(phase = DictationPhase.Finished, isSpeaking = false) }
        viewModelScope.launch { finish(completed = completed) }
    }

    /** 放弃本次听写，直接退出 */
    fun abort() {
        sessionJob?.cancel()
        ttsEngine.stop()
    }

    private suspend fun finish(completed: Boolean) {
        val st = _uiState.value
        val elapsed = if (runStartedAt > 0) {
            accumulatedMillis + (System.currentTimeMillis() - runStartedAt)
        } else {
            st.elapsedMillis
        }

        _uiState.update {
            it.copy(
                phase = DictationPhase.Finished,
                elapsedMillis = elapsed,
                isSpeaking = false,
                remainingMillis = 0L,
                words = it.words.map { w -> w.copy(status = WordStatus.DONE) },
            )
        }

        _result.value = DictationResult(
            words = st.words.map { it.text },
            elapsedMillis = elapsed,
            completed = completed,
        )
        // 交给会话交接点，供结果页（另一个 ViewModel 实例）读取
        DictationSessionStore.publish(_result.value!!)
        repository.recordSession(elapsed)
        // 历史记录只存词语（外加时刻与用时），不记对错——见 DictationRecord 的说明。
        // 这里连**放弃听写**的场景也一并记下：用户中途退出往往正是想回头看看
        // 「刚才那批词是什么」，漏记反而最可惜
        repository.appendHistory(
            DictationRecord(
                completedAt = System.currentTimeMillis(),
                words = _result.value!!.words,
                elapsedMillis = elapsed,
            )
        )
    }

    fun consumeError() = _uiState.update { it.copy(errorMessage = null) }

    override fun onCleared() {
        sessionJob?.cancel()
        super.onCleared()
    }

    companion object {
        /**
         * 拼出真正送去朗读的文本。
         *
         * 中文听写时报序号（「第 3 个，苹果」）能帮学生确认进度；但英文单词
         * 如果也念「第 3 个，apple」，「第几个」是中文、单词是英文，中间会
         * 夹一次语言切换，听起来很割裂。所以英文只念单词本身。
         *
         * 用纯函数是为了能在单元测试里直接验证，不必启动 Android 环境。
         */
        internal fun buildSpeakText(
            text: String,
            index: Int,
            speakIndex: Boolean,
        ): String {
            if (!speakIndex) return text
            val language = SpeechLanguage.detectFor(text)
            return if (language == SpeechLanguage.CHINESE) {
                "第 ${index + 1} 个，$text"
            } else {
                text
            }
        }

        fun factory(
            ttsEngine: TtsEngine,
            repository: SettingsRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DictationViewModel(ttsEngine, repository) as T
        }
    }
}
