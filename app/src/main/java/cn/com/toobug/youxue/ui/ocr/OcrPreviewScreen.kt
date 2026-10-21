package cn.com.toobug.youxue.ui.ocr

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.com.toobug.youxue.ui.theme.BlueContainer
import cn.com.toobug.youxue.ui.theme.BluePrimary
import cn.com.toobug.youxue.ui.theme.OrangeAccent
import cn.com.toobug.youxue.ui.theme.Spacing

/**
 * 识别结果确认页。
 *
 * 这是整个拍照识字流程里的**第二道筛子**：裁剪解决「整块不该识字的区域」，
 * 这里解决「识别出来了但不该听写的行」。两道筛子管的事不一样，
 * 所以不是二选一。
 *
 * 为什么必须有这一页：OCR 一定会有错字，拍照的角度、光线、书页弯折
 * 都会影响结果。识别完直接塞进文本框的话，用户还得自己逐行找哪里错了；
 * 而在这里一眼扫过去、划掉不要的、改掉错的，比事后返工省事得多。
 */
@Composable
fun OcrPreviewScreen(
    onBack: () -> Unit,
    onFilled: () -> Unit,
    viewModel: OcrPreviewViewModel = viewModel(factory = OcrPreviewViewModel.factory()),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.runOnce() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            PreviewTopBar(
                onBack = onBack,
                selectedCount = state.selectedCount,
                totalCount = state.rows.size,
                onToggleAll = { viewModel.selectAll(state.selectedCount < state.rows.size) },
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    state.loading -> LoadingBody()
                    state.errorMessage != null -> ErrorBody(
                        message = state.errorMessage!!,
                        onRetry = viewModel::retry,
                    )
                    state.rows.isEmpty() -> EmptyBody()
                    else -> RowList(
                        rows = state.rows,
                        onToggle = viewModel::toggle,
                        onEdit = viewModel::edit,
                    )
                }
            }

            if (!state.loading && state.errorMessage == null && state.rows.isNotEmpty()) {
                ConfirmBar(
                    selectedCount = state.selectedCount,
                    enabled = state.canFill,
                    onConfirm = { viewModel.confirm { onFilled() } },
                )
            }
        }
    }
}

@Composable
private fun PreviewTopBar(
    onBack: () -> Unit,
    selectedCount: Int,
    totalCount: Int,
    onToggleAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
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
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "确认要听写的词语",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "勾选的会填进录入框，点文字可以直接改",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (totalCount > 0) {
                TextButton(onClick = onToggleAll) {
                    Text(
                        text = if (selectedCount < totalCount) "全选" else "全不选",
                        color = BluePrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowList(
    rows: List<OcrRowUi>,
    onToggle: (Int) -> Unit,
    onEdit: (Int, String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.lg,
            end = Spacing.lg,
            top = Spacing.sm,
            bottom = Spacing.lg,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item {
            Text(
                text = "标记为橙色的是「看着不太像词语」的行，默认没勾上——" +
                    "如果你确实要它，勾一下就行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.xs),
            )
        }

        itemsIndexed(rows, key = { index, _ -> index }) { index, row ->
            OcrRow(
                row = row,
                onToggle = { onToggle(index) },
                onEdit = { onEdit(index, it) },
            )
        }
    }
}

@Composable
private fun OcrRow(
    row: OcrRowUi,
    onToggle: () -> Unit,
    onEdit: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (row.selected) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (row.selected) 1.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = Spacing.md, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = row.selected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = BluePrimary),
            )

            BasicTextField(
                value = row.text,
                onValueChange = onEdit,
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = Spacing.sm),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (row.selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
                cursorBrush = SolidColor(BluePrimary),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (row.text.isEmpty()) {
                            Text(
                                text = "（空）",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                        inner()
                    }
                },
            )

            row.suspect?.let { reason ->
                Spacer(Modifier.width(Spacing.sm))
                SuspectChip(label = suspectLabel(reason))
            }
        }
    }
}

@Composable
private fun SuspectChip(label: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = OrangeAccent.copy(alpha = 0.15f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OrangeAccent,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
        )
    }
}

@Composable
private fun ConfirmBar(
    selectedCount: Int,
    enabled: Boolean,
    onConfirm: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = Spacing.md,
                    bottom = Spacing.md,
                ),
        ) {
            Button(
                onClick = onConfirm,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BluePrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = if (enabled) "填入录入框（$selectedCount 个）" else "至少选一个词语",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun LoadingBody() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = BluePrimary)
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = "正在识别…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "识别在本机完成，不会上传图片",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun EmptyBody() {
    BodyMessage(
        icon = Icons.Filled.SearchOff,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        title = "没识别到文字",
        hint = "可能框选范围太窄，或者照片太糊、光线太暗。回去重新框一块试试。",
    )
}

@Composable
private fun ErrorBody(message: String, onRetry: () -> Unit) {
    BodyMessage(
        icon = Icons.Filled.WarningAmber,
        tint = OrangeAccent,
        title = "识别没成功",
        hint = message,
        action = {
            TextButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = BluePrimary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text("重试", color = BluePrimary)
            }
        },
    )
}

@Composable
private fun BodyMessage(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    hint: String,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(BlueContainer, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(Spacing.md))
            action()
        }
    }
}
