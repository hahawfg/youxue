package cn.com.toobug.youxue.ui.task

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.com.toobug.youxue.YouxueApplication
import cn.com.toobug.youxue.data.TaskItem
import cn.com.toobug.youxue.task.GroupAccent
import cn.com.toobug.youxue.task.TaskGroup
import cn.com.toobug.youxue.task.TaskUiState
import cn.com.toobug.youxue.ui.theme.BlueContainer
import cn.com.toobug.youxue.ui.theme.BluePrimary
import cn.com.toobug.youxue.ui.theme.OrangeAccent
import cn.com.toobug.youxue.ui.theme.Spacing
import cn.com.toobug.youxue.util.DayMath
import kotlinx.coroutines.launch
import org.intellij.lang.annotations.JdkConstants.HorizontalAlignment
import kotlin.math.roundToInt

/** 拖拽中的视觉状态 */
private data class DragVisual(
    val task: TaskItem,
    val startLeft: Float,
    val startTop: Float,
    val widthPx: Int,
    val accumX: Float = 0f,
    val accumY: Float = 0f,
    /** 当前指针悬停在哪个日期上；null 表示不在任何日期格上 */
    val hoverDay: Long? = null,
)

/**
 * 拖拽的共享状态。
 *
 * 之所以放在页面级而不是卡片级：浮层要画在所有内容之上，命中的日期格又分布在
 * 另一棵子树里，只有它们的共同祖先才能同时看到两者。
 *
 * 注意 [visual] 是 Compose 状态，任何读取它的作用域都会在拖动时重组。
 * 因此读取点都刻意收紧在最小范围（浮层、单张卡片、单个日期格），
 * 并且用 `derivedStateOf` 把「是否与我有关」这个布尔值挡了一层——
 * 否则拖一张卡片会导致整个列表每帧重组。
 */
@Stable
private class TaskDragState {

    var visual by mutableStateOf<DragVisual?>(null)
        private set

    /** 日期格在根坐标系里的位置，由各格子自己上报 */
    val targets = mutableStateMapOf<Long, Rect>()

    /** 页面根容器的原点，用于把根坐标换算成浮层自身坐标 */
    var containerOrigin: Offset = Offset.Zero

    /** 最近一次拖拽结束的时刻，用来屏蔽紧随其后的那次点击 */
    private var dragEndedAt = 0L

    fun targetAt(pointInRoot: Offset): Long? =
        targets.entries.firstOrNull { it.value.contains(pointInRoot) }?.key

    fun isDragging(taskId: String): Boolean = visual?.task?.id == taskId

    fun begin(task: TaskItem, cardTopLeft: Offset, localTouch: Offset, size: IntSize) {
        visual = DragVisual(
            task = task,
            startLeft = cardTopLeft.x - containerOrigin.x,
            startTop = cardTopLeft.y - containerOrigin.y,
            widthPx = size.width,
            hoverDay = targetAt(cardTopLeft + localTouch),
        )
    }

    fun update(localTouch: Offset, delta: Offset, cardTopLeft: Offset) {
        val v = visual ?: return
        visual = v.copy(
            accumX = v.accumX + delta.x,
            accumY = v.accumY + delta.y,
            hoverDay = targetAt(cardTopLeft + localTouch),
        )
    }

    /** @return 松手时悬停的日期；null 表示没落在任何日期上，应视为取消 */
    fun finish(): Long? {
        val day = visual?.hoverDay
        visual = null
        dragEndedAt = System.currentTimeMillis()
        return day
    }

    fun cancel() {
        visual = null
        dragEndedAt = System.currentTimeMillis()
    }

    /**
     * 拖拽刚结束时的那次点击应当忽略。
     *
     * 正常情况下长按拖拽会消费掉抬手事件、点击不会触发；这里再加一道时间窗口兜底，
     * 免得依赖手势库内部的事件消费时机——那种依赖一旦版本升级变了就很难排查。
     */
    fun shouldIgnoreClick(): Boolean =
        System.currentTimeMillis() - dragEndedAt < CLICK_GUARD_MILLIS

    private companion object {
        const val CLICK_GUARD_MILLIS = 250L
    }
}

