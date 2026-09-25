package com.youxue.app.ui.dictation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.youxue.app.data.DictationSettings
import com.youxue.app.data.SettingsRepository
import com.youxue.app.dictation.WordParser
import com.youxue.app.tts.SpeechLanguage
import com.youxue.app.tts.TtsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupUiState(
    val rawText: String = "",
    val settings: DictationSettings = DictationSettings.DEFAULT,
    val errorMessage: String? = null,
    /** 是否已从 DataStore 读到过设置，用于避免首帧用默认值闪一下 */
    val loaded: Boolean = false,
    /**
     * 录入内容里出现、但设备没有对应语音包的语言。
     *
     * 空表示没问题。提前提示的意义在于：用户不必等到听写开始后
     * 才发现有一半词念不出来。
     */
    val missingLanguages: List<SpeechLanguage> = emptyList(),
) {
    val wordCount: Int get() = WordParser.countWords(rawText)

    val canStart: Boolean get() = wordCount > 0

    /** 录入内容涉及的语言种类，用于说明「本批词语会自动按语言朗读」 */
    val usedLanguages: List<SpeechLanguage>
        get() = WordParser.parse(rawText)
            .map { SpeechLanguage.detectFor(it) }
            .distinct()

    val isMixedLanguage: Boolean get() = usedLanguages.size > 1

    val estimatedMillis: Long
        get() = WordParser.estimatedTotalMillis(
            words = WordParser.parse(rawText),
            perCharSeconds = settings.perCharSeconds,
            minSeconds = settings.minSeconds,
            maxSeconds = settings.maxSeconds,
        )
}

/**
 * 录入页的状态持有者。
 *
 * 关键设计：**UI 状态是设置的唯一真源**。
 *
 * 拖动滑块时只更新内存里的 StateFlow（即时、无 IO），再把整份设置异步落盘。
 * 反过来「订阅 DataStore 并回灌 UI」是错的——那样每次落盘的往返延迟都会让
 * UI 短暂回到旧值，表现出来就是滑块拖不动、松开又弹回去。
 * 所以这里只在进入页面时读一次 DataStore，之后不再订阅。
 */
class DictationSetupViewModel(
    private val repository: SettingsRepository,
    private val ttsEngine: TtsEngine,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 只读一次：设置 + 草稿回填
            val saved = repository.loadSettings()
            val draft = repository.draftWords.first()
            _state.update {
                it.copy(
                    settings = saved,
                    rawText = if (it.rawText.isBlank()) draft else it.rawText,
                    loaded = true,
                )
            }
            refreshMissingLanguages()
        }

        // 语音包在「设置」里装好回来时，引擎状态会变；跟着重算一次提示
        viewModelScope.launch {
            ttsEngine.state.collect { refreshMissingLanguages() }
        }
    }

    /**
     * 重算「缺少哪种语音包」。
     *
     * 必须等引擎初始化完成——初始化期间 [TtsEngine.isLanguageAvailable] 一律返回
     * false，这时算出来的结果会把两种语言都标成缺失，闪一条误导性的警告。
     */
    private fun refreshMissingLanguages() {
        val used = _state.value.usedLanguages
        val missing = if (used.isEmpty()) {
            emptyList()
        } else {
            used.filter { !ttsEngine.isLanguageAvailable(it) }
        }
        _state.update { it.copy(missingLanguages = missing) }
    }

    fun onTextChange(text: String) {
        _state.update { it.copy(rawText = text) }
        refreshMissingLanguages()
    }

    fun clearText() {
        _state.update { it.copy(rawText = "", missingLanguages = emptyList()) }
        viewModelScope.launch { repository.clearDraft() }
    }

    /**
     * 把拍照识字的结果填进录入框。
     *
     * 采取**追加**而不是替换：拍完一页接着拍下一页是常见动作，
     * 替换会把上一页抹掉。而文本框为空时追加就等于替换，
     * 所以不需要再给用户一个「覆盖还是追加」的选择——多一个选择
     * 就要多一次判断，而这种情况下的判断谁都不想每次做一遍。
     *
     * 唯一要留意的是拼接处只补一个换行：用户可能自己刚好在末尾留了空行，
     * 再补一个就会在两个词语之间多出一条空行。
     */
    fun fillFromOcr(text: String) {
        if (text.isBlank()) return
        val current = _state.value.rawText
        val merged = when {
            current.isBlank() -> text
            current.endsWith("\n") -> current + text
            else -> current + "\n" + text
        }
        _state.update { it.copy(rawText = merged) }
        refreshMissingLanguages()
    }

    /** 识别结果填入后的词语增量，用于给用户一句「填了几个」的反馈 */
    fun countFor(text: String): Int = WordParser.countWords(text)

    /**
     * 更新设置：先改内存状态让 UI 立刻响应，再异步持久化。
     * 顺序不能颠倒，否则拖动过程中会看到回跳。
     */
    fun updateSettings(transform: (DictationSettings) -> DictationSettings) {
        val next = transform(_state.value.settings)
        _state.update { it.copy(settings = next) }
        viewModelScope.launch { repository.saveSettings(next) }
    }

    /** 把当前词语存为草稿，供听写页读取 */
    fun commit() {
        val text = _state.value.rawText
        viewModelScope.launch { repository.saveDraft(text) }
    }

    fun consumeError() {
        _state.update { it.copy(errorMessage = null) }
    }

    companion object {
        fun factory(
            repository: SettingsRepository,
            ttsEngine: TtsEngine,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DictationSetupViewModel(repository, ttsEngine) as T
                }
            }
    }
}
