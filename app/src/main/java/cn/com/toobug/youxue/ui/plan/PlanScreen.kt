package cn.com.toobug.youxue.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.com.toobug.youxue.data.StudyPlan
import cn.com.toobug.youxue.ui.theme.BluePrimary
import cn.com.toobug.youxue.ui.theme.Spacing
import cn.com.toobug.youxue.ui.theme.WarningGreen
import cn.com.toobug.youxue.util.DayMath

/**
 * 「每天」在数据层是七个全选，界面侧不产生空集合。
 *
 * 不用 private：下面 [EditorTarget] 是 internal 的，而它的默认参数引用了本值
 * ——默认参数的可见性不能低于声明它的类型，否则编译器会拒绝。
 * 弹层那边也复用这一份，避免同包出现两个同名顶层常量。
 */
internal val ALL_DAYS: Set<Int> = (0..6).toSet()

/**
 * 编辑弹层的初始值。
 *
 * 新建与编辑共用一份结构，只是 [id] 为 null 与否的差别——
 * 两条路径的字段完全一样，分成两种状态只会让弹层里到处是 if。
 */
internal data class EditorTarget(
    val id: String? = null,
    val title: String = "",
    val weekdays: Set<Int> = ALL_DAYS,
    val startDay: Long = 0L,
    val endDay: Long? = null,
    val enabled: Boolean = true,
) {
    companion object {
        fun new(today: Long) = EditorTarget(startDay = today)

        fun from(plan: StudyPlan) = EditorTarget(
            id = plan.id,
            title = plan.title,
            weekdays = plan.weekdays,
            startDay = plan.startDay,
            endDay = plan.endDay,
            enabled = plan.enabled,
        )
    }
}

/**
 * 学习计划页。
 *
 * 两段式：上面是**今天要做的**（没打的在前，打完的沉下去），下面是**今天不用做的**。
 * 只做「今天」一个口径，没有日期条——计划是习惯，用户关心的永远是今天，
 * 翻历史交给详情页的月历。这和任务页（可以随便挑一天看）的定位不同。
 *
 * ViewModel 由外部传入而不是在这里 `viewModel()`：详情页要和它共用同一个实例，
 * 具体见 [PlanViewModel] 的说明。
 */
