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
 * 任务数据单独用一份 DataStore，与听写设置分开。
 *
 * 分开的原因不是偏好，而是**写入代价**：任务每次改动都要重写整份列表，
 * 如果和设置挤在同一个文件里，改一个任务会连带把设置的 key 也重写一遍。
 * 各存各的，互不干扰。
 */
private val Context.taskDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "youxue_tasks")

/**
 * 任务的持久化。
 *
 * 只暴露「读全部 / 写全部」两个动作，不做单条增删改。
 * 因为调用方（ViewModel）本来就持有完整列表，按目标状态整体覆盖
 * 比按指令差分更简单，也不会出现「某条任务的字段没被同步」这类问题。
 */
class TaskRepository(private val context: Context) {

    private object Keys {
        val TASKS = stringPreferencesKey("tasks")
    }

    /** 任务列表。存储层不保证顺序，展示顺序由分组逻辑决定 */
    val tasks: Flow<List<TaskItem>> = context.taskDataStore.data.map { prefs ->
        TaskCodec.decode(prefs[Keys.TASKS] ?: "")
    }

    suspend fun save(tasks: List<TaskItem>) {
        val encoded = TaskCodec.encode(tasks)
        context.taskDataStore.edit { prefs ->
            if (encoded.isEmpty()) prefs.remove(Keys.TASKS) else prefs[Keys.TASKS] = encoded
        }
    }
}
