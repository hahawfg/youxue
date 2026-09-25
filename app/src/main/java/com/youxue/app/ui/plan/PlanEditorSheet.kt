package com.youxue.app.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.youxue.app.plan.PlanSchedule
import com.youxue.app.ui.theme.BluePrimary
import com.youxue.app.ui.theme.Spacing
import com.youxue.app.util.DayMath

/** 短标签按中文习惯从周一开始排，索引是 dayOfWeek 的值 */
private val WEEK_ORDER = listOf(1, 2, 3, 4, 5, 6, 0)
private val WORKDAYS: Set<Int> = setOf(1, 2, 3, 4, 5)

// 「每天」用的 ALL_DAYS 直接复用 PlanScreen.kt 里那份（同包 internal）：
// 顶层声明同包同名会冲突，与其在文件间同步两个字面量，不如只留一处

private enum class PickTarget { START, END }

/**
 * 计划的新建 / 编辑弹层。
 *
 * 用底部弹层而不是独立页面：字段只有四组，一屏足够，
 * 开一个路由反而多一次进出动画和一份要维护的导航状态。
 *
 * 「重复日」是这里唯一需要动脑的地方，所以给了两个快捷入口
 * （每天 / 工作日）——常见需求一键到位，其余的逐个点。
 *
 * 返回值里带上 [onSave] 的 `enabled`，是因为「启用」开关也在这个弹层里；
 * 拆成保存后再单独调一次会多一次整体落盘，中间还有一帧
 * 「规则已改但还没停用」的中间态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanEditorSheet(
    target: EditorTarget,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        weekdays: Set<Int>,
        startDay: Long,
        endDay: Long?,
        enabled: Boolean,
    ) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // key 用 target.id：从「编辑 A」切到「编辑 B」时要重新取初值，
    // 否则会带着上一条的内容渲染一帧
    var title by remember(target.id) { mutableStateOf(target.title) }
    var weekdays by remember(target.id) { mutableStateOf(target.weekdays) }
    var startDay by remember(target.id) { mutableStateOf(target.startDay) }
    var endDay by remember(target.id) { mutableStateOf(target.endDay) }
    var enabled by remember(target.id) { mutableStateOf(target.enabled) }
    var picking by remember { mutableStateOf<PickTarget?>(null) }

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
                text = if (target.id == null) "新建计划" else "编辑计划",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(Spacing.lg))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("计划名称") },
                placeholder = { Text("例如：每周一三五听写") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = BluePrimary),
            )

            Spacer(Modifier.height(Spacing.lg))

            // ---- 重复日 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "每周哪几天",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { weekdays = ALL_DAYS }) { Text("每天") }
                TextButton(onClick = { weekdays = WORKDAYS }) { Text("工作日") }
            }

            Spacer(Modifier.height(Spacing.xs))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WEEK_ORDER.forEach { dow ->
                    DayChip(
                        label = PlanSchedule.WEEK_SHORT[dow],
                        selected = dow in weekdays,
                        // 取消到一天不剩的计划哪天都不会触发，等于白建一条，
                        // 所以这里直接挡住。想「全不选」的人其实是想停用，
                        // 那是下面那个开关的事
                        onClick = {
                            val next = if (dow in weekdays) weekdays - dow else weekdays + dow
                            if (next.isNotEmpty()) weekdays = next
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            Text(
                text = "现在的规则：${PlanSchedule.repeatLabel(weekdays)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.lg))

            // ---- 起止日 ----
            DateRow(
                label = "开始日期",
                value = DayMath.monthDayLabel(startDay),
                onClick = { picking = PickTarget.START },
            )

            Spacer(Modifier.height(Spacing.sm))

            DateRow(
                label = "结束日期（可不填）",
                value = endDay?.let { DayMath.monthDayLabel(it) } ?: "长期有效",
                muted = endDay == null,
                onClick = { picking = PickTarget.END },
                onClear = if (endDay == null) null else ({ endDay = null }),
            )

            Spacer(Modifier.height(Spacing.xs))

            Text(
                text = "到这一天为止就不再出现；留空表示一直做下去",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.lg),
            )

            // ---- 启用开关：新建时没有「停用」的语义，只在编辑时露出 ----
            if (target.id != null) {
                Spacer(Modifier.height(Spacing.lg))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "启用",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "停用后不再出现在「今天」，打卡记录保留",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }

            Spacer(Modifier.height(Spacing.xl))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "删除计划",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.width(Spacing.sm))
                }
                Button(
                    onClick = { onSave(title, weekdays, startDay, endDay, enabled) },
                    enabled = title.isNotBlank() && weekdays.isNotEmpty(),
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

    picking?.let { which ->
        val initial = when (which) {
            PickTarget.START -> startDay
            PickTarget.END -> endDay ?: startDay
        }
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initial * DayMath.MILLIS_PER_DAY,
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    // DatePicker 返回的就是 UTC 零点毫秒，除以一天即得 epoch day，
                    // 与 DayMath 的存储表示天然对齐
                    pickerState.selectedDateMillis?.let { millis ->
                        val day = millis / DayMath.MILLIS_PER_DAY
                        when (which) {
                            PickTarget.START -> {
                                startDay = day
                                // 开始日推到结束日之后会让区间变空，把结束日一起往后带
                                endDay?.let { if (it < day) endDay = day }
                            }
                            PickTarget.END -> endDay = day
                        }
                    }
                    picking = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * 圆形星期按钮。
 *
 * 自己画而不是用 `FilterChip`：一是七个 chip 要等宽排一行，
 * chip 自带的内边距会让文字挤在一起；二是圆形比圆角矩形更贴合
 * 「日一二三四五六」这种单字标签。
 */
@Composable
private fun DayChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(
                if (selected) BluePrimary else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DateRow(
    label: String,
    value: String,
    muted: Boolean = false,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else BluePrimary,
            )
        }
        if (onClear != null) {
            TextButton(onClick = onClear) { Text("清除") }
        }
    }
}
