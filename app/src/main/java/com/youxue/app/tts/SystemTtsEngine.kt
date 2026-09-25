package com.youxue.app.tts

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * 基于 Android 系统 [TextToSpeech] 的实现，支持中英双语自动切换。
 *
 * 选它的理由：随系统提供、零安装体积、中英离线语音包在国产机型上基本预装，
 * 延迟低且不会因为模型加载失败导致整个听写流程不可用。代价是不同机型音色有差异，
 * 这属于可接受的取舍——需要绝对一致的音色时可以换成内置离线模型。
 *
 * 双语处理的关键点：每次朗读前按文本内容检测语言并切换 `Locale`。
 * 系统 TTS 切语言本身很快（只是切内部资源指针），比维护两套引擎实例简单得多。
 */
class SystemTtsEngine(private val context: Context) : TtsEngine {

    private val _state = MutableStateFlow<TtsState>(TtsState.Initializing)
    override val state: StateFlow<TtsState> = _state.asStateFlow()

    /** 朗读完成/失败的回调表，key 为 utteranceId */
    private val pending = ConcurrentHashMap<String, (SpeakResult) -> Unit>()

    private val idGenerator = AtomicLong(0)
    private val mutex = Mutex()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var initialized = false

    /** 各语言是否可用，初始化时探明 */
    private val languageAvailability = ConcurrentHashMap<SpeechLanguage, Boolean>()

    /** 已确认可用的语音对象，避免每次朗读都遍历 voices 列表 */
    private val chosenVoice = ConcurrentHashMap<SpeechLanguage, Voice>()

    /** 当前已经设置到引擎上的语言，避免重复切换 */
    @Volatile
    private var appliedLanguage: SpeechLanguage? = null

    init {
        initTts()
    }