/** 编辑弹层的目标：id 为 null 表示新建 */
private data class EditorTarget(
    val id: String? = null,
    val title: String = "",
    val content: String = "",
    val endDay: Long? = null,
)

/**
 * 学习任务页。
 *
 * 交互模型的核心是：「日期非必填」与「拖动绑定」其实是同一件事的两面——
 * 正因为可以先不填日期，拖动才有意义。所以列表里必须有个「未安排」的归属地，
 * 否则任务一拖出去就没地方放。
 *
 * 日期语义是**区间**：任务从创建那天开始「要做」，一直到截止日为止，
 * 所以日期条上那些格子会连着亮角标，切到区间里的任何一天都能看到它。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(
    onBack: () -> Unit,
    viewModel: TaskViewModel = viewModel(
        factory = TaskViewModel.factory(YouxueApplication.get().taskRepository)
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val drag = remember { TaskDragState() }
    val listScroll = rememberScrollState()
    val stripScroll = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var pendingDelete by remember { mutableStateOf<TaskItem?>(null) }

    // 载入完成后把日期条滚到今天附近，让「今天」一开始就在视野里
    LaunchedEffect(state.loading, state.stripDays.isNotEmpty()) {
        if (!state.loading && state.stripDays.isNotEmpty()) {
            val index = state.stripDays.indexOf(state.today)
            if (index >= 0) stripScroll.scrollToItem((index - 2).coerceAtLeast(0))
        }
    }

    // 换日期时列表回到顶部。否则会停在上一格内容的中间，看着像「没切过去」
    LaunchedEffect(state.selectedDay) {
        if (!state.loading) listScroll.animateScrollTo(0)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { drag.containerOrigin = it.positionInRoot() }
        ) {
            Column(Modifier.fillMaxSize()) {

                TaskTopBar(
                    openCount = state.openCount,
                    overdueCount = state.overdueCount,
                    onBack = onBack,
                )

                DateStrip(
                    days = state.stripDays,
                    today = state.today,
                    selectedDay = state.selectedDay,
                    counts = state.dayCounts,
                    drag = drag,
                    listState = stripScroll,
                    onPick = viewModel::selectDay,
                )

                SelectedDayHeader(
                    day = state.selectedDay,
                    today = state.today,
                    activeCount = state.selectedCount,
                    onJumpToday = {
                        viewModel.selectDay(state.today)
                        val index = state.stripDays.indexOf(state.today)
                        if (index >= 0) {
                            scope.launch { stripScroll.animateScrollToItem((index - 2).coerceAtLeast(0)) }
                        }
                    },
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when {
                        state.loading -> LoadingState()
                        state.isEmpty -> EmptyState()
                        state.isDayEmpty -> DayEmptyState()

                        else -> TaskList(
                            state = state,
                            drag = drag,
                            listScroll = listScroll,
                            onToggle = viewModel::toggleDone,
                            onEdit = { task ->
                                if (!drag.shouldIgnoreClick()) {
                                    editor = EditorTarget(
                                        id = task.id,
                                        title = task.title,
                                        content = task.content,
                                        endDay = task.endDay,
                                    )
                                }
                            },
                            onDropOn = { taskId, day -> viewModel.moveTo(taskId, day) },
                        )
                    }
                }
            }

            // 拖动中的卡片浮层。最后一个绘制，所以盖在所有内容之上。
            // 单独抽成组件是为了把「读取 visual」这个动作限制在它自己的重组范围里。
            DragOverlay(drag = drag)

            FloatingActionButton(
                onClick = { editor = EditorTarget() },
                containerColor = BluePrimary,
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(Spacing.lg),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "新建任务")
            }
        }
    }

    editor?.let { target ->
        TaskEditorSheet(
            target = target,
            onDismiss = { editor = null },
            onSave = { title, content, endDay ->
                if (target.id == null) viewModel.add(title, content, endDay)
                else viewModel.update(target.id, title, content, endDay)
                editor = null
            },
            onDelete = if (target.id == null) {
                null
            } else {
                {
                    pendingDelete = state.tasks.firstOrNull { it.id == target.id }
                    editor = null
                }
            },
        )
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条任务？") },
            text = { Text("「${task.title}」将被删除，此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(task.id)
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
private fun TaskTopBar(openCount: Int, overdueCount: Int, onBack: () -> Unit) {
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
                text = "学习任务",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = when {
                    openCount == 0 -> "暂时没有待办"
                    overdueCount > 0 -> "还剩 $openCount 件，其中 $overdueCount 件已过期"
                    else -> "还剩 $openCount 件待办"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (overdueCount > 0) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------ 日期条

/**
 * 顶部的单行日期条。
 *
 * 两个身份合一：**选日期的入口**（点一下把列表切到那一天）和**拖拽的落点**
 * （把卡片拖过来改它的截止日）。
 *
 * 每个格子把自身边界上报给 [TaskDragState]，命中检测靠这些边界而不是
 * 「序号 × 格宽」算出来的偏移——后者在横向滚动、间距调整后会失准。
 */
