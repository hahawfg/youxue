package cn.com.toobug.youxue.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log

/**
 * 待识别的图片来自哪里。
 *
 * 系统相机回给我们的是一个文件路径（FileProvider 给的临时文件），
 * 相册选图回给我们的是内容 URI。两者的解码方式不同但语义相同，
 * 用一个密封类型区分，比到处传 `String` 再猜它是路径还是 URI 可靠。
 *
 * 只存路径/URI 不存 Bitmap：**Bitmap 是十几 MB 的大对象，
 * 放进应用级单例等于给自己埋一个泄漏点**。谁用到谁解码，
 * 解出来的位图跟着页面的生命周期走。
 */
sealed interface ImageSource {

    data class FilePath(val path: String) : ImageSource

    data class ContentUri(val uri: Uri) : ImageSource
}

/**
 * 位图解码与裁剪。
 *
 * 用 `android.media.ExifInterface` 而不是 `androidx.exifinterface`：
 * 框架自带的这个类从 API 5 就有（按文件名读），API 24 起支持从流读，
 * 而本项目 minSdk 正好是 24——**为了一件事加一个新依赖不划算**。
 *
 * 所有解码都强制降采样。这不是优化，是必须：不降采样的话
 * 用户在几台机器上就会撞到 OOM，而且是拍照回来那一刻才崩。
 */
object BitmapLoader {

    private const val TAG = "BitmapLoader"

    /** 按来源分派解码。调用方不必关心它是相机文件还是相册 URI */
    fun decode(
        context: Context,
        source: ImageSource,
        maxLongSide: Int = ImageMath.MAX_LONG_SIDE,
    ): Bitmap? = when (source) {
        is ImageSource.FilePath -> decodeFile(source.path, maxLongSide)
        is ImageSource.ContentUri -> decodeUri(context.contentResolver, source.uri, maxLongSide)
    }

    /**
     * 从文件路径解码，长边压到 [maxLongSide] 以内，并按 EXIF 摆正。
     *
     * 系统相机拍出来的图走这条路（我们给了它一个 FileProvider 的文件 URI）。
     */
    fun decodeFile(path: String, maxLongSide: Int = ImageMath.MAX_LONG_SIDE): Bitmap? =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val opts = BitmapFactory.Options().apply {
                inSampleSize = ImageMath.sampleSize(bounds.outWidth, bounds.outHeight, maxLongSide)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(path, opts) ?: return null

            val orientation = runCatching {
                ExifInterface(path)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

            applyTransform(decoded, ImageMath.exifTransform(orientation))
        }.onFailure { Log.w(TAG, "解码失败：$path", it) }.getOrNull()

    /**
     * 从内容 URI 解码。相册选图走这条路。
     *
     * 输入流开了三次（量尺寸、真解码、读 EXIF）：ContentResolver 的流
     * 只能顺序读一次，而这三件事发生在不同阶段，只能重开。
     * 重开的开销是查一次 provider，相比解码本身可以忽略。
     */
    fun decodeUri(
        resolver: ContentResolver,
        uri: Uri,
        maxLongSide: Int = ImageMath.MAX_LONG_SIDE,
    ): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = ImageMath.sampleSize(bounds.outWidth, bounds.outHeight, maxLongSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val orientation = resolver.openInputStream(uri)?.use { stream ->
            runCatching {
                ExifInterface(stream)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        applyTransform(decoded, ImageMath.exifTransform(orientation))
    }.onFailure { Log.w(TAG, "解码失败：$uri", it) }.getOrNull()

    /**
     * 按 [box]（位图坐标系）裁出子图。
     *
     * 越界或空框一律返回 null 而不是抛异常——用户在裁剪页手抖拖出一个
     * 极小框是完全可能的正常操作，不该让 App 崩掉。
     */
    fun crop(src: Bitmap, box: CropBox): Bitmap? {
        if (src.isRecycled) return null
        val safe = ImageMath.clampBox(box, src.width, src.height)
        if (safe.isEmpty) return null
        return runCatching {
            Bitmap.createBitmap(src, safe.left, safe.top, safe.width, safe.height)
        }.onFailure { Log.w(TAG, "裁剪失败：$safe", it) }.getOrNull()
    }

    private fun applyTransform(src: Bitmap, transform: ExifTransform): Bitmap {
        if (transform.degrees == 0 && !transform.flipHorizontal) return src
        val matrix = Matrix().apply {
            if (transform.degrees != 0) postRotate(transform.degrees.toFloat())
            // 镜像必须在旋转之后叠加：EXIF 的定义里 flip 是先于 rotate 施加的，
            // 用 postXxx 的顺序反过来才等价
            if (transform.flipHorizontal) postScale(-1f, 1f)
        }
        return runCatching {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
                .also { if (it !== src) src.recycle() }
        }.getOrDefault(src)
    }
}