@Composable
fun PlanScreen(
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    viewModel: PlanViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var pendingDelete by remember { mutableStateOf<StudyPlan?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                PlanTopBar(state = state, onBack = onBack)

                when {
                    state.loading -> LoadingBody()
                    state.isEmpty -> EmptyBody(onCreate = { editor = EditorTarget.new(state.today) })
                    else -> PlanList(
                        state = state,
                        onToggle = viewModel::toggleToday,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }

            FloatingActionButton(
                onClick = { editor = EditorTarget.new(state.today) },
                containerColor = BluePrimary,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(Spacing.lg),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "新建计划")
            }
        }
    }

    editor?.let { target ->
        PlanEditorSheet(
            target = target,
            onDismiss = { editor = null },
            onSave = { title, weekdays, startDay, endDay, enabled ->
                if (target.id == null) {
                    viewModel.add(title, weekdays, startDay, endDay)
                } else {
                    viewModel.update(target.id, title, weekdays, startDay, endDay, enabled)
                }
                editor = null
            },
            // 用 let 而不是 `if (target.id != null)`：后者靠 smart cast，
            // 在嵌套 lambda 里不一定成立，写出来编译器也未必认
            onDelete = target.id?.let { id ->
                {
                    pendingDelete = state.planById(id)
                    editor = null
                }
            },
        )
    }

    pendingDelete?.let { plan ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个计划？") },
            // 记录会一起删掉这件事必须说出来，否则用户以为还能回看历史
            text = { Text("「${plan.title}」和它的全部打卡记录都会被删除，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(plan.id)
                    pendingDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

// ------------------------------------------------------------------ 顶部

@Composable
private fun PlanTopBar(state: PlanUiState, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = "学习计划",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = when {
                    state.loading -> "正在读取…"
                    state.isEmpty -> "还没有计划"
                    state.allDoneToday -> "今天的都完成了"
                    else -> "今天还有 ${state.pendingCount} 项待完成"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.allDoneToday) WarningGreen
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ 列表

@Composable
private fun PlanList(
    state: PlanUiState,
    onToggle: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val today = state.today

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.lg,
            end = Spacing.lg,
            bottom = 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(key = "header-today") {
            SectionHeader(
                title = "今天 · ${DayMath.monthDayLabel(today)} ${DayMath.weekLabel(today)}",
                note = if (state.dueToday.isEmpty()) null else "已做 ${state.dueToday.count { it.done }} / ${state.dueToday.size}",
            )
        }

        if (state.dueToday.isEmpty()) {
            item(key = "today-empty") { TodayEmptyRow() }
        } else {
            items(state.dueToday, key = { it.plan.id }) { entry ->
                PlanCard(
                    entry = entry,
                    onToggle = { onToggle(entry.plan.id) },
                    onOpen = { onOpenDetail(entry.plan.id) },
                )
            }
        }

        if (state.others.isNotEmpty()) {
            item(key = "header-others") {
                SectionHeader(
                    title = "其它计划",
                    note = "今天不用做",
                    modifier = Modifier.padding(top = Spacing.lg),
                )
            }
            items(state.others, key = { it.plan.id }) { entry ->
                PlanCard(
                    entry = entry,
                    onToggle = null,
                    onOpen = { onOpenDetail(entry.plan.id) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    note: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一条计划的卡片。
 *
 * [onToggle] 为 null 表示不提供打卡入口（今天不用做的那些）。
 * 打卡按钮和卡片本身是两个点击目标，各管各的：点圈是打卡，点其余部分是看详情。
 */
@Composable
private fun PlanCard(
    entry: PlanEntry,
    onToggle: (() -> Unit)?,
    onOpen: () -> Unit,
) {
    val plan = entry.plan
    val dimmed = !plan.enabled

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)
            .padding(end = Spacing.md, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onToggle != null) {
            DoneToggle(done = entry.done, onClick = onToggle)
        } else {
            Spacer(Modifier.width(Spacing.md))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = plan.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (entry.done) FontWeight.Normal else FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitleOf(entry),
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    dimmed -> MaterialTheme.colorScheme.onSurfaceVariant
                    entry.done -> WarningGreen
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** 副标题把「什么时候做」和「坚持得怎么样」两句揉成一行 */
private fun subtitleOf(entry: PlanEntry): String {
    val plan = entry.plan
    val parts = mutableListOf<String>()

    if (!plan.enabled) parts += "已停用"
    parts += entry.repeatLabel

    if (entry.done) {
        parts += "今天已完成"
    } else if (entry.nextDay != null) {
        val next = entry.nextDay
        parts += "下次 ${DayMath.monthDayLabel(next)} ${DayMath.weekLabel(next)}"
    }

    // 连续 0 天不值得占一行字，用户看到「连续 0 天」只觉得被冒犯
    if (entry.streak > 0) parts += "连续 ${entry.streak} 天"

    return parts.joinToString(" · ")
}

/**
 * 打卡圆圈。
 *
 * 触摸区 44dp、可见圆 28dp：手指按不准 28dp 的目标，
 * 而视觉上只需要一个明确的圆点。这也是它没有做成 IconButton 的原因——
 * IconButton 的点击区就是它 40dp 的图标框，尺寸不好两边兼顾。
 */
@Composable
private fun DoneToggle(done: Boolean, onClick: () -> Unit) {
    val accent = WarningGreen
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (done) accent else MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 1.5.dp,
                    color = if (done) accent else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "取消今天的打卡",
                    tint = Color.White,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 空态

@Composable
private fun LoadingBody() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "正在读取…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyBody(onCreate: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = Spacing.xl),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = "还没有学习计划",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "定一个「每周一三五听写」，每天照着做，做完点一下打勾",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.lg))
            TextButton(onClick = onCreate) { Text("新建计划") }
        }
    }
}

/** 有计划、但今天一个都不用做 */
@Composable
private fun TodayEmptyRow() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Spacing.lg),
    ) {
        Text(
            text = "今天没有安排",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "按现在的规则，今天不需要听写。想每天都做就把重复日改成「每天」",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
