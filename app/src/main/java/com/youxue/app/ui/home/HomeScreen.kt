package com.youxue.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youxue.app.YouxueApplication
import com.youxue.app.ui.theme.BlueContainer
import com.youxue.app.ui.theme.BluePrimary
import com.youxue.app.ui.theme.BluePrimaryDark
import com.youxue.app.ui.theme.OrangeAccent
import com.youxue.app.ui.theme.OrangeContainer
import com.youxue.app.ui.theme.Spacing
import com.youxue.app.ui.theme.SuccessRed
import com.youxue.app.util.formatDuration

/** 每行放几个入口。四列是手机宽度下「排得下、图标又还看得清」的实用上限 */
private const val GRID_COLUMNS = 4

/**
 * 功能入口定义。
 *
 * 做成数据驱动而不是硬编码在布局里，是因为首页定位就是「入口聚合页」，
 * 后续每加一个功能只需要往这张表里添一行。
 * 只放**已经能用**的功能——没做完的不进表。入口页上出现点不动的格子，
 * 用户的第一反应是「这 App 坏了」，而不是「哦，还没做」。想预告就写在更新说明里，
 * 不要摆成一个假的按钮。
 */
private data class FeatureEntry(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val tint: Color,
    val container: Color,
)

private val features = listOf(
    FeatureEntry(
        id = "dictation",
        title = "听写词语",
        icon = Icons.Filled.Mic,
        tint = BluePrimary,
        container = BlueContainer,
    ),
    FeatureEntry(
        id = "tasks",
        title = "学习任务",
        icon = Icons.Filled.Checklist,
        tint = Color(0xFF7F77DD),
        container = Color(0xFFEEEDFE),
    ),
    FeatureEntry(
        id = "plans",
        title = "学习计划",
        icon = Icons.Filled.CalendarMonth,
        tint = OrangeAccent,
        container = OrangeContainer,
    ),
)

@Composable
fun HomeScreen(
    onOpenDictation: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenPlans: () -> Unit,
    viewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(
            YouxueApplication.get().settingsRepository,
            YouxueApplication.get().planRepository,
            YouxueApplication.get().taskRepository,
        )
    ),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val hasDraft by viewModel.hasDraft.collectAsStateWithLifecycle()
    val planPending by viewModel.pendingToday.collectAsStateWithLifecycle()
    val taskPending by viewModel.taskPending.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Spacing.xxl),
        ) {
            item { BrandHeader(stats = stats) }

            item {
                SectionTitle(
                    title = "开始学习",
                    modifier = Modifier.padding(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        top = Spacing.xl,
                        bottom = Spacing.md,
                    ),
                )
            }

            item {
                FeatureGrid(
                    features = features,
                    hasDraft = hasDraft,
                    planPending = planPending,
                    taskPending = taskPending,
                    // 按 id 分派而不是所有格子都走同一个回调：
                    // 首页是入口聚合页，每加一个功能就多一个去向
                    onOpen = { id ->
                        when (id) {
                            "dictation" -> onOpenDictation()
                            "tasks" -> onOpenTasks()
                            "plans" -> onOpenPlans()
                        }
                    },
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }

            item {
                Spacer(Modifier.height(Spacing.lg))
                //FooterNote()
            }
        }
    }
}

/**
 * 品牌头部。
 *
 * 用渐变块而不是简单的 AppBar，是因为首页要给家长/学生一个「这是一款正经学习工具」
 * 的第一印象，同时顺带把累计数据露出来，让坚持使用有正反馈。
 */
