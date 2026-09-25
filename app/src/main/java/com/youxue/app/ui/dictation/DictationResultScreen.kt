package com.youxue.app.ui.dictation

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youxue.app.YouxueApplication
import com.youxue.app.dictation.DictationResult
import com.youxue.app.dictation.DictationSessionStore
import com.youxue.app.dictation.DictationViewModel
import com.youxue.app.ui.theme.BluePrimary
import com.youxue.app.ui.theme.BluePrimaryDark
import com.youxue.app.ui.theme.Spacing
import com.youxue.app.util.formatDuration
import com.youxue.app.util.formatDurationChinese

/**
 * 听写结束页。
 *
 * 内容顺序即为学生核对时的自然顺序：先看用了多久 → 再逐个核对词语 →
 * 最后决定干什么。每个词都能单独重听，因为「这个词没听清，想确认写对没有」
 * 是核对阶段最高频的动作。
 *
 * 结果数据从 [DictationSessionStore] 取，而不是本页的 ViewModel：
 * 本页与听写页是两个路由、两个 ViewModel 实例，直接读自己的实例只会拿到 null。
 */
@Composable
fun DictationResultScreen(
    onRestart: () -> Unit,
    onNewWords: () -> Unit,
    onHome: () -> Unit,
    viewModel: DictationViewModel = viewModel(
        factory = DictationViewModel.factory(
            YouxueApplication.get().ttsEngine,
            YouxueApplication.get().settingsRepository,
        )
    ),
) {
    val result by DictationSessionStore.result.collectAsStateWithLifecycle()

    // 结果为空说明是异常进入（比如进程被回收后直接恢复到本路由）。
    // 兜底退回听写页，而不是留一个白屏让用户困惑。
    LaunchedEffect(result == null) {
        if (result == null) onNewWords()
    }

    val r = result ?: run {
        LoadingPlaceholder()
        return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            ResultHeader(
                elapsedMillis = r.elapsedMillis,
                wordCount = r.wordCount,
                completed = r.completed,
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = Spacing.lg,
                    bottom = Spacing.lg,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item {
                    StatRow(
                        elapsedMillis = r.elapsedMillis,
                        wordCount = r.wordCount,
                        averageMillis = r.averageMillisPerWord,
                    )
                }

                item {
                    Text(
                        text = "本次词语（共 ${r.wordCount} 个）",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }

                itemsIndexed(r.words) { index, word ->
                    WordReviewRow(
                        index = index,
                        word = word,
                        onReplay = { viewModel.replayText(word) },
                    )
                }

                item {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = "所有数据都保存在本机，不需要联网",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            ResultActions(
                onRestart = onRestart,
                onNewWords = onNewWords,
                onHome = onHome,
            )
        }
    }
}

/** 结果尚未就绪时的占位，避免出现白屏 */
@Composable
private fun LoadingPlaceholder() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = BluePrimary)
        }
    }
}

/** 顶部结果横幅 */
@Composable
private fun ResultHeader(
    elapsedMillis: Long,
    wordCount: Int,
    completed: Boolean,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(BluePrimary, BluePrimaryDark)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(
                start = Spacing.xl,
                end = Spacing.xl,
                top = Spacing.xl,
                bottom = Spacing.xl,
            ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }

            Spacer(Modifier.height(Spacing.md))

            Text(
                text = if (completed) "听写完成" else "本次听写已结束",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(Spacing.sm))

            // 总用时做成醒目的主指标：这是学生/家长最关心的一件事
            Text(
                text = formatDuration(elapsedMillis),
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "总用时",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
            )

            Spacer(Modifier.height(Spacing.sm))

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.White.copy(alpha = 0.18f),
            ) {
                Text(
                    text = "共 $wordCount 个词语 · ${formatDurationChinese(elapsedMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.95f),
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                )
            }
        }
    }
}

/** 三项统计 */
@Composable
private fun StatRow(
    elapsedMillis: Long,
    wordCount: Int,
    averageMillis: Long,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatCell(
                label = "总用时",
                value = formatDuration(elapsedMillis),
                modifier = Modifier.weight(1f),
                highlight = true,
            )
            VerticalDivider()
            StatCell(
                label = "词语数",
                value = wordCount.toString(),
                modifier = Modifier.weight(1f),
            )
            VerticalDivider()
            StatCell(
                label = "平均每词",
                value = if (wordCount == 0) "—" else "${averageMillis / 1000} 秒",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(36.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = if (highlight) BluePrimary else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 单个词语的回顾行，可单独重听 */
@Composable
private fun WordReviewRow(
    index: Int,
    word: String,
    onReplay: () -> Unit,
) {
    var playing by remember(word) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Spacing.lg,
                    end = Spacing.sm,
                    top = Spacing.md,
                    bottom = Spacing.md,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(Spacing.md))

            Text(
                text = word,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = {
                    playing = true
                    onReplay()
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.VolumeUp,
                    contentDescription = "重听「$word」",
                    tint = if (playing) BluePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 底部动作按钮 */
@Composable
private fun ResultActions(
    onRestart: () -> Unit,
    onNewWords: () -> Unit,
    onHome: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(
                    onClick = onNewWords,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ListAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("换词语")
                }

                Button(
                    onClick = onRestart,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("再听一遍")
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onHome)
                    .padding(vertical = Spacing.md),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = "返回首页",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
