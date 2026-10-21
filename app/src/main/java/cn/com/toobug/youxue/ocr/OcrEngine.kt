package cn.com.toobug.youxue.ocr

import android.graphics.Bitmap

/**
 * 文字识别引擎抽象。
 *
 * 和 `TtsEngine` 一样先定接口再实现：OCR 是那种「以后很可能换」的能力
 * （换引擎、换云端、换自训练模型），把调用方和 ML Kit 解耦掉，
 * 将来业务层不用动。
 */
interface OcrEngine {

    /**
     * 识别一张位图中的文字。
     *
     * 返回的**行顺序即阅读顺序**——这一点由引擎保证，调用方不要再自己排序。
     * 多栏排版、横排竖排的版面分析都交给引擎，自己按坐标排只会更差。
     *
     * 图片长边建议压到 2000px 以内再传进来：更大的图不会更准，
     * 只会更慢、更占内存。
     *
     * 失败不抛异常，走 [OcrResult.Failure]，让调用方决定怎么提示。
     */
    suspend fun recognize(bitmap: Bitmap): OcrResult

    /** 释放引擎持有的资源。多次调用应当是安全的 */
    fun shutdown()
}

/** 识别结果 */
sealed interface OcrResult {

    /**
     * 识别成功。
     *
     * 注意 [lines] 为空也是「成功」——可能图里确实没有文字，
     * 也可能拍的是一张空白纸。这不是错误，UI 该给的是「没识别到文字」
     * 而不是「识别失败」，两者的处置方式完全不同。
     */
    data class Success(val lines: List<String>) : OcrResult

    data class Failure(val message: String, val cause: Throwable? = null) : OcrResult
}