@Composable
private fun BrandHeader(stats: HomeStats) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    colors = listOf(BluePrimary, BluePrimaryDark),
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(
                start = Spacing.xl,
                end = Spacing.xl,
                top = Spacing.xl,
                bottom = Spacing.xl,
            ),
    ) {
        Column {
            Text(
                text = "优学",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "（单机版）数据保存在本机，删除App会清空数据",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )

            Spacer(Modifier.height(Spacing.xs))
/*
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                HeaderStatChip(
                    icon = Icons.Filled.Mic,
                    label = "听写次数",
                    value = stats.totalSessions.toString(),
                    modifier = Modifier.weight(1f),
                )
                HeaderStatChip(
                    icon = Icons.Filled.Timer,
                    label = "累计用时",
                    value = formatDuration(stats.totalMillis),
                    modifier = Modifier.weight(1f),
                )
            }

 */
        }
    }
}

@Composable
private fun HeaderStatChip(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.16f))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier,
    )
}

/**
 * 入口宫格。
 *
 * 手工分行而不是用 `LazyVerticalGrid`：入口数量是个位数量级，
 * 而且它嵌在 `LazyColumn` 里——嵌一层可滚动容器就要处理高度约束，
 * 得不偿失。`chunked` 手动分行，代码更短、行为更好预测。
 *
 * 末行不足一行时用等宽空位补齐：不补的话 `weight` 会把剩下的格子撑成两倍宽，
 * 图标列宽随着功能数量变化而漂移，加一个功能前面全要挪位。
 */
@Composable
private fun FeatureGrid(
    features: List<FeatureEntry>,
    hasDraft: Boolean,
    planPending: Int,
    taskPending: Int,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        features.chunked(GRID_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                row.forEach { feature ->
                    FeatureCell(
                        feature = feature,
                        showDraftBadge = feature.id == "dictation" && hasDraft,
                        pendingCount = pendingBadgeOf(feature.id, planPending, taskPending),
                        onClick = { onOpen(feature.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(GRID_COLUMNS - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 哪个入口挂角标、挂几件。
 *
 * 抽成一处而不是在 `FeatureCell` 里按 id 写 `if`：角标是**入口自己的属性**，
 * 每加一个会攒待办的功能就在这里多一个分支，格子那层不需要知道有哪几个功能。
 * 以后若要给入口加「新内容」之类的其它标记，也从这里出去。
 */
private fun pendingBadgeOf(id: String, planPending: Int, taskPending: Int): Int = when (id) {
    "tasks" -> taskPending
    "plans" -> planPending
    else -> 0
}

/**
 * 一个入口格子：图标在上、标题在下。
 *
 * 一行四个意味着每格只有 70dp 上下，所以只留「图标 + 标题」两层信息。
 * 副标题在这里放不下，也没必要——标题本身已经是最准的说明，
 * 挤一行小字进去只会让标题被压到两行甚至省略号。
 *
 * 也不用 Card 包一层：四个格子挨着时，每格再加底色和阴影会碎成一片，
 * 反而不如让图标自己的底色说话。
 */
@Composable
private fun FeatureCell(
    feature: FeatureEntry,
    showDraftBadge: Boolean,
    pendingCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(feature.container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = feature.icon,
                    contentDescription = null,
                    tint = feature.tint,
                    modifier = Modifier.size(26.dp),
                )
            }

            // 草稿提示缩成右上角一个小圆点：宫格里挂不下「有草稿」三个字，
            // 而这件事只需要「提醒存在」，不需要在这里解释它是什么。
            if (showDraftBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-3).dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(OrangeAccent),
                )
            }

            // 待办用数字而不是圆点：这里「还有几件」本身就是用户想立刻知道的信息，
            // 一个不带动词的红点说不清是「有新内容」还是「有活没干」。
            // 任务和计划各挂各的，且和上面的草稿点是互斥的（不同功能），不会同时出现
            if (pendingCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-6).dp)
                        .defaultMinSize(minWidth = 17.dp, minHeight = 17.dp)
                        .clip(CircleShape)
                        .background(SuccessRed)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = pendingCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        Text(
            text = feature.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FooterNote() {
    Text(
        text = "所有数据都保存在本机，不需要联网",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl),
    )
}