    private fun initTts() {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = engine
                if (t == null) {
                    _state.value = TtsState.Unavailable("语音引擎初始化失败，请重启应用后重试")
                } else {
                    probeLanguages(t)
                }
            } else {
                _state.value = TtsState.Unavailable(
                    "系统语音引擎初始化失败。请确认设备已安装「文字转语音」组件后重试"
                )
            }
        }
        tts = engine

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                val id = utteranceId ?: return
                pending.remove(id)?.invoke(SpeakResult.Done)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onError(utteranceId, TextToSpeech.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                val id = utteranceId ?: return
                pending.remove(id)?.invoke(SpeakResult.Error(describeError(errorCode)))
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                val id = utteranceId ?: return
                pending.remove(id)?.invoke(SpeakResult.Interrupted)
            }
        })
    }

    /**
     * 探明中英两种语言的可用性，并各自挑一个合适的默认音色。
     *
     * 判定「可用」而非「就绪」：只要有一种语言可用，引擎就算可用。
     * 缺另一种语言只影响对应词语的朗读，不该让整个应用不可用——
     * 所以状态里会带一条提示，但不会把状态置为 Unavailable。
     */
    private fun probeLanguages(t: TextToSpeech) {
        var anyAvailable = false

        SpeechLanguage.entries.forEach { lang ->
            val locale = pickSupportedLocale(t, lang)
            if (locale != null) {
                languageAvailability[lang] = true
                anyAvailable = true
                pickDefaultVoice(t, lang)?.let { chosenVoice[lang] = it }
            } else {
                languageAvailability[lang] = false
            }
        }

        if (!anyAvailable) {
            _state.value = TtsState.Unavailable(
                "设备缺少中文和英文语音包。请到「设置 → 语言和输入 → 文字转语音」" +
                    "中下载语音数据后重试"
            )
            return
        }

        // 先切到中文作为初始语言（中文听写是主场景）
        if (languageAvailability[SpeechLanguage.CHINESE] == true) {
            applyLanguage(t, SpeechLanguage.CHINESE)
        } else {
            applyLanguage(t, SpeechLanguage.ENGLISH)
        }

        initialized = true

        // 有语言缺失时，状态仍是 Ready，但带上提示信息
        val missing = SpeechLanguage.entries.filter { languageAvailability[it] != true }
        _state.value = if (missing.isEmpty()) {
            TtsState.Ready
        } else {
            TtsState.PartiallyAvailable(
                missingLanguage = missing.first(),
                reason = "设备缺少${missing.first().displayName}语音包，" +
                    "对应的词语将无法朗读",
            )
        }
    }

    /** 在候选 Locale 里找出引擎支持的第一个 */
    private fun pickSupportedLocale(t: TextToSpeech, lang: SpeechLanguage): Locale? {
        val candidates = when (lang) {
            SpeechLanguage.CHINESE -> listOf(
                Locale.SIMPLIFIED_CHINESE,   // zh_CN
                Locale.CHINESE,              // zh
                Locale("zh", "CN"),
                Locale("zh", "SG"),
                Locale("zh", "HK"),
                Locale("zh", "TW"),
            )
            SpeechLanguage.ENGLISH -> listOf(
                Locale.US,
                Locale.UK,
                Locale.ENGLISH,             // en
                Locale("en", "AU"),
                Locale("en", "IN"),
            )
        }
        for (locale in candidates) {
            val result = try {
                t.isLanguageAvailable(locale)
            } catch (_: Exception) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
            if (result >= TextToSpeech.LANG_AVAILABLE) return locale
        }
        return null
    }

    /** 在可用音色中挑一个中文/英文的、优先离线、优先女声的音色 */
    private fun pickDefaultVoice(t: TextToSpeech, lang: SpeechLanguage): Voice? {
        val voices = try {
            t.voices
        } catch (_: Exception) {
            null
        } ?: return null

        val matched = voices.filter { v ->
            val language = v.locale?.language ?: return@filter false
            when (lang) {
                SpeechLanguage.CHINESE -> language.equals("zh", ignoreCase = true)
                SpeechLanguage.ENGLISH -> language.equals("en", ignoreCase = true)
            }
        }
        if (matched.isEmpty()) return null

        // 离线优先（网络音色在无网时会失败），其次女声（更贴近教师朗读）
        return matched.firstOrNull {
            !it.isNetworkConnectionRequired && it.name.contains("female", ignoreCase = true)
        } ?: matched.firstOrNull { !it.isNetworkConnectionRequired }
            ?: matched.first()
    }

    /** 把语言与音色应用到引擎上 */
    private fun applyLanguage(t: TextToSpeech, lang: SpeechLanguage) {
        if (appliedLanguage == lang) return
        val candidates = when (lang) {
            SpeechLanguage.CHINESE -> listOf(
                Locale.SIMPLIFIED_CHINESE, Locale.CHINESE, Locale("zh", "CN"),
            )
            SpeechLanguage.ENGLISH -> listOf(Locale.US, Locale.UK, Locale.ENGLISH)
        }
        for (locale in candidates) {
            try {
                val r = t.setLanguage(locale)
                if (r >= TextToSpeech.LANG_AVAILABLE) break
            } catch (_: Exception) {
                // 继续试下一个
            }
        }
        chosenVoice[lang]?.let { voice ->
            try {
                t.voice = voice
            } catch (_: Exception) {
                // 忽略：部分引擎设置音色会抛异常，但朗读仍可能成功
            }
        }
        appliedLanguage = lang
    }

    override fun isLanguageAvailable(language: SpeechLanguage): Boolean =
        languageAvailability[language] == true

    /** 等待初始化完成，最多等待 [timeoutMs] */
    private suspend fun awaitInitialized(timeoutMs: Long = 4000): Boolean {
        if (initialized) return true
        if (_state.value is TtsState.Unavailable) return false
        return withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (initialized) return@withContext true
                if (_state.value is TtsState.Unavailable) return@withContext false
                Thread.sleep(50)
            }
            initialized
        }
    }

    override suspend fun speak(
        text: String,
        rate: Float,
        pitch: Float,
        repeat: Int,
    ): SpeakResult {
        if (text.isBlank()) return SpeakResult.Done

        val language = SpeechLanguage.detectFor(text)

        mutex.withLock {
            if (!awaitInitialized()) {
                val reason = (_state.value as? TtsState.Unavailable)?.reason
                    ?: "语音引擎尚未就绪"
                return SpeakResult.Error(reason)
            }
            val engine = tts ?: return SpeakResult.Error("语音引擎已释放")

            if (languageAvailability[language] != true) {
                return SpeakResult.Error(
                    "设备缺少${language.displayName}语音包，「$text」无法朗读"
                )
            }

            // 每次朗读前确保语言正确——中英混排时相邻两个词可能需要切换
            applyLanguage(engine, language)

            try {
                engine.setSpeechRate(rate.coerceIn(0.3f, 2.5f))
                engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
            } catch (_: Exception) {
                // 忽略参数设置失败，继续朗读
            }

            val times = repeat.coerceAtLeast(1)
            for (i in 0 until times) {
                val id = "yx_${idGenerator.incrementAndGet()}"

                val result = suspendCancellableCoroutine { cont ->
                    pending[id] = { r -> if (cont.isActive) cont.resume(r) }
                    cont.invokeOnCancellation {
                        pending.remove(id)
                        stopInternal(engine)
                    }

                    val params = Bundle().apply {
                        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
                    }
                    val code = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
                    if (code == TextToSpeech.ERROR) {
                        pending.remove(id)?.invoke(SpeakResult.Error("朗读调用失败"))
                    }
                }

                if (result !is SpeakResult.Done) return result
                // 重复朗读之间留一点气口，避免两次发音粘连
                if (i < times - 1) kotlinx.coroutines.delay(350)
            }
            return SpeakResult.Done
        }
    }

    override fun stop() {
        tts?.let { stopInternal(it) }
    }

    private fun stopInternal(engine: TextToSpeech) {
        try {
            engine.stop()
        } catch (_: Exception) {
            // 忽略
        }
        // 兜底：把还没回调的任务标记为中断，避免协程永久挂起
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.invoke(SpeakResult.Interrupted)
        }
    }

    override fun shutdown() {
        initialized = false
        appliedLanguage = null
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
            // 忽略
        }
        tts = null
    }

    private fun describeError(code: Int): String = when (code) {
        TextToSpeech.ERROR_SYNTHESIS -> "语音合成失败，可能是语音包数据不完整"
        TextToSpeech.ERROR_SERVICE -> "语音服务不可用，请重启应用后重试"
        TextToSpeech.ERROR_OUTPUT -> "音频输出异常，请检查扬声器或音量"
        TextToSpeech.ERROR_NETWORK -> "需要网络才能朗读，请检查网络或改用离线语音包"
        TextToSpeech.ERROR_NETWORK_TIMEOUT -> "语音服务网络超时"
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "语音数据尚未下载完成"
        else -> "朗读出错（错误码 $code）"
    }

    companion object {
        /** 供设置页展示：判断当前是否处于「需要网络」的音色 */
        fun requiresNetwork(voice: Voice?): Boolean =
            voice?.isNetworkConnectionRequired == true

        @Suppress("unused")
        val MIN_SDK_SAFE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
    }
}
