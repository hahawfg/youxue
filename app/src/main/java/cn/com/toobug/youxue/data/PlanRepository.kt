package cn.com.toobug.youxue.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 学习计划单独用第三份 DataStore，与设置（`youxue_prefs`）、
 * 任务（`youxue_tasks`）都分开。
 *
 * 理由和 [TaskRepository] 一样是**写入代价**：计划每次改动都要重写整份列表，
 * 挤在同一个文件里就会连带重写别人的 key。三份文件各管各的。
 *
 * 计划与记录放在**同一个**文件的两个 key 里，而不是再拆一份：
 * 两者总是一起被读（判定今天要不要做、算连续打卡都需要两边），
 * 拆开只会多一次 IO 和一处可能不同步的地方。
 */
private val Context.planDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "youxue_plans")

/**
 * 计划的持久化。
 *
 * 只暴露「读全部 / 写全部」，不做单条增删改——理由同 [TaskRepository]：
 * ViewModel 本来就持有完整列表，按目标状态整体覆盖比按指令差分更简单，
 * 也不会出现「某条记录的字段没被同步」这类问题。
 */
class PlanRepository(private val context: Context) {

    private object Keys {
        val PLANS = stringPreferencesKey("plans")
        val RECORDS = stringPreferencesKey("records")
    }

    /** 全部计划。存储层不保证顺序，展示顺序由 ViewModel 决定 */
    val plans: Flow<List<StudyPlan>> = context.planDataStore.data.map { prefs ->
        PlanCodec.decodePlans(prefs[Keys.PLANS] ?: "")
    }

    /** 全部打卡记录 */
    val records: Flow<List<PlanRecord>> = context.planDataStore.data.map { prefs ->
        PlanCodec.decodeRecords(prefs[Keys.RECORDS] ?: "")
    }

    suspend fun savePlans(plans: List<StudyPlan>) {
        val encoded = PlanCodec.encodePlans(plans)
        context.planDataStore.edit { prefs ->
            if (encoded.isEmpty()) prefs.remove(Keys.PLANS) else prefs[Keys.PLANS] = encoded
        }
    }

    suspend fun saveRecords(records: List<PlanRecord>) {
        val encoded = PlanCodec.encodeRecords(records)
        context.planDataStore.edit { prefs ->
            if (encoded.isEmpty()) prefs.remove(Keys.RECORDS) else prefs[Keys.RECORDS] = encoded
        }
    }
}
