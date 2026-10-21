package cn.com.toobug.youxue.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.toobug.youxue.data.DictationRecord
import cn.com.toobug.youxue.data.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 历史列表的界面状态 */
data class HistoryUiState(
    val records: List<DictationRecord> = emptyList(),
    /** 还在读第一份数据。用来区分「真的没有记录」和「还没读到」 */
    val loading: Boolean = true,
) {
    val isEmpty: Boolean get() = !loading && records.isEmpty()
}

class DictationHistoryViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = repository.history
        .map { HistoryUiState(records = it, loading = false) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryUiState(),
        )

    fun clearAll() {
        viewModelScope.launch { repository.clearHistory() }
    }

    companion object {
        fun factory(repository: SettingsRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DictationHistoryViewModel(repository) as T
            }
    }
}
