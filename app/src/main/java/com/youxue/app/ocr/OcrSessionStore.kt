package com.youxue.app.ocr

import com.youxue.app.util.CropBox
import com.youxue.app.util.ImageSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 拍照识字流程的「交接点」。
 *
 * 和 [com.youxue.app.dictation.DictationSessionStore] 同样的理由：
 * 录入页、裁剪页、预览页是三个独立路由，各自持有独立的 ViewModel 实例，
 * 上一个页面的状态下一个页面读不到，必须有个应用级的地方放。
 *
 * 但这里**只放指针，不放位图**。位图是十几 MB 的大对象，挂在单例上
 * 一旦忘记释放就是几十 MB 的内存泄漏，而且从生命周期上根本说不清
 * 「什么时候该放」。改为只存 [ImageSource]（一个字符串）和用户框选的
 * [CropBox]（四个整数），谁用谁解码，位图跟着页面的生命周期走——
 * 代价是解码两次，换来的是内存管理上不可能出错。
 */
object OcrSessionStore {

    private val _source = MutableStateFlow<ImageSource?>(null)

    /** 当前待识别的图片。null 表示流程还没开始 */
    val source: StateFlow<ImageSource?> = _source.asStateFlow()

    private val _cropBox = MutableStateFlow<CropBox?>(null)

    /**
     * 用户在裁剪页框选的区域（位图坐标系）。
     * null 表示「整图识别」——不是「没选」，是明确选了整张图。
     */
    val cropBox: StateFlow<CropBox?> = _cropBox.asStateFlow()

    private val _filled = MutableStateFlow<String?>(null)

    /** 待回填到录入框的文本。录入页观察它，取走后置回 null */
    val filled: StateFlow<String?> = _filled.asStateFlow()

    /** 开始一次新的拍照识字。旧的框选会被一并清掉 */
    fun begin(source: ImageSource) {
        _source.value = source
        _cropBox.value = null
        _filled.value = null
    }

    /**
     * 记录裁剪区域。
     *
     * 传 null 表示整图。刻意用「显式传 null」而不是「不调用」来区分：
     * 用户可能先框了一块、又改主意要整图，这时必须能把之前的框清掉。
     */
    fun setCrop(box: CropBox?) {
        _cropBox.value = box
    }

    /** 预览页确认后写入，交回录入页 */
    fun publishFilled(text: String) {
        _filled.value = text
    }

    /**
     * 取走回填文本，取完清空。
     *
     * 这里**必须消费后清空**，和会话结果那种「读后不清」正好相反：
     * 回填是一次性动作，清空后配置变更重组不会把用户随后手改的内容
     * 又覆盖回识别结果。
     */
    fun consumeFilled(): String? {
        val value = _filled.value ?: return null
        _filled.value = null
        return value
    }

    /** 整条流程结束（回填完成、或用户中途退出）时调用 */
    fun clear() {
        _source.value = null
        _cropBox.value = null
        _filled.value = null
    }
}
