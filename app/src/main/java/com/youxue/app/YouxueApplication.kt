package com.youxue.app

import android.app.Application
import com.youxue.app.data.PlanRepository
import com.youxue.app.data.SettingsRepository
import com.youxue.app.data.TaskRepository
import com.youxue.app.ocr.MlKitOcrEngine
import com.youxue.app.ocr.OcrEngine
import com.youxue.app.tts.SystemTtsEngine
import com.youxue.app.tts.TtsEngine

/**
 * 应用级单例容器。
 *
 * 不引入 DI 框架：这个应用的对象图只有四个节点（TTS 引擎、OCR 引擎、
 * 设置仓库、任务仓库），手写容器比引入 Hilt/Koin 更直观，也少一层编译期成本。
 * 等真接入后端、对象图复杂起来再换也不迟。
 */
class YouxueApplication : Application() {

    /** TTS 引擎全应用共用一个实例：TextToSpeech 的初始化是有成本的，反复创建会明显卡顿 */
    val ttsEngine: TtsEngine by lazy { SystemTtsEngine(this) }

    /**
     * OCR 引擎。
     *
     * 这里用 `lazy` 而不是构造时创建，是有意的：ML Kit 的
     * `TextRecognizer` 一创建就会去加载原生库（十几 MB 的 so），
     * 放在冷启动路径上会白白拖慢所有用户，而拍照识字是个低频功能。
     * 第一次真正用到它时才加载，代价落在真正需要的人身上。
     */
    val ocrEngine: OcrEngine by lazy { MlKitOcrEngine() }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val taskRepository: TaskRepository by lazy { TaskRepository(this) }

    val planRepository: PlanRepository by lazy { PlanRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onTerminate() {
        ttsEngine.shutdown()
        ocrEngine.shutdown()
        super.onTerminate()
    }

    companion object {
        private var instance: YouxueApplication? = null

        /**
         * 获取应用实例。
         * 用伴生对象而非 Context 强制转换，是为了在少数拿不到 Activity 的地方也能取到容器。
         */
        fun get(): YouxueApplication = requireNotNull(instance) {
            "YouxueApplication 尚未初始化"
        }
    }
}