@Composable
private fun DateStrip(
    days: List<Long>,
    today: Long,
    selectedDay: Long,
    counts: Map<Long, Int>,
    drag: TaskDragState,
    listState: LazyListState,
    onPick: (Long) -> Unit,
) {
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        contentPadding = PaddingValues(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(days.size, key = { days[it] }) { index ->
            val day = days[index]
            DateChip(
                day = day,
                isToday = day == today,
                isSelected = day == selectedDay,
                count = counts[day] ?: 0,
                drag = drag,
                onPick = onPick,
            )
        }
    }
}

@Composable
private fun DateChip(
    day: Long,
    isToday: Boolean,
    isSelected: Boolean,
    count: Int,
    drag: TaskDragState,
    onPick: (Long) -> Unit,
) {
    // 用 derivedStateOf 挡一层：只有「我这一格是否被悬停」翻转时才重组，
    // 否则拖动过程中每一格都会跟着每帧重组
    val isHovered by remember(day) {
        derivedStateOf {
            val hovered = drag.visual?.hoverDay
            hovered != null && hovered == day
        }
    }

    val container = when {
        isSelected -> BluePrimary
        isHovered -> BlueContainer
        isToday -> BlueContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val content = when {
        isSelected -> Color.White
        isHovered || isToday -> BluePrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val shape = RoundedCornerShape(12.dp)

    // 格子被 LazyRow 回收后要撤销登记，否则会留下一个已不在屏幕上的命中区域
    val dragRef = drag
    DisposableEffect(day) {
        onDispose { dragRef.targets.remove(day) }
    }

    Column (
        modifier = Modifier
            .size(width = 48.dp, height = 62.dp)
            .clip(shape)
            .background(container)
            // 悬停用描边而不是换底色：选中态已经把底色占满了，
            // 拖动时还能一眼看出「松手就落这一格」
            .then(
                if (isHovered) Modifier.border(1.5.dp, BluePrimary, shape) else Modifier
            )
            .onGloballyPositioned {
                dragRef.targets[day] = Rect(
                    offset = it.positionInRoot(),
                    size = it.size.toSize(),
                )
            }
            .clickable { onPick(day) }
            .padding(vertical = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally
        //contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = DayMath.weekLabel(day),
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected) Color.White.copy(alpha = 0.85f) else content,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = DayMath.dayOfMonth(day).toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = content,
            )
        }

        // 还没做完的任务，在这天和它的截止日之间的每一天都会挂角标
        if (count > 0) {
            Box(
                modifier = Modifier
                    //.align(Alignment.BottomCenter)
                    //.padding(top = Spacing.xs, end = Spacing.xs)//.offset(x = Spacing.xs, y = -Spacing.md)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(OrangeAccent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (count > 9) "9+" else count.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        lineHeight = 9.sp,
                    ),
                    color = Color.White,

                )
            }
        }
    }
}

// ------------------------------------------------------------------ 选中日期说明

/**
 * 选中日期的说明行。
 *
 * 存在的意义是把「区间」这套语义讲清楚：用户看到今天的角标、又看到明后天同样有角标，
 * 需要一句话告诉他「同一个任务在这几天都算要做，直到截止日」。
 */
