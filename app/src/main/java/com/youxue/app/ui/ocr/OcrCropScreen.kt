package com.youxue.app.ui.ocr

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.youxue.app.ocr.OcrSessionStore
import com.youxue.app.ui.theme.BluePrimary
import com.youxue.app.ui.theme.Spacing
import com.youxue.app.util.BitmapLoader
import com.youxue.app.util.CropBox
import com.youxue.app.util.ImageMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 裁剪页。
 *
 * 目的是「先把不该识字的地方整块去掉」：课本一页里往往混着题号、页眉、
 * 旁边那一栏、下一课的词语。与其让 OCR 全识别出来再一条条删，
 * 不如先框出真正要的那一块。
 *
 * 但裁剪框选不准时比不裁更糟——把「苹果」框掉上半截，识别出来就是残字。
 * 所以给「整图识别」作为等价出口，多余内容不多时直接跳过这一步。
 *
 * **坐标系**：页面内部统一用 **dp 数值** 而不是 px 做布局算术，
 * 选中框的坐标相对「图片实际显示区域」这个子 Box，和容器留白无关——
 * 否则图片上下的黑边会被算进裁剪范围。真正的换算只在最后交给
 * [BitmapLoader.crop] 之前做一次，且走的是能单测的 [ImageMath]。
 */
