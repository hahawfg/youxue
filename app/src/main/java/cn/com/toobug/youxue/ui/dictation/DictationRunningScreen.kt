package cn.com.toobug.youxue.ui.dictation

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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.com.toobug.youxue.YouxueApplication
import cn.com.toobug.youxue.data.DictationSettings
import cn.com.toobug.youxue.dictation.DictationPhase
import cn.com.toobug.youxue.dictation.DictationUiState
import cn.com.toobug.youxue.dictation.DictationViewModel
import cn.com.toobug.youxue.dictation.WordStatus
import cn.com.toobug.youxue.tts.SpeechLanguage
import cn.com.toobug.youxue.tts.TtsState
import cn.com.toobug.youxue.ui.theme.BlueContainer
import cn.com.toobug.youxue.ui.theme.BluePrimary
import cn.com.toobug.youxue.ui.theme.OrangeAccent
import cn.com.toobug.youxue.ui.theme.OrangeContainer
import cn.com.toobug.youxue.ui.theme.Spacing
import cn.com.toobug.youxue.util.formatDuration
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** 跳转弹层里每行放几个序号 */
private const val JUMP_COLUMNS = 6

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
    val perCharSeconds by viewModel.perCharSeconds.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var showExitDialog by remember { mutableStateOf(false) }

    // 听写中的两个「临时工具」面板
    var showPaceSheet by remember { mutableStateOf(false) }
    var showJumpSheet by remember { mutableStateOf(false) }

    // 是否把当前词语露出来。默认不显示——这是给「实在听不清、想确认一下」的兜底，
    // 而不是常驻的提示，常驻就等于把答案摆在学生眼前了。
    // 所以：默认关、点了才开、**换一个词自动收回**（否则点一次之后整套题都在裸奔）
    var revealWord by remember { mutableStateOf(false) }
    var revealForIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(state.currentIndex, state.phase) {
        if (state.currentIndex != revealForIndex || state.isPreparing) {
            revealForIndex = state.currentIndex
            revealWord = false
        }
    }

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
                perCharSeconds = perCharSeconds,
                onOpenPace = { showPaceSheet = true },
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
                    RunningContent(
                        state = state,
                        revealWord = revealWord,
                        onToggleReveal = { revealWord = !revealWord },
                        onReplay = viewModel::replayCurrent,
                    )
                }
            }

            // ---- 底部操作区 ----
            ControlBar(
                state = state,
                onNext = viewModel::nextWord,
                onPrevious = viewModel::previousWord,
                onPause = viewModel::togglePause,
                onReplay = viewModel::replayCurrent,
                onOpenPace = { showPaceSheet = true },
                onOpenJump = { showJumpSheet = true },
                onComplete = viewModel::complete,
            )
        }
    }

    if (showPaceSheet) {
        PaceSheet(
            current = perCharSeconds,
            onDismiss = { showPaceSheet = false },
            onCommit = viewModel::updatePerCharSeconds,
        )
    }

    if (showJumpSheet) {
        JumpSheet(
            state = state,
            onDismiss = { showJumpSheet = false },
            onJump = viewModel::jumpTo,
        )
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

/**
 * 每字时长调节弹层。
 *
 * 这是听写的**节奏**本身：每个字给几秒书写时间，词越长等待越久。
 * 与「朗读语速」不是一回事——那个管 TTS 念得多快，在录入页调。
 *
 * 与录入页共用同一份 `perCharSeconds`，改完**存成默认**，下次进来沿用。
 * 拖动时只更新本地预览、松手才提交，避免每一帧都写一次 DataStore；
 * 但**当前词的重新计时是即时的**（在 ViewModel 里做），所以拖的时候
 * 背后那个倒计时环会跟着变，能直接看出自己调的是什么。
 */
@Composable
private fun PaceSheet(
    current: Float,
    onDismiss: () -> Unit,
    onCommit: (Float) -> Unit,
) {
    var preview by remember(current) { mutableFloatStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("听写速度（每字时长）") },
        text = {
            Column {
                Text(
                    text = String.format("%.1f 秒/字", preview),
                    style = MaterialTheme.typography.displaySmall,
                    color = BluePrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.xs))
                // 抽象参数给个具体例子，用户才知道自己调的是什么
                Text(
                    text = "「苹果」等 ${formatSecondsShort((2 * preview * 1000).toLong())} 秒，"
                        + "「中华人民共和国」等 ${formatSecondsShort((7 * preview * 1000).toLong())} 秒",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.md))
                Slider(
                    value = preview,
                    onValueChange = { preview = it },
                    onValueChangeFinished = { onCommit(preview) },
                    valueRange = DictationSettings.PER_CHAR_MIN..DictationSettings.PER_CHAR_MAX,
                    steps = DictationSettings.PER_CHAR_STEPS,
                    colors = SliderDefaults.colors(
                        thumbColor = BluePrimary,
                        activeTrackColor = BluePrimary,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${DictationSettings.PER_CHAR_MIN} 秒/字（快）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${DictationSettings.PER_CHAR_MAX} 秒/字（慢）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = "改完立刻生效：正在等的这个词会重新计时，后面的词也都按新时长来。"
                        + "该值会记住，下次听写继续用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "注：单字词最少 ${formatSecondsShort((DictationSettings.DEFAULT.minSeconds * 1000).toLong())} 秒、"
                        + "单个词最多 ${formatSecondsShort((DictationSettings.DEFAULT.maxSeconds * 1000).toLong())} 秒封顶，"
                        + "可在录入页的「间隔设置」里改。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

/**
 * 跳转弹层：选任意一个词跳过去，从那里继续听。
 *
 * 用宫格列序号而不是一个数字输入框：听写中用户心里想的是
 * 「回到第 3 个」，序号摆在眼前可以直接指，不用调键盘。
 * 每格同时标出该词是否已经写过（勾/点），让用户知道跳过去是回头还是往前。
 */
@Composable
private fun JumpSheet(
    state: DictationUiState,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳到指定位置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "选择一个词，从这里继续往下听。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))

                state.words.chunked(JUMP_COLUMNS).forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        row.forEach { word ->
                            JumpCell(
                                number = word.index + 1,
                                isCurrent = word.index == state.currentIndex,
                                isDone = word.status == WordStatus.DONE,
                                onClick = {
                                    onJump(word.index)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // 末行补齐，避免 weight 把剩下的格子撑宽
                        repeat(JUMP_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 跳转弹层里的一格：序号 + 状态 */
@Composable
private fun JumpCell(
    number: Int,
    isCurrent: Boolean,
    isDone: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = when {
        isCurrent -> BluePrimary
        isDone -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val content = when {
        isCurrent -> Color.White
        isDone -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(12.dp),
        color = container,
        border = if (isCurrent || isDone) null else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = content,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun RunningTopBar(
    state: DictationUiState,
    perCharSeconds: Float,
    onOpenPace: () -> Unit,
    onExit: () -> Unit,
) {
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

            // 当前每字时长：一眼看得见一个词大概等多久，点开可调。
            // 不做成纯图标是因为「1.5 秒/字」这个数本身就是用户要确认的信息，
            // 藏进弹层里就成了「点开才知道」，与「一眼可读」的初衷相反
            Surface(
                onClick = onOpenPace,
                shape = RoundedCornerShape(10.dp),
                color = BlueContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Timer,
                        contentDescription = null,
                        tint = BluePrimary,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = String.format("%.1f 秒/字", perCharSeconds),
                        style = MaterialTheme.typography.labelMedium,
                        color = BluePrimary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.width(Spacing.sm))

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
    revealWord: Boolean,
    onToggleReveal: () -> Unit,
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

        // 整个环形区域都可以点：这是页面上最大最好按的目标，
        // 学生低头写字时随手一戳就能看到词，不用瞄准某个小按钮
        Box(
            modifier = Modifier.clickable(onClick = onToggleReveal),
            contentAlignment = Alignment.Center,
        ) {
            CountdownRing(
                remainingFraction = state.remainingFraction,
                remainingMillis = state.remainingMillis,
                isSpeaking = state.isSpeaking,
                isPaused = state.isPaused,
            )
        }

        Spacer(Modifier.height(Spacing.xl))

        WordHint(
            state = state,
            revealed = revealWord,
            onToggleReveal = onToggleReveal,
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

/**
 * 当前词语提示行。
 *
 * 默认只给字数、不给内容——这是刻意保留的设计：把词摆在屏幕上，
 * 听写就变成了抄写。但确实存在「实在没听清、想确认一下」的场景，
 * 所以给一个**点击才显示**的出口：默认隐藏、点开展示、换词自动收回。
 *
 * 展开态用醒目的底色 + 大字号，与「本词 2 个字」这种弱提示拉开距离：
 * 用户必须一眼看出自己正在看答案，而不是误以为这是正常状态。
 */
@Composable
private fun WordHint(
    state: DictationUiState,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
) {
    val currentWord = state.currentWord
    val charCount = currentWord?.text?.count { !it.isWhitespace() } ?: 0
    val language = currentWord?.let { SpeechLanguage.detectFor(it.text) }

    if (revealed && currentWord != null) {
        Surface(
            onClick = onToggleReveal,
            shape = RoundedCornerShape(14.dp),
            color = OrangeContainer,
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = Spacing.xl,
                    vertical = Spacing.md,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = currentWord.text,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "点一下收起",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // 隐藏态：只说几个字、什么语言、还有多久
    val hint = buildString {
        append("本词 $charCount ")
        // 英文词论「个字母」而不是「个字」，说法要对上
        append(if (language == SpeechLanguage.ENGLISH) "个字母" else "个字")
        if (language != null) {
            append("（${language.displayName}）")
        }
        append("，等待 ${formatSecondsShort(state.currentIntervalMillis)} 秒")
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggleReveal)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(Spacing.xs))
        Icon(
            imageVector = Icons.Filled.Visibility,
            contentDescription = "显示当前词语",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
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
    onOpenPace: () -> Unit,
    onOpenJump: () -> Unit,
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

            // 第二排：语速 + 跳到指定位置 + 提前完成。
            // 这两项都是「偶尔才用」的工具，放在第二排的小按钮里，
            // 不跟主操作抢第一排的位置——听写中最高频的动作永远是「下一个」
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChipButton(
                    icon = Icons.Filled.Timer,
                    label = "听写速度",
                    onClick = onOpenPace,
                )

                AssistChipButton(
                    icon = Icons.Filled.PlaylistPlay,
                    label = "跳到第几个",
                    onClick = onOpenJump,
                )

                Spacer(Modifier.weight(1f))

                TextButton(onClick = onComplete) {
                    Text(
                        text = if (state.currentIndex >= state.total - 1) {
                            "全部读完，完成听写"
                        } else {
                            "提前完成"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 第二排的小工具按钮：图标 + 文字，样式统一 */
@Composable
private fun AssistChipButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
