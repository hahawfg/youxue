package cn.com.toobug.youxue.ui.ocr

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.com.toobug.youxue.YouxueApplication
import cn.com.toobug.youxue.ocr.OcrResult
import cn.com.toobug.youxue.ocr.OcrSessionStore
import cn.com.toobug.youxue.ocr.OcrTextCleaner
import cn.com.toobug.youxue.ocr.SuspectReason
import cn.com.toobug.youxue.util.BitmapLoader
import cn.com.toobug.youxue.util.CropBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 预览页里的一行 */
data class OcrRowUi(
    val text: String,
    val suspect: SuspectReason?,
    val selected: Boolean,
) {
    val isClean: Boolean get() = suspect == null
}

data class OcrPreviewUiState(
    val loading: Boolean = true,
    val rows: List<OcrRowUi> = emptyList(),
    /** 引擎或图片层面的错误。与「没识别到文字」是两件事，提示也不同 */
    val errorMessage: String? = null,
) {
    val selectedCount: Int get() = rows.count { it.selected }

    val canFill: Boolean get() = selectedCount > 0
}

/**
 * 预览页的状态持有者。
 *
 * 识别在这里发起而不是在裁剪页：裁剪页只负责「框哪儿」，
 * 识别是「框完之后的事」，放在这里可以让裁剪页彻底不依赖 OCR 引擎
 * （它只需要 Bitmap 和裁剪工具），职责更干净。
 *
 * 需要 Application 是因为解码要 ContentResolver——相册选来的图
 * 是个 content URI，没有 Context 读不了。
 */
class OcrPreviewViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = YouxueApplication.get().ocrEngine

    private val _state = MutableStateFlow(OcrPreviewUiState())
    val state: StateFlow<OcrPreviewUiState> = _state.asStateFlow()

    private var ran = false

    /** 幂等：配置变更会让页面重进，但识别只该跑一次 */
    fun runOnce() {
        if (ran) return
        ran = true
        viewModelScope.launch { recognize() }
    }

    fun retry() {
        _state.value = OcrPreviewUiState(loading = true)
        viewModelScope.launch { recognize() }
    }

    fun toggle(index: Int) {
        _state.update { s ->
            s.copy(
                rows = s.rows.mapIndexed { i, row ->
                    if (i == index) row.copy(selected = !row.selected) else row
                }
            )
        }
    }

    fun selectAll(selected: Boolean) {
        _state.update { s -> s.copy(rows = s.rows.map { it.copy(selected = selected) }) }
    }

    fun edit(index: Int, text: String) {
        _state.update { s ->
            s.copy(
                rows = s.rows.mapIndexed { i, row ->
                    if (i != index) return@mapIndexed row
                    val changed = text != row.text
                    row.copy(
                        text = text,
                        // 用户手改过的行就不再是「可疑」了——可疑是我们对引擎输出的判断，
                        // 人已经把内容确认过，标记就该撤掉
                        suspect = if (changed) null else row.suspect,
                        // 改出来的内容按「用户明确要它」处理，直接勾上；
                        // 否则用户改完一个疑似页码的行，会疑惑它为什么还是没被选中
                        selected = when {
                            text.isBlank() -> false
                            changed -> true
                            else -> row.selected
                        },
                    )
                }
            )
        }
    }

    /** 把选中的行交回录入页。回调在写入后触发，由调用方负责导航 */
    fun confirm(onDone: (String) -> Unit) {
        val text = OcrTextCleaner.joinLines(
            _state.value.rows.filter { it.selected }.map { it.text }
        )
        if (text.isBlank()) return
        OcrSessionStore.publishFilled(text)
        onDone(text)
    }

    private suspend fun recognize() {
        val source = OcrSessionStore.source.value
        if (source == null) {
            _state.value = OcrPreviewUiState(loading = false, errorMessage = "图片已失效，请重新拍摄")
            return
        }

        val context = getApplication<Application>()
        val cropBox: CropBox? = OcrSessionStore.cropBox.value

        val bitmap: Bitmap? = withContext(Dispatchers.IO) {
            val full = BitmapLoader.decode(context, source) ?: return@withContext null
            if (cropBox == null) {
                full
            } else {
                val cropped = BitmapLoader.crop(full, cropBox)
                // 裁剪完原图就没用了，立刻放掉——两张 12MB 同时挂着很容易触发 GC 抖动
                if (cropped !== full) full.recycle()
                cropped
            }
        }

        if (bitmap == null) {
            _state.value = OcrPreviewUiState(loading = false, errorMessage = "图片读取失败，请重新拍摄")
            return
        }

        val result = try {
            engine.recognize(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        _state.value = when (result) {
            is OcrResult.Failure -> OcrPreviewUiState(
                loading = false,
                errorMessage = result.message,
            )

            is OcrResult.Success -> OcrPreviewUiState(
                loading = false,
                rows = OcrTextCleaner.toCandidates(result.lines).map {
                    OcrRowUi(
                        text = it.text,
                        suspect = it.suspect,
                        // 可疑行仍然展示、只是默认不勾——决定权在用户，
                        // 程序只负责把可疑的地方指出来
                        selected = it.selectedByDefault,
                    )
                },
            )
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return OcrPreviewViewModel(YouxueApplication.get()) as T
            }
        }
    }
}

/** 供测试与 UI 共用的一句话说明，避免同一套措辞在两处各写一遍 */
fun suspectLabel(reason: SuspectReason): String = when (reason) {
    SuspectReason.NUMERIC_ONLY -> "像页码"
    SuspectReason.PUNCTUATION_ONLY -> "没内容"
    SuspectReason.TOO_SHORT -> "只有一个字"
    SuspectReason.TOO_LONG -> "长句"
}
