package cn.com.toobug.youxue.util

import kotlin.math.roundToInt

/**
 * 一个矩形区域（像素）。
 *
 * 刻意不用 `android.graphics.Rect`：这里的换算逻辑需要能单测，
 * 而 Rect 是 Android 类，在 JVM 单测里拿到的是空壳实现，测了等于没测。
 */
data class CropBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val width: Int get() = right - left

    val height: Int get() = bottom - top

    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/** EXIF 方向信息换算出来的变换。拍照十张有八张带方向标记，不处理就会得到躺着的图 */
data class ExifTransform(val degrees: Int, val flipHorizontal: Boolean)

/**
 * 图片尺寸相关的纯计算。
 *
 * 全部不依赖 Android 类——降采样倍率、fitCenter 布局、显示坐标到位图坐标的换算
 * 都是纯算术，而它们恰恰是最容易算错、也最值得回归保护的地方。
 * 真正碰 Bitmap 的部分在 [BitmapLoader]。
 */
object ImageMath {

    /**
     * 解码时的长边上限。
     *
     * 手机随手拍就是 4000×3000，按 ARGB_8888 直接解码是 48MB，
     * 几台机器上就会 OOM。压到 2000 对识别精度没有任何损失——
     * ML Kit 的检测网络本身就在这个量级上工作，喂更大的图只是更慢更费内存。
     */
    const val MAX_LONG_SIDE = 2000

    /**
     * 算 `BitmapFactory.Options.inSampleSize`。
     *
     * 必须是 2 的幂：BitmapFactory 规定非 2 的幂会被向下取到最近的 2 的幂，
     * 与其让它悄悄改，不如自己算准。
     *
     * 结果是「不小于需求」的那个 2 的幂——宁可略大一点也不要比目标小，
     * 缩过头就再也放不回来了。
     */
    fun sampleSize(srcWidth: Int, srcHeight: Int, maxLongSide: Int = MAX_LONG_SIDE): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || maxLongSide <= 0) return 1
        var sample = 1
        val longSide = maxOf(srcWidth, srcHeight)
        while (longSide / (sample * 2) >= maxLongSide) {
            sample *= 2
        }
        return sample
    }

    /**
     * 位图在容器里按 fitCenter（等比缩放、居中、不裁切）显示后占的实际矩形。
     *
     * 裁剪框的坐标全部相对**这个矩形**而不是相对容器。否则图片上下两边
     * 的留白会被当成图片内容算进裁剪范围，框到边上裁出来就是黑的。
     */
    fun fitRect(srcWidth: Int, srcHeight: Int, viewWidth: Int, viewHeight: Int): CropBox {
        if (srcWidth <= 0 || srcHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return CropBox(0, 0, 0, 0)
        }
        val scale = minOf(viewWidth.toFloat() / srcWidth, viewHeight.toFloat() / srcHeight)
        val w = (srcWidth * scale).roundToInt().coerceIn(1, viewWidth)
        val h = (srcHeight * scale).roundToInt().coerceIn(1, viewHeight)
        val left = (viewWidth - w) / 2
        val top = (viewHeight - h) / 2
        return CropBox(left, top, left + w, top + h)
    }

    /**
     * 把「相对显示矩形」的裁剪框换算成位图坐标。
     *
     * 两个方向各自算比例，不共用一个 scale：显示区域经过取整后宽高比
     * 和原图不会严格相等，共用会带来几个像素的错位，而错位体现在
     * 裁剪结果上就是边缘被切掉一小条——正是最不想切到的字。
     */
    fun displayBoxToBitmapBox(
        box: CropBox,
        displayWidth: Int,
        displayHeight: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): CropBox {
        if (displayWidth <= 0 || displayHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
            return CropBox(0, 0, 0, 0)
        }
        val sx = bitmapWidth.toFloat() / displayWidth
        val sy = bitmapHeight.toFloat() / displayHeight
        return CropBox(
            (box.left * sx).roundToInt(),
            (box.top * sy).roundToInt(),
            (box.right * sx).roundToInt(),
            (box.bottom * sy).roundToInt(),
        )
    }

    /**
     * 把框夹进图片边界内，并保证宽高不低于 [minSize]。
     *
     * 先夹右/下，右边贴边了还不够就把左/上往回挪——只夹一边的话，
     * 用户在右下角拖出一个很小的框时会得到一个空框，裁出来直接崩。
     * 小尺寸裁剪框在 OCR 里毫无价值，宁可给一块稍大的。
     */
    fun clampBox(box: CropBox, srcWidth: Int, srcHeight: Int, minSize: Int = 8): CropBox {
        if (srcWidth <= 0 || srcHeight <= 0) return CropBox(0, 0, 0, 0)
        val size = minSize.coerceAtMost(minOf(srcWidth, srcHeight))

        var left = box.left.coerceIn(0, srcWidth - size)
        var top = box.top.coerceIn(0, srcHeight - size)
        var right = box.right.coerceIn(left + size, srcWidth)
        var bottom = box.bottom.coerceIn(top + size, srcHeight)

        // 右上角贴边后宽/高不足时，往回推左/上
        if (right - left < size) left = (right - size).coerceAtLeast(0)
        if (bottom - top < size) top = (bottom - size).coerceAtLeast(0)

        // 再兜一层，防止上面的推挤把值推出边界
        right = right.coerceIn(left + size, srcWidth)
        bottom = bottom.coerceIn(top + size, srcHeight)
        left = left.coerceIn(0, right - size)
        top = top.coerceIn(0, bottom - size)

        return CropBox(left, top, right, bottom)
    }

    /**
     * EXIF 方向值 → 需要施加的变换。
     *
     * 后置摄像头竖着拍出来通常是 `ROTATE_90`——像素数据本身是横躺的，
     * 靠这个标记告诉看图的人「请转 90 度」。不处理的话，OCR 拿到的就是
     * 一张躺着的图，识别率断崖式下跌。
     *
     * 取值对应 `android.media.ExifInterface.ORIENTATION_*`；
     * 传进来未知值时按「不变换」处理，这是最保守的选择。
     */
    fun exifTransform(orientation: Int): ExifTransform = when (orientation) {
        1 -> ExifTransform(0, false) // NORMAL
        2 -> ExifTransform(0, true) // FLIP_HORIZONTAL
        3 -> ExifTransform(180, false) // ROTATE_180
        4 -> ExifTransform(180, true) // FLIP_VERTICAL
        5 -> ExifTransform(90, true) // TRANSPOSE
        6 -> ExifTransform(90, false) // ROTATE_90
        7 -> ExifTransform(270, true) // TRANSVERSE
        8 -> ExifTransform(270, false) // ROTATE_270
        else -> ExifTransform(0, false)
    }
}
