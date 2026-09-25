package com.youxue.app.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 基于 ML Kit 捆绑式的文字识别实现。
 *
 * 选捆绑式而不是 `play-services-mlkit-*` 非捆绑式，是因为后者要靠
 * Google Play 服务在首次使用时下载模型——国内机型基本没有 GMS，
 * 等于功能不可用。捆绑式的模型和原生库都在 APK 里，完全离线。
 *
 * 用中文识别器而不是拉丁识别器：中文包的 assets 里同时带 `Hani_ctc`
 * 和 `Latn_ctc` 两套模型，**一个依赖就覆盖了中英**，不需要再加拉丁包。
 *
 * 识别器是懒加载的：构造 `TextRecognizer` 会去加载原生库，
 * 放在 Application 启动路径上会拖慢冷启动，而大部分用户根本不会用到识字。
 */
class MlKitOcrEngine : OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    @Volatile
    private var released = false

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        if (bitmap.isRecycled) {
            return OcrResult.Failure("图片已释放，无法识别")
        }

        return withContext(Dispatchers.Default) {
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                // 显式给结果类型：两个监听器分别 resume Success / Failure，
                // 不写的话 Kotlin 会从其中一个分支把类型定死
                suspendCancellableCoroutine<OcrResult> { cont ->
                    recognizer.process(image)
                        .addOnSuccessListener { text ->
                            if (!cont.isActive) return@addOnSuccessListener
                            // 用 lines 而不是 text.text：后者是整页拼成的一个大字符串，
                            // 粒度太粗，和「一行一个词语」的约定对不上。
                            // 行顺序 ML Kit 已按阅读顺序给好，不要再自己按坐标排。
                            val lines = text.textBlocks
                                .flatMap { it.lines }
                                .map { it.text.trim() }
                                .filter { it.isNotEmpty() }
                            cont.resume(OcrResult.Success(lines))
                        }
                        .addOnFailureListener { e ->
                            if (!cont.isActive) return@addOnFailureListener
                            Log.w(TAG, "ML Kit 识别失败", e)
                            cont.resume(
                                OcrResult.Failure(
                                    "识别失败：${e.message ?: e.javaClass.simpleName}",
                                    e,
                                )
                            )
                        }
                }
            } catch (t: Throwable) {
                // 原生库加载失败、OOM 等都会走到这里。这类异常在国产无 GMS
                // 机型上是真实存在的一种可能，所以提示要能直接看懂。
                Log.w(TAG, "ML Kit 调用异常", t)
                OcrResult.Failure(
                    "识别引擎不可用：${t.message ?: t.javaClass.simpleName}",
                    t,
                )
            }
        }
    }

    override fun shutdown() {
        if (released) return
        released = true
        runCatching { recognizer.close() }
    }

    private companion object {
        const val TAG = "MlKitOcrEngine"
    }
}
