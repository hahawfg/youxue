package cn.com.toobug.youxue.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.com.toobug.youxue.YouxueApplication
import cn.com.toobug.youxue.data.DictationRecord
import cn.com.toobug.youxue.dictation.DictationViewModel
import cn.com.toobug.youxue.tts.SpeechLanguage
import cn.com.toobug.youxue.ui.theme.BlueContainer
import cn.com.toobug.youxue.ui.theme.BluePrimary
import cn.com.toobug.youxue.ui.theme.OrangeAccent
import cn.com.toobug.youxue.ui.theme.Spacing
import cn.com.toobug.youxue.util.formatDateMinute
import cn.com.toobug.youxue.util.formatDurationChinese

/**
 * 听写记录页。
 *
 * 记录里只有词语（外加时间与用时），所以这一页的任务很单纯：
 * 让用户**快速找到「那次听写了哪些词」**。因此每条记录直接把词平铺出来，
 * 不做折叠——折叠一层就等于每次都要多点一下，而这里本来就没有隐私内容要藏。
 *
 * 每个词都能点一下重听，沿用结果页那套交互：家长拿着手机给孩子听写时，
 * 经常需要「把上次那批词再报一遍」。
 */
@Composable
fun DictationHistoryScreen(
    onBack: () -> Unit,
    viewModel: DictationHistoryViewModel = viewModel(
        factory = DictationHistoryViewModel.factory(
            YouxueApplication.get().settingsRepository,
        )
    ),
    // 重听复用听写页的 ViewModel：它的 replayText 只依赖 TTS 引擎，与会话状态无关
    speaker: DictationViewModel = viewModel(
        factory = DictationViewModel.factory(
            YouxueApplication.get().ttsEngine,
            YouxueApplication.get().settingsRepository,
        )
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showClearDialog by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<Long?>(null) }

    // 复制整批词语。
    //
    // 用换行而不是「、」拼接：粘到别处仍是一行一个词，更省事的是——
    // 粘回听写录入框能直接被 WordParser 逐行认成词表，
    // 「把上次那批词再听一遍」不用一个字一个字敲。
    // 所以也不带序号：序号虽然能被录入页剥掉，但粘到便签里就是多余的噪声。
    val copyWords: (DictationRecord) -> Unit = { record ->
        val text = record.words.joinToString("\n")
        if (text.isNotEmpty()) {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("听写词语", text))
            Toast.makeText(
                context,
                "已复制 ${record.wordCount} 个词",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            HistoryTopBar(
                count = state.records.size,
                onBack = onBack,
                onClear = { showClearDialog = true },
                clearEnabled = state.records.isNotEmpty(),
            )

            when {
                state.loading -> LoadingPlaceholder()

                state.isEmpty -> EmptyPlaceholder()

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        top = Spacing.md,
                        bottom = Spacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    items(state.records, key = { it.completedAt }) { record ->
                        HistoryCard(
                            record = record,
                            expanded = expandedId == record.completedAt,
                            onToggleExpand = {
                                expandedId = if (expandedId == record.completedAt) {
                                    null
                                } else {
                                    record.completedAt
                                }
                            },
                            onPlayWord = speaker::replayText,
                            onCopy = { copyWords(record) },
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空听写记录？") },
            text = { Text("只会删掉这份历史列表，累计听写次数和用时不受影响。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    viewModel.clearAll()
                }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HistoryTopBar(
    count: Int,
    onBack: () -> Unit,
    onClear: () -> Unit,
    clearEnabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = "听写记录",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = if (count > 0) "最近 $count 次" else "还没有记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 清空是破坏性动作，但也不该藏太深——记录页本来就是「想清就清」的地方。
        // 点开还有一次二次确认，所以放在顶栏没问题
        IconButton(onClick = onClear, enabled = clearEnabled) {
            Icon(
                imageVector = Icons.Filled.DeleteOutline,
                contentDescription = "清空记录",
                tint = if (clearEnabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                },
            )
        }
    }
}

/**
 * 一条记录。
 *
 * 词语**默认只展示一行**（超出省略），点一下才展开全部——
 * 一次听写可能二三十个词，全展开会让列表变成一堵墙，
 * 想找「上周三那次」要滑很久。所以默认紧凑、需要时再看全。
 *
 * 复制按钮则**常显、不进折叠区**：它的用处是把整批词搬去别处（打印、发微信、
 * 或粘回录入框再听一遍），没必要为了复制先展开一次。
 */
@Composable
private fun HistoryCard(
    record: DictationRecord,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onPlayWord: (String) -> Unit,
    onCopy: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BlueContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = null,
                        tint = BluePrimary,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = formatDateMinute(record.completedAt),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${record.wordCount} 个词 · 用时 ${formatDurationChinese(record.elapsedMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = onCopy,
                    enabled = record.words.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "复制这批词语",
                        tint = BluePrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))

            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    record.words.forEachIndexed { i, word ->
                        WordRow(index = i + 1, word = word, onPlay = onPlayWord)
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                TextButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "收起",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // 收起态：把词拼成一段，只给一行。
                // 用「、」分隔而不是原来的换行——一行里换行会变成多行，
                // 而这里的意图恰恰是「一眼扫过、知道是些什么词」
                Text(
                    text = record.words.joinToString("、"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleExpand),
                )
                if (record.words.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = "点开看全部 ${record.wordCount} 个词",
                        style = MaterialTheme.typography.labelSmall,
                        color = BluePrimary,
                        modifier = Modifier.clickable(onClick = onToggleExpand),
                    )
                }
            }
        }
    }
}

/** 展开态里的一个词：序号 + 词语 + 重听 */
@Composable
private fun WordRow(index: Int, word: String, onPlay: (String) -> Unit) {
    val language = SpeechLanguage.detectFor(word)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(start = Spacing.md, end = Spacing.xs, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = OrangeAccent,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp),
        )
        Text(
            text = word,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = language.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        IconButton(
            onClick = { onPlay(word) },
            modifier = Modifier.size(38.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.VolumeUp,
                contentDescription = "重听",
                tint = BluePrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun LoadingPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = BluePrimary)
    }
}

@Composable
private fun EmptyPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xxl, start = Spacing.xl, end = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "还没有听写记录",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "完成一次听写后，这里会记下那批词语、时间和用时。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