@Composable
fun OcrCropScreen(
    onBack: () -> Unit,
    onRecognize: () -> Unit,
) {
    val context = LocalContext.current
    val source by OcrSessionStore.source.collectAsStateWithLifecycle()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var decodeFailed by remember { mutableStateOf(false) }

    LaunchedEffect(source) {
        val src = source ?: return@LaunchedEffect
        decodeFailed = false
        val decoded = withContext(Dispatchers.IO) { BitmapLoader.decode(context, src) }
        if (decoded == null) decodeFailed = true else bitmap = decoded
    }

    // 这里**不主动 recycle**：导航切换有淡出动画，页面退出的一两帧里
    // 这张图还在被绘制，此时回收会直接抛 "trying to use a recycled bitmap"。
    // 交给 GC 就好——位图不可达后自然会被收走，代价只是一小段时间的内存峰值。
    // 真正需要立刻回收的是那些没有 UI 引用它的地方（比如预览页里 OCR 用完之后）。

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            CropTopBar(onBack = onBack)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                when {
                    decodeFailed -> Text(
                        text = "图片读取失败，请返回换一张重试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    bmp == null -> CircularProgressIndicator(color = BluePrimary)

                    else -> CropCanvas(
                        bitmap = bmp,
                        onPicked = { bitmapBox ->
                            OcrSessionStore.setCrop(bitmapBox)
                            onRecognize()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CropTopBar(onBack: () -> Unit) {
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
                    text = "框出要听写的文字",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "拖四角调范围，拖中间整体移动",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 图片 + 裁剪框 + 两个出口按钮。
 *
 * 图片按 `ContentScale.Fit` 铺满容器，但**选中框不铺在容器上**，
 * 而是单独建一个 offset+size 完全等于显示区域的子 Box——
 * 这样框的坐标天然就是「图片坐标系」，不用到处减留白偏移。
 */
@Composable
private fun CropCanvas(
    bitmap: Bitmap,
    /** 参数是**位图坐标系**下的裁剪区域；传 null 表示用整张图 */
    onPicked: (CropBox?) -> Unit,
) {
    val density = LocalDensity.current
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val containerW = with(density) { containerSize.width.toDp().value }.roundToInt()
    val containerH = with(density) { containerSize.height.toDp().value }.roundToInt()

    val display = remember(containerW, containerH, bitmap.width, bitmap.height) {
        if (containerW <= 0 || containerH <= 0) {
            CropBox(0, 0, 0, 0)
        } else {
            ImageMath.fitRect(bitmap.width, bitmap.height, containerW, containerH)
        }
    }

    // 默认框留一圈边距：用户多半想连边上的词一起要，贴着图片边缘反而不好拖
    var box by remember(display) {
        mutableStateOf(
            CropBox(
                (display.width * 0.06f).roundToInt(),
                (display.height * 0.06f).roundToInt(),
                (display.width * 0.94f).roundToInt(),
                (display.height * 0.94f).roundToInt(),
            )
        )
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { containerSize = it.size },
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )

            if (display.width > 0 && display.height > 0) {
                Box(
                    modifier = Modifier
                        .offset(x = display.left.dp, y = display.top.dp)
                        .size(display.width.dp, display.height.dp)
                ) {
                    CropOverlay(
                        box = box,
                        boundsWidth = display.width,
                        boundsHeight = display.height,
                        onBoxChange = { box = it },
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            OutlinedButton(
                onClick = { onPicked(null) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("整图识别", color = MaterialTheme.colorScheme.onSurface)
            }
            Button(
                onClick = {
                    onPicked(
                        ImageMath.displayBoxToBitmapBox(
                            box = box,
                            displayWidth = display.width,
                            displayHeight = display.height,
                            bitmapWidth = bitmap.width,
                            bitmapHeight = bitmap.height,
                        )
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
            ) {
                Text("识别选区", fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * 裁剪框：压暗层 + 边框 + 三分线 + 四个角手柄 + 整体拖动。
 *
 * 所有坐标都在「图片显示区域」的局部坐标系里，四个整数、单位是 **dp**。
 * 布局（手柄的 offset/size）本来就是 dp，所以直接可用；
 * 唯一要小心的是 [Canvas] 里的绘制——DrawScope 的单位是 px，
 * 进去先 `.dp.toPx()` 换算一次，否则画出来的框和手柄会分处两个坐标系。
 *
 * 每次改动都过一遍 [ImageMath.clampBox]——把边界约束收在一处，
 * 手柄和整体拖动就都只剩「算出新值」这一件事。
 */
@Composable
private fun CropOverlay(
    box: CropBox,
    boundsWidth: Int,
    boundsHeight: Int,
    onBoxChange: (CropBox) -> Unit,
) {
    val scrim = Color.Black.copy(alpha = 0.55f)

    // 手势协程**只在 key 变化时重启**，所以闭包里读到的会是协程启动那一刻的值。
    // 这两个 rememberUpdatedState 就是为此而设：它们每帧跟随重组更新，
    // 而协程持有的是那个稳定的 State 对象，读出来永远是最新值。
    val currentBox by rememberUpdatedState(box)
    val currentOnBoxChange by rememberUpdatedState(onBoxChange)

    fun clamp(next: CropBox) = ImageMath.clampBox(next, boundsWidth, boundsHeight, minSize = 24)

    Box(Modifier.fillMaxSize()) {

        Canvas(Modifier.fillMaxSize()) {
            // ⚠️ box 里存的是 **dp 数值**，而 DrawScope 的坐标系是 **px**——这里必须换算。
            //
            // 这里踩过一个大坑：原来写的是 `box.left.toFloat()`，等于把 dp 数当 px 用。
            // 于是密度 2.75 的屏幕上，整个选框（压暗层的挖空 + 白色描边 + 三分线）
            // 被缩小到 **1/2.75** 并挤到图片左上角，而四个角手柄是 Compose 按 dp
            // 摆放的、位置完全正确 —— 两者处在两个坐标系里，画出来的框永远追不上手柄，
            // 现象正是「拖手柄时选取边框不跟」。更隐蔽的是：手指移动 100px 时
            // 框只画出 100/2.75≈36px，所以看起来还像「拖不动/跟不上手指」，
            // 于是连续几轮都在手势代码里找原因，其实手势一直是对的。
            // DrawScope 本身实现了 Density，所以直接 .toPx() 即可。
            val l = box.left.dp.toPx()
            val t = box.top.dp.toPx()
            val r = box.right.dp.toPx()
            val b = box.bottom.dp.toPx()
            val w = (r - l).coerceAtLeast(0f)
            val h = (b - t).coerceAtLeast(0f)

            // 选区外压暗，让用户一眼看清「保留的是哪一块」。
            // 用四条矩形拼出「中间挖空」的效果，比 Path 简单，也不会因
            // 填充规则写错而把选区一起盖住。
            drawRect(scrim, size = Size(size.width, t))
            drawRect(
                scrim,
                topLeft = Offset(0f, b),
                size = Size(size.width, (size.height - b).coerceAtLeast(0f)),
            )
            drawRect(scrim, topLeft = Offset(0f, t), size = Size(l, h))
            drawRect(
                scrim,
                topLeft = Offset(r, t),
                size = Size((size.width - r).coerceAtLeast(0f), h),
            )

            // 三分线，帮助判断框有没有歪
            val guide = Color.White.copy(alpha = 0.35f)
            for (i in 1..2) {
                val x = l + w * i / 3f
                val y = t + h * i / 3f
                drawLine(guide, Offset(x, t), Offset(x, b), strokeWidth = 1f)
                drawLine(guide, Offset(l, y), Offset(r, y), strokeWidth = 1f)
            }

            drawRect(
                color = Color.White,
                topLeft = Offset(l, t),
                size = Size(w, h),
                style = Stroke(width = 2.dp.toPx()),
            )
        }

        // 整体拖动。热区**铺满整个图片显示区**，而不是只盖住选框本身——
        // 用户想「拖这个蒙版」时，手很可能落在选框外那圈压暗区域上，
        // 只认框内会让人以为拖不动。落在框外的拖动同样只做平移，不改变框的大小。
        // 四角手柄写在这之后（同层 Box 里后写的在上层），
        // 落在角上的触摸会先被手柄拿走，两者不会互相抢。
        //
        // key 用容器尺寸而不是框本身：容器尺寸才是这段手势的「有效域」
        // （容器变了 clamp 的边界就变了，该重启），而它在拖动过程中恒定，
        // 所以协程不会中途被打断。框的最新值靠 currentBox 读，不靠 key 传。
        //
        // 增量一律走 detectBoxDrag：位移累积后再换算 dp、且从手势起点重算，
        // 而不是每帧各自取整、也不是在「当前框」上叠加（细节见该函数注释）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(boundsWidth, boundsHeight) {
                    detectBoxDrag(startFrom = { currentBox }) { start, dx, dy ->
                        currentOnBoxChange(clamp(start.move(dx, dy)))
                    }
                }
        )

        // 四个角。新框同样从「手势起点 + 累积位移」算出，
        // 起点经 currentBox 取（它每帧跟随重组，是最新值）
        CornerHandle(Corner.TopStart, { currentBox }) { currentOnBoxChange(clamp(it)) }
        CornerHandle(Corner.TopEnd, { currentBox }) { currentOnBoxChange(clamp(it)) }
        CornerHandle(Corner.BottomStart, { currentBox }) { currentOnBoxChange(clamp(it)) }
        CornerHandle(Corner.BottomEnd, { currentBox }) { currentOnBoxChange(clamp(it)) }
    }
}

/**
 * 一个角手柄。
 *
 * 触摸区（44dp）远大于可见点（12dp）：手指按不准 12dp 的目标，
 * 而视觉上只需要一个明确的角点。用 offset 把触摸区的**中心**对到角点上，
 * 所以偏移量是负的半个触摸区。
 *
 * **绝对定位就不要再 `align`。** 这里踩过：原来写成
 * `align(alignment).offset(anchorX - half, anchorY - half)`，
 * 而 `align` 已经把子项摆到了那一侧的边上（如 BottomEnd 是 `宽-44`），
 * `offset` 是在此基础上**再加**一次绝对坐标，两者叠加，
 * 四个角里三个被推到画面外（BottomEnd 约在 `宽 + 框右` 处），
 * 屏幕上就只剩 TopStart 那一个圆圈、还不在角上。
 * 现在改成「默认 TopStart 摆放 + 只 offset 一次」，中心正好落在角点上。
 *
 * **不改手势的 key。** `pointerInput(Unit)` 表示「永不因重组而重启」——
 * 曾经的写法是 `pointerInput(anchorX, anchorY)`，而 anchor 就是 box 的四角坐标，
 * 恰恰是被拖动本身改的那个值。第一个增量改完 box 就触发 key 变化，
 * 协程被取消重启，`detectDragGestures` 回到「等下一次按下」，
 * 可手指还按在屏幕上不会再有 down，拖动当场就断。
 * 角点位置每帧靠重组更新，不需要靠 key 去刷。
 *
 * 本组件只负责「画圆圈 + 把位移交给谁」；位移与框的算法在 [detectBoxDrag] /
 * [dragCorner] 里，都是纯逻辑。
 */
@Composable
private fun CornerHandle(
    corner: Corner,
    /** 取当前框。拖动起点由它决定，所以必须是最新值 */
    currentBox: () -> CropBox,
    onBoxChange: (CropBox) -> Unit,
) {
    val touchSize: Dp = 44.dp
    val dotSize: Dp = 12.dp
    val half = touchSize / 2

    // 角点位置：每次重组读一次，跟着框走
    val box = currentBox()
    val anchorX = when (corner) {
        Corner.TopStart, Corner.BottomStart -> box.left
        Corner.TopEnd, Corner.BottomEnd -> box.right
    }
    val anchorY = when (corner) {
        Corner.TopStart, Corner.TopEnd -> box.top
        Corner.BottomStart, Corner.BottomEnd -> box.bottom
    }

    // 同 CropOverlay：协程不重启，闭包里就必须读到最新的取值函数与回调，
    // 否则拿到的还是启动那一刻的旧值
    val currentStartFrom by rememberUpdatedState(currentBox)
    val currentOnBoxChange by rememberUpdatedState(onBoxChange)

    Box(
        modifier = Modifier
            .offset(x = anchorX.dp - half, y = anchorY.dp - half)
            .size(touchSize)
            .pointerInput(Unit) {
                detectBoxDrag(startFrom = { currentStartFrom() }) { start, dx, dy ->
                    currentOnBoxChange(start.dragCorner(corner, dx, dy))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .drawBehind {
                    drawCircle(color = Color.White, radius = size.minDimension / 2)
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.35f),
                        radius = size.minDimension / 2,
                        style = Stroke(width = 2f),
                    )
                }
        )
    }
}

/** 四个角。拖动某一角时，改的是这个角对应的那两条边 */
private enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

/** 整体平移：四个边一起动，宽高不变 */
private fun CropBox.move(dx: Int, dy: Int): CropBox =
    CropBox(left + dx, top + dy, right + dx, bottom + dy)

/** 拖某一角：只动该角对应的两条边，另外两条边是锚 */
private fun CropBox.dragCorner(corner: Corner, dx: Int, dy: Int): CropBox = when (corner) {
    Corner.TopStart -> copy(left = left + dx, top = top + dy)
    Corner.TopEnd -> copy(right = right + dx, top = top + dy)
    Corner.BottomStart -> copy(left = left + dx, bottom = bottom + dy)
    Corner.BottomEnd -> copy(right = right + dx, bottom = bottom + dy)
}

/**
 * 裁剪框拖动。回调拿到的是**手势起点那一刻的框**，和**从起点累积到现在的 dp 位移**。
 * 裁剪页两处手势（整体平移、四角缩放）都走它，保证两者手感规则完全一致。
 *
 * 这里有两个坑，都踩过，所以特意做成现在这样：
 *
 * **一、位移必须累积后再换算成 dp。** 手势事件给的是 px，而框坐标是 dp 整数。
 * 若每帧各自「本帧 px → dp → 取整」，在密度 2.75 的屏幕上每帧位移不足
 * 1.375px（≈0.5dp）就被舍成 0；而零头是**每帧重新开始、不会攒起来**的，
 * 于是慢速拖动时帧帧都得 0，回调一次都不触发——现象就是「怎么拖都不动」。
 *
 * **二、新框必须从起点重算，不能在「当前框」上叠加增量。** 触摸采样率通常高于
 * 渲染帧率（120Hz 触摸 vs 60/120Hz 刷新），**同一帧内会有 2~3 个 move 事件**，
 * 而「当前框」要等下一帧重组才会更新。若在它上面叠加，同一帧里只有第一个事件
 * 真正生效、后面几个都基于同一个旧值算，位移被整段丢掉——现象就是手指移了
 * 100px、框只走三分之一，「跟不上手指」。从起点重算天然幂等，来多少事件都不丢。
 */
private suspend fun PointerInputScope.detectBoxDrag(
    /** 读手势起点那一刻的框。必须是最新值，调用处一律传经 `rememberUpdatedState` 包装的取值函数 */
    startFrom: () -> CropBox,
    onDragTo: (start: CropBox, dx: Int, dy: Int) -> Unit,
) {
    var start = CropBox(0, 0, 0, 0)
    var accX = 0f
    var accY = 0f
    var lastX = 0
    var lastY = 0

    detectDragGestures(
        onDragStart = {
            start = startFrom()
            accX = 0f
            accY = 0f
            lastX = 0
            lastY = 0
        },
        onDrag = { change, amount ->
            change.consume()
            accX += amount.x
            accY += amount.y

            val dx = accX.toDp().value.roundToInt()
            val dy = accY.toDp().value.roundToInt()
            // 换算后没变化就不必惊动上层——一帧内可能有多个事件落在同一个 dp 上
            if (dx != lastX || dy != lastY) {
                lastX = dx
                lastY = dy
                onDragTo(start, dx, dy)
            }
        },
    )
}
