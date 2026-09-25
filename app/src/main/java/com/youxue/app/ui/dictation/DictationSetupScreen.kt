package com.youxue.app.ui.dictation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youxue.app.YouxueApplication
import com.youxue.app.data.DictationSettings
import com.youxue.app.ocr.OcrSessionStore
import com.youxue.app.tts.SpeechLanguage
import com.youxue.app.ui.theme.BlueContainer
import com.youxue.app.ui.theme.BluePrimary
import com.youxue.app.ui.theme.OrangeAccent
import com.youxue.app.ui.theme.Spacing
import com.youxue.app.util.ImageSource
import com.youxue.app.util.formatDurationChinese
import com.youxue.app.util.formatSeconds
import java.io.File
import kotlin.math.roundToInt

/**
 * 词语录入页。
 *
 * 布局上把「输入区」放在最上方并给足高度，因为这是用户唯一必须做的事；
 * 参数设置折叠在下方卡片里，默认收起常用项、展开高级项，避免一屏塞满滑块。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictationSetupScreen(
    onBack: () -> Unit,
    onStarted: () -> Unit,
    /** 拿到待识别的图片后交给上层导航去裁剪页 */
    onOcrImageReady: (ImageSource) -> Unit,
    viewModel: DictationSetupViewModel = viewModel(
        factory = DictationSetupViewModel.factory(
            YouxueApplication.get().settingsRepository,
            YouxueApplication.get().ttsEngine,
        )
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // 同一个 SnackbarHost 要同时承载「报错」和「识字成功」两种语气，
    // 用这个标记决定底色。比按文案字符串去猜可靠
    var snackbarIsError by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // --- 拍照 / 选图 ---
    // 相机拍出来的文件路径要跨回调传递，用 remember 存着；
    // 用 rememberSaveable 会更稳，但相机回调几乎不会跨进程重建，
    // 而且存的是临时文件路径，重建后也没意义。
    var pendingShotPath by remember { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        val path = pendingShotPath
        pendingShotPath = null
        when {
            saved && path != null -> onOcrImageReady(ImageSource.FilePath(path))
            // 用户取消拍照：把刚建出来的空文件删掉，不然缓存目录会攒一堆垃圾
            path != null -> runCatching { File(path).delete() }
        }
    }

    // PickVisualMedia 走系统的照片选择器，Android 13+ 是原生的，
    // 低版本回落到 ACTION_OPEN_DOCUMENT——**两条路都不需要读图权限**
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onOcrImageReady(ImageSource.ContentUri(uri))
    }

    fun launchCamera() {
        val dir = File(context.cacheDir, "ocr").apply { mkdirs() }
        val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return
        pendingShotPath = file.absolutePath
        cameraLauncher.launch(uri)
    }

    // --- 识别结果回填 ---
    // 这里收的是「从预览页返回」这一刻的信号。审核页把文本放进 OcrSessionStore，
    // 因为两个页面是两个独立的 ViewModel 实例，直接传是传不到的
    LaunchedEffect(Unit) {
        OcrSessionStore.filled.collect { text ->
            if (text == null) return@collect
            val incoming = OcrSessionStore.consumeFilled() ?: return@collect
            val before = viewModel.countFor(state.rawText)
            viewModel.fillFromOcr(incoming)
            val added = viewModel.countFor(incoming)
            // 整条流程结束了，把图和框一起放掉
            OcrSessionStore.clear()
            snackbarIsError = false
            snackbarHostState.showSnackbar(
                message = if (before == 0) {
                    "已识别 $added 个词语"
                } else {
                    "已追加 $added 个词语，共 ${before + added} 个"
                },
            )
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarIsError = true
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                SetupTopBar(
                    wordCount = state.wordCount,
                    onBack = onBack,
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        bottom = Spacing.lg,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    item {
                        WordsInput(
                            value = state.rawText,
                            onValueChange = viewModel::onTextChange,
                            onClear = viewModel::clearText,
                            onTakePhoto = ::launchCamera,
                            onPickImage = {
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                        )
                    }

                    item {
                        EstimateCard(
                            wordCount = state.wordCount,
                            estimatedMillis = state.estimatedMillis,
                            perCharSeconds = state.settings.perCharSeconds,
                        )
                    }

                    // 语言提示：只在「有内容」时才出现，空输入时不打扰
                    if (state.wordCount > 0) {
                        item {
                            LanguageHintCard(
                                usedLanguages = state.usedLanguages,
                                missingLanguages = state.missingLanguages,
                            )
                        }
                    }

                    item {
                        IntervalSettingsCard(
                            settings = state.settings,
                            onUpdate = viewModel::updateSettings,
                        )
                    }

                    item {
                        ReadSettingsCard(
                            settings = state.settings,
                            onUpdate = viewModel::updateSettings,
                        )
                    }

                    item { Spacer(Modifier.height(Spacing.sm)) }
                }

                StartBar(
                    enabled = state.canStart,
                    onStart = {
                        viewModel.commit()
                        onStarted()
                    },
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp),
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = if (snackbarIsError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.inverseSurface
                    },
                    contentColor = if (snackbarIsError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.inverseOnSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun SetupTopBar(wordCount: Int, onBack: () -> Unit) {
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
                text = "听写词语",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = if (wordCount == 0) "每行输入一个词语" else "共 $wordCount 个词语",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WordsInput(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "听写内容",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (value.isNotBlank()) {
                    TextButton(onClick = onClear) {
                        Text("清空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                placeholder = {
                    Text(
                        text = "例如：\n苹果\n蓝天\n小朋友\n中华人民共和国",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                },
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = BluePrimary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                ),
            )

            Spacer(Modifier.height(Spacing.md))

            // 拍照识字放在输入框正下方：它是「填内容」这件事的另一种做法，
            // 和输入框是一组，不该拆到页面别处去
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(
                    onClick = onTakePhoto,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = null,
                        tint = BluePrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("拍照识字", color = BluePrimary)
                }
                OutlinedButton(
                    onClick = onPickImage,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = null,
                        tint = BluePrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text("从相册选", color = BluePrimary)
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = "一行一个词语，行首的「1.」「一、」等序号会自动去掉。"
                        + "识字结果会追加到已有内容后面，不会覆盖。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EstimateCard(
    wordCount: Int,
    estimatedMillis: Long,
    perCharSeconds: Float,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BlueContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "预计书写时长",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (wordCount == 0) "—" else formatDurationChinese(estimatedMillis),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = if (wordCount == 0) {
                    "录入词语后自动估算"
                } else {
                    "按每字 ${formatSeconds(perCharSeconds)} 秒计算，不含朗读时间"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                modifier = Modifier.width(140.dp),
            )
        }
    }
}

/**
 * 语言自动识别提示卡。
 *
 * 中英词都能听写，语言按词语内容自动判断，用户不需要手动切换。
 * 但有两件事必须说清楚，否则会让人困惑：
 * 1) 混排时每个词各用各的语言朗读——这正是用户期望的，但要让他知道确实如此；
 * 2) 缺语音包时哪些词念不出来——必须提前说，不能开始后才静默失败。
 */
@Composable
private fun LanguageHintCard(
    usedLanguages: List<SpeechLanguage>,
    missingLanguages: List<SpeechLanguage>,
) {
    val hasProblem = missingLanguages.isNotEmpty()
    val container = if (hasProblem) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val onContainer = if (hasProblem) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (hasProblem) Icons.Filled.Warning else Icons.Filled.Translate,
                contentDescription = null,
                tint = if (hasProblem) onContainer else BluePrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (hasProblem) "缺少语音包" else "语言识别",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (hasProblem) onContainer else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (hasProblem) {
                        "设备没有${missingLanguages.joinToString("、") { it.displayName }}语音包，" +
                            "这部分词语将无法朗读。可到「设置 → 系统 → 语言和输入 → " +
                            "文字转语音 → 安装语音数据」中补装。"
                    } else {
                        buildString {
                            append("已自动识别为")
                            append(usedLanguages.joinToString("、") { it.displayName })
                            append("，将按对应语言朗读")
                            if (usedLanguages.size > 1) append("，中英混排也不会读错")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer,
                )
            }
        }
    }
}

/**
 * 间隔设置卡片。
 *
 * 这里刻意用「每字时长」而不是「总间隔」：用户的心智模型是
 * 「这个字大概要写多久」，而不是「我要等几秒」，参数与直觉对齐才好调。
 */
@Composable
private fun IntervalSettingsCard(
    settings: DictationSettings,
    onUpdate: ((DictationSettings) -> DictationSettings) -> Unit,
) {
    SettingsCard(title = "间隔时间") {
        SliderRow(
            label = "每字时长",
            valueText = "${formatSeconds(settings.perCharSeconds)} 秒",
            description = "词语有几个字，就等几倍这个时长",
            value = settings.perCharSeconds,
            range = DictationSettings.PER_CHAR_MIN..DictationSettings.PER_CHAR_MAX,
            steps = DictationSettings.PER_CHAR_STEPS,
            snap = 0.5f,
            format = { "${formatSeconds(it)} 秒" },
        ) { v ->
            onUpdate { it.copy(perCharSeconds = v) }
        }

        SliderRow(
            label = "最短间隔",
            valueText = "${formatSeconds(settings.minSeconds)} 秒",
            description = "单字词至少等这么久，保证来得及落笔",
            value = settings.minSeconds,
            range = DictationSettings.MIN_INTERVAL_MIN..DictationSettings.MIN_INTERVAL_MAX,
            steps = DictationSettings.MIN_INTERVAL_STEPS,
            snap = 1f,
            format = { "${it.toInt()} 秒" },
        ) { v ->
            onUpdate { it.copy(minSeconds = v) }
        }

        SliderRow(
            label = "最长间隔",
            valueText = "${formatSeconds(settings.maxSeconds)} 秒",
            description = "再长的词也不超过这个上限，避免干等",
            value = settings.maxSeconds,
            range = DictationSettings.MAX_INTERVAL_MIN..DictationSettings.MAX_INTERVAL_MAX,
            steps = DictationSettings.MAX_INTERVAL_STEPS,
            snap = 5f,
            format = { "${it.toInt()} 秒" },
        ) { v ->
            onUpdate { it.copy(maxSeconds = v) }
        }
    }
}

@Composable
private fun ReadSettingsCard(
    settings: DictationSettings,
    onUpdate: ((DictationSettings) -> DictationSettings) -> Unit,
) {
    SettingsCard(title = "朗读设置") {
        SliderRow(
            label = "语速",
            valueText = String.format("%.1f 倍", settings.speechRate),
            description = "偏慢一点更适合听写",
            value = settings.speechRate,
            range = DictationSettings.RATE_MIN..DictationSettings.RATE_MAX,
            steps = DictationSettings.RATE_STEPS,
            snap = 0.1f,
            format = { String.format("%.1f 倍", it) },
        ) { v ->
            onUpdate { it.copy(speechRate = v) }
        }

        SliderRow(
            label = "重复朗读",
            valueText = "${settings.repeatCount} 遍",
            description = "同一个词连着读几遍",
            value = settings.repeatCount.toFloat(),
            range = DictationSettings.REPEAT_MIN.toFloat()..DictationSettings.REPEAT_MAX.toFloat(),
            steps = DictationSettings.REPEAT_STEPS,
            snap = 1f,
            format = { "${it.toInt()} 遍" },
        ) { v ->
            onUpdate { it.copy(repeatCount = v.toInt().coerceIn(1, 3)) }
        }

        SliderRow(
            label = "预备时间",
            valueText = if (settings.prepareSeconds == 0) "不等待" else "${settings.prepareSeconds} 秒",
            description = "开始前留出拿笔、铺本子的时间",
            value = settings.prepareSeconds.toFloat(),
            range = DictationSettings.PREPARE_MIN.toFloat()..DictationSettings.PREPARE_MAX.toFloat(),
            steps = DictationSettings.PREPARE_STEPS,
            snap = 1f,
            format = { if (it.toInt() == 0) "不等待" else "${it.toInt()} 秒" },
        ) { v ->
            onUpdate { it.copy(prepareSeconds = v.toInt().coerceIn(0, 10)) }
        }

        SwitchRow(
            label = "自动进入下一词",
            description = "关闭后需要手动点「下一个」",
            checked = settings.autoAdvance,
        ) { v ->
            onUpdate { it.copy(autoAdvance = v) }
        }

        SwitchRow(
            label = "朗读词语序号",
            description = "例如「第 1 个，苹果」",
            checked = settings.speakIndex,
        ) { v ->
            onUpdate { it.copy(speakIndex = v) }
        }

        SwitchRow(
            label = "结束时语音提示",
            description = "全部读完后朗读「听写结束」",
            checked = settings.speakFinishAnnouncement,
        ) { v ->
            onUpdate { it.copy(speakFinishAnnouncement = v) }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.md))
            content()
        }
    }
}

/**
 * 一行滑块设置。
 *
 * @param snap    吸附粒度。Compose 的 Slider 在浮点量程上不能保证落点整齐，
 *                显式吸附一次才能保证用户看到的是 1.5 而不是 1.5000001。
 * @param format  数值 → 展示文案。拖动过程中用它实时预览。
 */
@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    snap: Float = 0f,
    format: (Float) -> String = { formatSeconds(it) },
    onValueChange: (Float) -> Unit,
) {
    // 拖动中用本地预览值，松手才向上提交：
    // 避免连续拖动触发几十次 DataStore 写入。
    var dragging by remember { mutableStateOf(false) }
    var preview by remember(value) { mutableFloatStateOf(value) }

    val commit = { raw: Float ->
        val snapped = if (snap > 0f) (raw / snap).roundToInt() * snap else raw
        snapped.coerceIn(range.start, range.endInclusive)
    }

    Column(Modifier.padding(vertical = Spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // 当前值做成色块：拖动时变色，一眼能确认"调到了"
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (dragging) {
                    BluePrimary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Text(
                    text = if (dragging) format(commit(preview)) else valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (dragging) BluePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 3.dp),
                )
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = (if (dragging) preview else value).coerceIn(range.start, range.endInclusive),
            onValueChange = { raw ->
                dragging = true
                preview = raw
            },
            onValueChangeFinished = {
                dragging = false
                onValueChange(commit(preview))
            },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StartBar(enabled: Boolean, onStart: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Button(
                onClick = onStart,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BluePrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = if (enabled) "开始听写" else "请先录入词语",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
