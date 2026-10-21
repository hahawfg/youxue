package cn.com.toobug.youxue.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import cn.com.toobug.youxue.plan.PlanCalendar
import cn.com.toobug.youxue.plan.PlanSchedule
import cn.com.toobug.youxue.ui.theme.BluePrimary
import cn.com.toobug.youxue.ui.theme.Spacing
import cn.com.toobug.youxue.ui.theme.WarningGreen
import cn.com.toobug.youxue.util.DayMath

/** 近 30 天完成率的口径 */
private const val RECENT_DAYS = 30

/**
 * 计划详情：一个月的打卡情况 + 累计成绩。
 *
 * ViewModel 是列表页那份（同一个实例），所以在这里打卡、改规则，
 * 返回列表页立刻就是新的——见 [PlanViewModel] 的说明。
 */
@Composable
fun PlanDetailScreen(
    planId: String,
    viewModel: PlanViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val plan = state.planById(planId)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (plan == null) {
            // 只可能是两种情况：还在读，或者这条计划刚被自己删掉
            GoneBody(loading = state.loading, onBack = onBack)
        } else {
            PlanDetailBody(
                plan = plan,
                state = state,
                viewModel = viewModel,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun PlanDetailBody(
    plan: StudyPlan,
    state: PlanUiState,
    viewModel: PlanViewModel,
    onBack: () -> Unit,
) {
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // loading 期间 state.today 还是 0，先拿系统日期占位，免得月历闪一下 1970 年
    val fallbackToday = remember { DayMath.today() }
    val today = if (state.today != 0L) state.today else fallbackToday

    // 月份状态留在这里而不是塞进共享的 ViewModel：翻月是纯展示、不动数据，
    // 放进共享状态会让列表页也跟着重组一次。
    // key 带上 today：从占位值切到真值时重置一次，之后翻月不再被重置
    var year by remember(plan.id, today) { mutableStateOf(DayMath.ymd(today).year) }
    var month by remember(plan.id, today) { mutableStateOf(DayMath.ymd(today).month) }

    val records = state.records
    val cells = remember(plan, records, year, month, today) {
        PlanCalendar.monthCells(plan, records, year, month, today)
    }
    val monthSummary = remember(cells) { PlanCalendar.summaryOfMonth(cells) }
    val recent = remember(plan, records, today) {
        PlanCalendar.summaryOfRecent(plan, records, today, RECENT_DAYS)
    }
    val streak = remember(plan, records, today) { PlanSchedule.streak(plan, records, today) }
    val totalDone = remember(plan, records) { PlanSchedule.totalDoneDays(plan, records) }
    val doneToday = PlanSchedule.isDoneOn(plan.id, today, records)
    val dueToday = PlanSchedule.isDueOn(plan, today)

    Column(Modifier.fillMaxSize()) {
        DetailTopBar(
            plan = plan,
            onBack = onBack,
            onEdit = { editor = EditorTarget.from(plan) },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xxl),
        ) {
            TodayAction(
                due = dueToday,
                done = doneToday,
                nextDay = if (dueToday) null else PlanSchedule.nextDueDay(plan, today),
                onToggle = { viewModel.toggleToday(plan.id) },
            )

            Spacer(Modifier.height(Spacing.lg))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                StatCard(
                    value = streak.toString(),
                    unit = "天",
                    label = "连续打卡",
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    value = (recent.rate * 100).toInt().toString(),
                    unit = "%",
                    label = "近 $RECENT_DAYS 天",
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    value = totalDone.toString(),
                    unit = "次",
                    label = "累计完成",
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(Spacing.lg))

            MonthCard(
                year = year,
                month = month,
                cells = cells,
                summary = monthSummary,
                onPrev = {
                    val prev = DayMath.shiftMonth(year, month, -1)
                    year = prev.year
                    month = prev.month
                },
                onNext = {
                    val next = DayMath.shiftMonth(year, month, 1)
                    year = next.year
                    month = next.month
                },
            )

            Spacer(Modifier.height(Spacing.lg))

            Legend()

            Spacer(Modifier.height(Spacing.xl))

            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("删除这个计划", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    editor?.let { target ->
        PlanEditorSheet(
            target = target,
            onDismiss = { editor = null },
            onSave = { title, weekdays, startDay, endDay, enabled ->
                viewModel.update(plan.id, title, weekdays, startDay, endDay, enabled)
                editor = null
            },
            onDelete = {
                editor = null
                confirmDelete = true
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个计划？") },
            text = { Text("「${plan.title}」和它的全部打卡记录都会被删除，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.remove(plan.id)
                    onBack()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

// ------------------------------------------------------------------ 顶部

@Composable
private fun DetailTopBar(plan: StudyPlan, onBack: () -> Unit, onEdit: () -> Unit) {
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
                text = plan.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = PlanSchedule.repeatLabel(plan.weekdays) +
                    if (plan.enabled) "" else " · 已停用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "编辑计划",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

// ------------------------------------------------------------------ 今日操作

/**
 * 今天这一条。
 *
 * 三种形态：该做且没做（实心按钮，引导动作）、该做且做了（减弱成描边按钮，
 * 点一下是撤销）、今天压根不用做（只说明下次什么时候）。
 * 撤销入口是必要的——误点一下不该只能靠改数据去修。
 */
@Composable
private fun TodayAction(
    due: Boolean,
    done: Boolean,
    nextDay: Long?,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Spacing.lg),
    ) {
        Text(
            text = if (due) "今天" else "今天不用做",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = when {
                due && done -> "已经打过卡了"
                due -> "做完了就点一下，记到今天的记录里"
                nextDay != null -> "下次是 ${DayMath.monthDayLabel(nextDay)} ${DayMath.weekLabel(nextDay)}"
                else -> "这条计划已经结束了"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (due) {
            Spacer(Modifier.height(Spacing.md))
            if (done) {
                OutlinedButton(
                    onClick = onToggle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WarningGreen),
                ) {
                    Text("撤销今天的打卡")
                }
            } else {
                Button(
                    onClick = onToggle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WarningGreen,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text("标记今天已完成")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 统计

@Composable
private fun StatCard(
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = BluePrimary,
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.labelMedium,
                color = BluePrimary,
                modifier = Modifier.padding(start = 1.dp, bottom = 3.dp),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------ 月历

@Composable
private fun MonthCard(
    year: Int,
    month: Int,
    cells: List<PlanCalendar.Cell>,
    summary: PlanCalendar.Summary,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev) {
                Icon(
                    imageVector = Icons.Filled.ChevronLeft,
                    contentDescription = "上个月",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${year}年${month}月",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = "下个月",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(Spacing.xs))

        Text(
            text = monthSummaryText(summary),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Spacing.md))

        Row(Modifier.fillMaxWidth()) {
            PlanSchedule.WEEK_SHORT.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(Spacing.xs))

        // 42 格固定 6 行（见 DayMath.monthGrid）：翻月时高度不变，
        // 上面那两个翻月按钮不会从指下溜走
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            cells.chunked(7).forEach { week ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    week.forEach { cell ->
                        MonthDayCell(cell = cell, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 这一个月到底做得怎么样。该做 0 次时不说「0/0」，那看着像出错 */
private fun monthSummaryText(summary: PlanCalendar.Summary): String = when {
    summary.due == 0 -> "这个月没有安排"
    summary.done >= summary.due -> "这个月 $summary.due 次都完成了"
    else -> "这个月完成 ${summary.done} / ${summary.due} 次"
}

/**
 * 月历里的一格。
 *
 * 三种底色：已打卡（实心绿）、该做但没打（浅灰）、不用做（透明）。
 * 「今天」用**描边**而不是换底色——底色已经在表达打卡状态了，
 * 再拿它表示「今天」就会互相盖掉（今天既可能已打卡、也可能没打）。
 */
@Composable
private fun MonthDayCell(cell: PlanCalendar.Cell, modifier: Modifier = Modifier) {
    val fill = when {
        cell.done -> WarningGreen
        cell.repeatDay && !cell.future -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    val textColor = when {
        cell.done -> Color.White
        // 补位格（上月/下月）压淡，让本月的范围一眼可辨
        !cell.inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        cell.future -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier.aspectRatio(1f).padding(1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(fill)
                .then(
                    if (cell.today) Modifier.border(2.dp, BluePrimary, CircleShape) else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = DayMath.dayOfMonth(cell.day).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
            )
        }
    }
}

@Composable
private fun Legend() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        LegendRow(color = WarningGreen, text = "打过卡")
        LegendRow(
            color = MaterialTheme.colorScheme.surfaceVariant,
            text = "该做，但那天没打",
            outlined = true,
        )
        LegendRow(color = BluePrimary, text = "今天", ring = true)
    }
}

@Composable
private fun LegendRow(
    color: Color,
    text: String,
    outlined: Boolean = false,
    ring: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (ring) Color.Transparent else color)
                .then(
                    if (outlined || ring) Modifier.border(1.5.dp, color, CircleShape) else Modifier
                ),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------ 异常态

@Composable
private fun GoneBody(loading: Boolean, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (loading) "正在读取…" else "这个计划已经不在了",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!loading) {
                Spacer(Modifier.height(Spacing.md))
                TextButton(onClick = onBack) { Text("返回") }
            }
        }
    }
}
