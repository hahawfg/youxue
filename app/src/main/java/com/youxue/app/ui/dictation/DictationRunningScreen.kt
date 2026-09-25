package com.youxue.app.ui.dictation

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youxue.app.YouxueApplication
import com.youxue.app.dictation.DictationPhase
import com.youxue.app.dictation.DictationUiState
import com.youxue.app.dictation.DictationViewModel
import com.youxue.app.tts.SpeechLanguage
import com.youxue.app.tts.TtsState
import com.youxue.app.ui.theme.BlueContainer
import com.youxue.app.ui.theme.BluePrimary
import com.youxue.app.ui.theme.OrangeAccent
import com.youxue.app.ui.theme.Spacing
import com.youxue.app.util.formatDuration
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * 听写进行页。
 *
 * 设计原则：学生低头写字，抬头看屏幕的时间很短，
 * 所以关键信息（第几个、还剩多久、下一词在哪个位置）都要一眼可读。
 * 因此用大字号 + 环形倒计时，而不是小字列表。
 */
@Composable
fun DictationRunningScreen(
    onFinished: () -> Unit,
    onExit: () -> Unit,
    viewModel: DictationViewModel = viewModel(
        factory = DictationViewModel.factory(
            YouxueApplication.get().ttsEngine,
            YouxueApplication.get().settingsRepository,
        )
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val ttsState by viewModel.ttsState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }

    // 听写期间保持屏幕常亮：学生可能长时间低头写字不碰屏幕
    KeepScreenOn()

    // 首次进入即开始听写（词语来自录入页保存的草稿）
    LaunchedEffect(Unit) {
        viewModel.startFromDraftIfNeeded()
    }

    // 结束 → 跳转结果页
    LaunchedEffect(state.phase) {
        if (state.phase is DictationPhase.Finished) {
            onFinished()
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
            // ---- 顶部：退出 + 进度 + 计时 ----
            RunningTopBar(
                state = state,
                onExit = { showExitDialog = true },
            )

            // ---- TTS 异常提示 ----
            // 完全不可用和「只缺一种语言」都要提示，但后者不该拦住听写流程
            val ttsWarning = when (val t = ttsState) {
                is TtsState.Unavailable -> t.reason
                is TtsState.PartiallyAvailable -> t.reason
                else -> null
            }
            AnimatedVisibility(
                visible = ttsWarning != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                TtsWarningBanner(reason = ttsWarning.orEmpty())
            }

            // ---- 主体 ----
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isPreparing) {
                    PreparingContent(state)
                } else {
                    RunningContent(state = state, onReplay = viewModel::replayCurrent)
                }
            }

            // ---- 底部操作区 ----
            ControlBar(
                state = state,
                onNext = viewModel::nextWord,
                onPrevious = viewModel::previousWord,
                onPause = viewModel::togglePause,
                onReplay = viewModel::replayCurrent,
                onComplete = viewModel::complete,
            )
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("结束本次听写？") },
            text = { Text("结束后会回到首页，当前进度不会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    viewModel.abort()
                    onExit()
                }) {
                    Text("结束听写", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitDialog = false }) {
                    Text("继续听写")
                }
            },
        )
    }
}

/** 让 Activity 在本页存活期间保持屏幕常亮 */
@Composable
private fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
private fun RunningTopBar(state: DictationUiState, onExit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "结束听写",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = "第 ${state.currentIndex + 1} / ${state.total} 个",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "已用 ${formatDuration(state.elapsedMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "剩余",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${state.total - state.currentIndex - 1} 个词",
                    style = MaterialTheme.typography.titleMedium,
                    color = OrangeAccent,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        LinearProgressIndicator(
            progress = { state.progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = BluePrimary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
    }
}

@Composable
private fun TtsWarningBanner(reason: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(Spacing.md),
        )
    }
}