@Composable
private fun SelectedDayHeader(
    day: Long,
    today: Long,
    activeCount: Int,
    onJumpToday: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.xs, bottom = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = DayMath.fullLabel(day, today),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            //Spacer(Modifier.width(Spacing.sm))
            //Text(
            //    text = if (activeCount > 0) "× $activeCount" else "没有要做的事",
            //    style = MaterialTheme.typography.labelSmall,
            //    color = if (activeCount > 0) BluePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            //)
            Spacer(Modifier.weight(1f))
            if (day != today) {
                //TextButton(onClick = onJumpToday,contentPadding = PaddingValues(0.dp),) { Text("回到今天") }
                Text(
                    text = "回到今天",
                    style = MaterialTheme.typography.labelSmall,
                    color = BluePrimary,
                    modifier = Modifier
                        .clickable { onJumpToday() }
                       // .padding(4.dp)  // 可选：加点点击热区
                )
            }else{
                //TextButton(onClick = {},contentPadding = PaddingValues(0.dp),) { Text("") }
            }
        }
        //Text(
        //    text = "截止日在这天或之后、且还没做完的任务，每天都会出现在这里",
        //    style = MaterialTheme.typography.labelSmall,
        //    color = MaterialTheme.colorScheme.onSurfaceVariant,
        //)
    }
}

// ------------------------------------------------------------------ 列表

@Composable
private fun TaskList(
    state: TaskUiState,
    drag: TaskDragState,
    listScroll: ScrollState,
    onToggle: (String) -> Unit,
    onEdit: (TaskItem) -> Unit,
    onDropOn: (String, Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(listScroll)
            .padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        state.groups.forEach { group ->
            GroupHeader(group = group)
            group.tasks.forEach { task ->
                TaskCard(
                    task = task,
                    selectedDay = state.selectedDay,
                    drag = drag,
                    onToggle = { onToggle(task.id) },
                    onEdit = { onEdit(task) },
                    onDropOn = { day -> onDropOn(task.id, day) },
                )
            }
        }
        Spacer(Modifier.height(88.dp))
    }
}

