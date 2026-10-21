package cn.com.toobug.youxue.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "youxue_prefs")

/**
 * 本地设置与草稿的持久化。
 *
 * 全部走 DataStore，不引入数据库：需要存的东西就是一份设置 + 一份词语草稿，
 * 用它换来的是一致性和线程安全，成本极低。
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val PER_CHAR = floatPreferencesKey("per_char_seconds")
        val MIN_INTERVAL = floatPreferencesKey("min_seconds")
        val MAX_INTERVAL = floatPreferencesKey("max_seconds")
        val REPEAT = intPreferencesKey("repeat_count")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val PREPARE = intPreferencesKey("prepare_seconds")
        val AUTO_ADVANCE = booleanPreferencesKey("auto_advance")
        val SPEAK_INDEX = booleanPreferencesKey("speak_index")
        val SPEAK_FINISH = booleanPreferencesKey("speak_finish")

        /** 上次录入的词语草稿，避免误退出后全部重打 */
        val DRAFT_WORDS = stringPreferencesKey("draft_words")

        /** 历史总听写次数与累计用时，用于首页展示 */
        val TOTAL_SESSIONS = intPreferencesKey("total_sessions")
        val TOTAL_MILLIS = intPreferencesKey("total_millis")

        /** 听写历史，一行一条，见 [DictationHistoryCodec] */
        val HISTORY = stringPreferencesKey("dictation_history")
    }

    /** 历史最多保留多少条。见 [DictationHistoryCodec.trim] 的说明 */
    private val historyLimit = 50

    val settings: Flow<DictationSettings> = context.dataStore.data.map { p ->
        DictationSettings(
            perCharSeconds = p[Keys.PER_CHAR] ?: DictationSettings.DEFAULT.perCharSeconds,
            minSeconds = p[Keys.MIN_INTERVAL] ?: DictationSettings.DEFAULT.minSeconds,
            maxSeconds = p[Keys.MAX_INTERVAL] ?: DictationSettings.DEFAULT.maxSeconds,
            repeatCount = p[Keys.REPEAT] ?: DictationSettings.DEFAULT.repeatCount,
            speechRate = p[Keys.SPEECH_RATE] ?: DictationSettings.DEFAULT.speechRate,
            prepareSeconds = p[Keys.PREPARE] ?: DictationSettings.DEFAULT.prepareSeconds,
            autoAdvance = p[Keys.AUTO_ADVANCE] ?: DictationSettings.DEFAULT.autoAdvance,
            speakIndex = p[Keys.SPEAK_INDEX] ?: DictationSettings.DEFAULT.speakIndex,
            speakFinishAnnouncement = p[Keys.SPEAK_FINISH]
                ?: DictationSettings.DEFAULT.speakFinishAnnouncement,
        )
    }

    val draftWords: Flow<String> = context.dataStore.data.map { it[Keys.DRAFT_WORDS] ?: "" }

    val statistics: Flow<DictationStats> = context.dataStore.data.map { p ->
        DictationStats(
            totalSessions = p[Keys.TOTAL_SESSIONS] ?: 0,
            totalMillis = (p[Keys.TOTAL_MILLIS] ?: 0).toLong(),
        )
    }

    /**
     * 听写历史，**最新的在前**。
     *
     * 存储里是按时间正序追加的（追加代价最低），倒序只在这里翻一次，
     * 让所有使用方都直接拿到「最近的在最上面」这个顺序。
     */
    val history: Flow<List<DictationRecord>> = context.dataStore.data.map { p ->
        DictationHistoryCodec.decode(p[Keys.HISTORY] ?: "")
            .sortedByDescending { it.completedAt }
    }

    suspend fun loadSettings(): DictationSettings = settings.first()

    /**
     * 保存整份设置。
     *
     * 接受完整对象而不是 `transform` lambda，是有意为之：
     * 之前的 `transform` 写法要在 `edit` 内部重新读一遍 DataStore 的值再变形，
     * 而 UI 拖动滑块时连续调用，后一次读到的可能还是前一次未落盘的旧值，
     * 于是用户刚拖到的位置被覆盖回去——手感就是「滑块拖不动」。
     * 改成由调用方传入已算好的完整状态，写入即最终态，不存在中间态竞态。
     */
    suspend fun saveSettings(next: DictationSettings) {
        context.dataStore.edit { p ->
            p[Keys.PER_CHAR] = next.perCharSeconds
            p[Keys.MIN_INTERVAL] = next.minSeconds
            p[Keys.MAX_INTERVAL] = next.maxSeconds
            p[Keys.REPEAT] = next.repeatCount
            p[Keys.SPEECH_RATE] = next.speechRate
            p[Keys.PREPARE] = next.prepareSeconds
            p[Keys.AUTO_ADVANCE] = next.autoAdvance
            p[Keys.SPEAK_INDEX] = next.speakIndex
            p[Keys.SPEAK_FINISH] = next.speakFinishAnnouncement
        }
    }

    suspend fun saveDraft(text: String) {
        context.dataStore.edit { it[Keys.DRAFT_WORDS] = text }
    }

    suspend fun clearDraft() {
        context.dataStore.edit { it.remove(Keys.DRAFT_WORDS) }
    }

    /** 一次听写完成后累加统计 */
    suspend fun recordSession(elapsedMillis: Long) {
        context.dataStore.edit { p ->
            p[Keys.TOTAL_SESSIONS] = (p[Keys.TOTAL_SESSIONS] ?: 0) + 1
            p[Keys.TOTAL_MILLIS] = ((p[Keys.TOTAL_MILLIS] ?: 0).toLong() + elapsedMillis)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
    }

    /**
     * 追加一条听写历史。
     *
     * 与 [saveSettings] 不同，这里**必须**读-改-写：历史是只增的列表，
     * 调用方手上没有完整的那份（列表可能被别处的写入改过），
     * 所以只能在 `edit` 内部基于当前值追加。
     *
     * 好在历史不存在 [saveSettings] 那种「用户连续拖动滑块」的高频写入场景，
     * 一次听写只写一次，读到旧值的竞态窗口可以忽略；
     * 而且这里只在**同一个 `edit` 块内**完成读和写，DataStore 保证该块是串行的。
     */
    suspend fun appendHistory(record: DictationRecord) {
        if (record.words.isEmpty()) return
        context.dataStore.edit { p ->
            val current = DictationHistoryCodec.decode(p[Keys.HISTORY] ?: "")
            // 落盘按时间正序（追加在尾部），读取时再倒序，见 history 的说明
            val next = DictationHistoryCodec.trim(current + record, historyLimit)
            p[Keys.HISTORY] = DictationHistoryCodec.encode(next)
        }
    }

    /** 清空全部听写历史 */
    suspend fun clearHistory() {
        context.dataStore.edit { it.remove(Keys.HISTORY) }
    }
}

/** 累计使用统计 */
data class DictationStats(
    val totalSessions: Int = 0,
    val totalMillis: Long = 0L,
)