/** 预备倒计时内容 */
@Composable
private fun PreparingContent(state: DictationUiState) {
    val phase = state.phase as? DictationPhase.Preparing ?: return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "准备开始",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "拿出笔和本子，共 ${state.total} 个词语",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xxl))
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(140.dp),
                color = BluePrimary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 8.dp,
            )
            Text(
                text = phase.secondsLeft.toString(),
                style = MaterialTheme.typography.displayLarge,
                color = BluePrimary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 运行中的主内容：环形倒计时 + 当前词序号 + 声波 */
@Composable
private fun RunningContent(
    state: DictationUiState,
    onReplay: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(horizontal = Spacing.xl),
    ) {
        Text(
            text = if (state.isPaused) "已暂停" else "正在听写",
            style = MaterialTheme.typography.labelLarge,
            color = if (state.isPaused) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.lg))

        CountdownRing(
            remainingFraction = state.remainingFraction,
            remainingMillis = state.remainingMillis,
            isSpeaking = state.isSpeaking,
            isPaused = state.isPaused,
        )

        Spacer(Modifier.height(Spacing.xl))

        // 提示学生当前是第几个字数的词——只给字数，不给内容，避免"看答案"
        val currentWord = state.currentWord
        val charCount = currentWord?.text?.count { !it.isWhitespace() } ?: 0
        val language = currentWord?.let { SpeechLanguage.detectFor(it.text) }
        Text(
            text = buildString {
                append("本词 $charCount ")
                // 英文词论「个字母」而不是「个字」，说法要对上
                append(if (language == SpeechLanguage.ENGLISH) "个字母" else "个字")
                if (language != null) {
                    append("（${language.displayName}）")
                }
                append("，等待 ${formatSecondsShort(state.currentIntervalMillis)} 秒")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Spacing.lg))

        FilledTonalButton(
            onClick = onReplay,
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.VolumeUp,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text("再听一遍")
        }
    }
}

private fun formatSecondsShort(millis: Long): String {
    val seconds = millis / 1000.0
    return if (seconds == seconds.toInt().toDouble()) seconds.toInt().toString()
    else "%.1f".format(seconds)
}

/**
 * 环形倒计时。
 *
 * 用进度环而不是纯数字，是因为学生抬头扫一眼就能凭"环还剩多少"判断时间，
 * 不用去读数字。环内叠加大号秒数作为精确参考。
 */
@Composable
private fun CountdownRing(
    remainingFraction: Float,
    remainingMillis: Long,
    isSpeaking: Boolean,
    isPaused: Boolean,
) {
    val ringColor = when {
        isPaused -> OrangeAccent
        isSpeaking -> BluePrimary
        else -> BluePrimary
    }

    // 朗读时的呼吸动画，给一个"正在说话"的视觉反馈
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseValue",
    )
    val scale = if (isSpeaking) pulse else 1f

    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(230.dp)) {
            val strokeWidth = 14.dp.toPx()
            val inset = strokeWidth / 2
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(inset, inset)

            // 轨道
            drawArc(
                color = Color(0xFFE6E9F2),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            // 进度
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * remainingFraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val secondsLeft = ceil(remainingMillis / 1000.0).toInt().coerceAtLeast(0)
            Text(
                text = if (isSpeaking) "朗读中" else secondsLeft.toString(),
                style = if (isSpeaking) {
                    MaterialTheme.typography.headlineLarge
                } else {
                    MaterialTheme.typography.displayLarge
                },
                color = ringColor,
                fontWeight = FontWeight.Bold,
            )
            if (!isSpeaking) {
                Text(
                    text = "秒",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 朗读时的声波弧线
        if (isSpeaking) {
            Canvas(modifier = Modifier.size(230.dp)) {
                val cx = size.width / 2
                val cy = size.height / 2
                val baseRadius = size.width / 2 * scale
                for (i in 0 until 3) {
                    val r = baseRadius + i * 10.dp.toPx()
                    for (angle in listOf(210f, 240f, 270f, 300f, 330f)) {
                        val rad = Math.toRadians(angle.toDouble())
                        val x = cx + r * cos(rad).toFloat()
                        val y = cy + r * sin(rad).toFloat()
                        drawCircle(
                            color = BluePrimary.copy(alpha = 0.35f - i * 0.1f),
                            radius = 3.dp.toPx(),
                            center = Offset(x, y),
                        )
                    }
                }
            }
        }
    }
}

/** 底部控制区 */
@Composable
private fun ControlBar(
    state: DictationUiState,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onPause: () -> Unit,
    onReplay: () -> Unit,
    onComplete: () -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 上一个（回退重听）
                OutlinedButton(
                    onClick = onPrevious,
                    enabled = state.currentIndex > 0,
                    modifier = Modifier.height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = Spacing.md),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("上一个")
                }

                // 下一个（主要操作）
                Button(
                    onClick = onNext,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                ) {
                    Text("下一个", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(Spacing.xs))
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // 暂停 / 继续
                IconButton(
                    onClick = onPause,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(
                        imageVector = if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (state.isPaused) "继续" else "暂停",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 重听当前词
                IconButton(
                    onClick = onReplay,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(BlueContainer),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "重听当前词",
                        tint = BluePrimary,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            // 完成听写
            TextButton(
                onClick = onComplete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (state.currentIndex >= state.total - 1) {
                        "全部读完，完成听写"
                    } else {
                        "提前完成听写（还有 ${state.total - state.currentIndex - 1} 个未读）"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