@Composable
private fun GroupHeader(group: TaskGroup) {
    val accentColor = when (group.accent) {
        GroupAccent.TODAY -> BluePrimary
        GroupAccent.OVERDUE -> MaterialTheme.colorScheme.error
        GroupAccent.NORMAL -> MaterialTheme.colorScheme.onBackground
        GroupAccent.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm, bottom = Spacing.xs, start = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.label,
            style = MaterialTheme.typography.titleSmall,
            color = accentColor,
            fontWeight = if (group.accent == GroupAccent.TODAY) FontWeight.Bold
            else FontWeight.Medium,
        )
        group.hint?.let { hint ->
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = if (group.accent == GroupAccent.OVERDUE) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "× ${group.tasks.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TaskCard(
    task: TaskItem,
    selectedDay: Long,
    drag: TaskDragState,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDropOn: (Long) -> Unit,
) {
    var cardTopLeft by remember { mutableStateOf(Offset.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }

    // 手势协程只在 key 变化时重启，闭包里必须读到最新值
    val currentTask by rememberUpdatedState(task)
    val currentOnDrop by rememberUpdatedState(onDropOn)
    val currentOnEdit by rememberUpdatedState(onEdit)

    // 同上：用 derivedStateOf 把「是不是我在被拖」这个布尔值隔离出来
    val isDragging by remember(task.id) {
        derivedStateOf { drag.isDragging(task.id) }
    }

    // 拖动时原位不是换成固定高度的占位块，而是保留卡片本身、只把它的绘制隐藏。
    // 用占位块的话高度得和真实卡片一致，卡片内容行数一变就会对不上，
    // 拖动过程中列表会抽一下；保留原布局则完全不动。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isDragging) {
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                } else {
                    Modifier
                }
            )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = if (isDragging) 0f else 1f }
                .onGloballyPositioned {
                    cardTopLeft = it.positionInRoot()
                    cardSize = it.size
                }
                .pointerInput(task.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { local ->
                            drag.begin(currentTask, cardTopLeft, local, cardSize)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            drag.update(change.position, amount, cardTopLeft)
                        },
                        onDragEnd = { drag.finish()?.let { day -> currentOnDrop(day) } },
                        onDragCancel = { drag.cancel() },
                    )
                }
                .clickable { currentOnEdit() },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = Spacing.md, top = Spacing.sm, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = task.done, onCheckedChange = { onToggle() })
                Column(Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (task.done) TextDecoration.LineThrough else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (task.content.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = task.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textDecoration = if (task.done) TextDecoration.LineThrough else null,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // 截止日等于选中日时不必重复——分组和上面的日期已经说清楚了；
                    // 其余情况（跨天的任务、已完成、已过期）必须标出来，
                    // 否则用户不知道「它到底几号要交」。
                    val overdue = task.isOverdue(selectedDay)
                    val endLabel = task.endDay?.takeIf { it != selectedDay }
                    if (endLabel != null) {
                        Spacer(Modifier.height(Spacing.xs))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (overdue) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                                    else BlueContainer
                                )
                                .padding(horizontal = Spacing.sm, vertical = 1.dp)
                        ) {
                            Text(
                                text = "${if (overdue) "已过期 · " else "截止 "}${DayMath.shortLabel(endLabel)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (overdue) MaterialTheme.colorScheme.error else BluePrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 拖动浮层

/**
 * 跟着手指走的卡片。
 *
 * 抽成独立组件不只是为了整洁：读取 [TaskDragState.visual] 会订阅该状态，
 * 放在页面级读会让整页每帧重组，收在这里就只重组自己。
 */
@Composable
private fun DragOverlay(drag: TaskDragState) {
    val visual = drag.visual ?: return
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (visual.startLeft + visual.accumX).roundToInt(),
                    (visual.startTop + visual.accumY).roundToInt(),
                )
            }
            .width(with(density) { visual.widthPx.toDp() })
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            border = BorderStroke(1.5.dp, BluePrimary),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = BluePrimary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Column {
                    Text(
                        text = visual.task.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (visual.hoverDay != null) "松手把截止日改到这天" else "拖到日期上",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (visual.hoverDay != null) BluePrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 空态

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = BluePrimary)
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.EventBusy,
            contentDescription = null,
            tint = BluePrimary.copy(alpha = 0.5f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = "还没有任务",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "点右下角新建。截止日期可以不填，之后长按卡片拖到上面某一天就行。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 一条任务都没有匹配到选中的这一天 */
@Composable
private fun DayEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "这一天没有要做的事",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "这里只会出现截止日在这天或之后、且还没做完的任务。\n换一天看看，或者新建一条。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ------------------------------------------------------------------ 编辑弹层

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskEditorSheet(
    target: EditorTarget,
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, endDay: Long?) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by remember(target.id) { mutableStateOf(target.title) }
    var content by remember(target.id) { mutableStateOf(target.content) }
    var endDay by remember(target.id) { mutableStateOf(target.endDay) }
    var showPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
                .imePadding(),
        ) {
            Text(
                text = if (target.id == null) "新建任务" else "编辑任务",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(Spacing.lg))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("标题") },
                placeholder = { Text("例如：听写第三单元") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = BluePrimary),
            )

            Spacer(Modifier.height(Spacing.md))

            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
                label = { Text("内容（可留空）") },
                placeholder = { Text("要做什么、有什么要求") },
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = BluePrimary),
            )

            Spacer(Modifier.height(Spacing.md))

            // 日期只是可选入口，主路径仍然是拖
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { showPicker = true }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "截止日期（可不填）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = endDay?.let { "到 ${DayMath.monthDayLabel(it)} 为止" } ?: "未安排",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (endDay == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else BluePrimary,
                    )
                }
                if (endDay != null) {
                    TextButton(onClick = { endDay = null }) { Text("清除") }
                }
            }
            Text(
                text = "从创建这天起，每天都会出现在列表里，直到截止日",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.lg, top = Spacing.xs),
            )

            Spacer(Modifier.height(Spacing.xl))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "删除任务",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.width(Spacing.sm))
                }
                Button(
                    onClick = { onSave(title, content, endDay) },
                    enabled = title.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.TaskAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(if (target.id == null) "添加" else "保存")
                }
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (endDay ?: DayMath.today()) * MILLIS_PER_DAY,
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    // DatePicker 返回的就是 UTC 零点毫秒，除以一天即得 epoch day，
                    // 与 DayMath 的存储表示天然对齐
                    pickerState.selectedDateMillis?.let { endDay = it / MILLIS_PER_DAY }
                    showPicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private const val MILLIS_PER_DAY = 86_400_000L
