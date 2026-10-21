package cn.com.toobug.youxue.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片尺寸换算的回归测试。
 *
 * 这几段算术看着简单，但错一个方向就是「用户框了这一块、裁出来是另一块」，
 * 而且要靠肉眼在真机上看出来非常费劲——正是最该用测试钉住的那类代码。
 */
class ImageMathTest {

    // ---- 降采样倍率 ----

    @Test
    fun `四千宽的图降一半`() {
        // 4000 / 2 = 2000 >= 2000，再降一次就只剩 1000 了，所以停在 2
        assertEquals(2, ImageMath.sampleSize(4000, 3000, 2000))
    }

    @Test
    fun `长边用长的那一边判断`() {
        assertEquals(2, ImageMath.sampleSize(3000, 4000, 2000))
    }

    @Test
    fun `刚好等于上限时不降采样`() {
        assertEquals(1, ImageMath.sampleSize(2000, 1500, 2000))
    }

    @Test
    fun `小图不放大`() {
        assertEquals(1, ImageMath.sampleSize(800, 600, 2000))
        assertEquals(1, ImageMath.sampleSize(100, 100, 2000))
    }

    @Test
    fun `超大图按 2 的幂逐级降`() {
        assertEquals(4, ImageMath.sampleSize(8000, 6000, 2000))
        assertEquals(8, ImageMath.sampleSize(16000, 12000, 2000))
    }

    @Test
    fun `降采样后长边不会小于目标`() {
        val samples = listOf(4000, 4032, 6000, 8192, 12000)
        samples.forEach { w ->
            val s = ImageMath.sampleSize(w, w * 3 / 4, 2000)
            assertTrue("$w 降到 ${w / s}，不该小于 2000", w / s >= 2000)
        }
    }

    @Test
    fun `非法尺寸退回 1`() {
        assertEquals(1, ImageMath.sampleSize(0, 0, 2000))
        assertEquals(1, ImageMath.sampleSize(-1, 100, 2000))
        assertEquals(1, ImageMath.sampleSize(4000, 3000, 0))
    }

    // ---- fitCenter 显示矩形 ----

    @Test
    fun `宽图上下留白并垂直居中`() {
        // 1000x500 放进 400x400：缩放 0.4 -> 400x200，上下各留 100
        assertEquals(CropBox(0, 100, 400, 300), ImageMath.fitRect(1000, 500, 400, 400))
    }

    @Test
    fun `高图左右留白并水平居中`() {
        // 500x1000 放进 400x400：缩放 0.4 -> 200x400，左右各留 100
        assertEquals(CropBox(100, 0, 300, 400), ImageMath.fitRect(500, 1000, 400, 400))
    }

    @Test
    fun `比例一致时铺满不留白`() {
        assertEquals(CropBox(0, 0, 400, 200), ImageMath.fitRect(1000, 500, 400, 200))
    }

    @Test
    fun `尺寸非法时返回空框而不是崩`() {
        assertEquals(CropBox(0, 0, 0, 0), ImageMath.fitRect(0, 100, 400, 400))
        assertEquals(CropBox(0, 0, 0, 0), ImageMath.fitRect(1000, 500, 0, 400))
    }

    @Test
    fun `显示矩形不会超出容器`() {
        val r = ImageMath.fitRect(4032, 3024, 997, 613)
        assertTrue("右边越界：$r", r.right <= 997)
        assertTrue("下边越界：$r", r.bottom <= 613)
        assertTrue("左边为负：$r", r.left >= 0)
        assertTrue("上边为负：$r", r.top >= 0)
    }

    // ---- 显示坐标到位图坐标 ----

    @Test
    fun `显示坐标按比例放大到位图坐标`() {
        // 显示层 400x200，位图 1000x500，比例 2.5
        val got = ImageMath.displayBoxToBitmapBox(
            box = CropBox(0, 100, 400, 300),
            displayWidth = 400,
            displayHeight = 200,
            bitmapWidth = 1000,
            bitmapHeight = 500,
        )
        assertEquals(CropBox(0, 250, 1000, 750), got)
    }

    @Test
    fun `两个方向各自换算`() {
        // 显示 200x300 -> 位图 1000x600：横向 x5、纵向 x2
        val got = ImageMath.displayBoxToBitmapBox(
            box = CropBox(10, 20, 110, 120),
            displayWidth = 200,
            displayHeight = 300,
            bitmapWidth = 1000,
            bitmapHeight = 600,
        )
        assertEquals(CropBox(50, 40, 550, 240), got)
    }

    @Test
    fun `换算后不越位图边界`() {
        val bitmap = 1000
        val got = ImageMath.displayBoxToBitmapBox(
            box = CropBox(0, 0, 400, 400),
            displayWidth = 400,
            displayHeight = 400,
            bitmapWidth = bitmap,
            bitmapHeight = 800,
        )
        assertTrue(got.right <= bitmap)
        assertTrue(got.bottom <= 800)
    }

    // ---- 边界夹取 ----

    @Test
    fun `越界的框被夹回图片内`() {
        assertEquals(CropBox(0, 0, 100, 80), ImageMath.clampBox(CropBox(-50, -50, 500, 500), 100, 80))
    }

    @Test
    fun `右下角的小框不会被夹成空框`() {
        val got = ImageMath.clampBox(CropBox(95, 75, 96, 76), 100, 80, minSize = 8)
        assertEquals(8, got.width)
        assertEquals(8, got.height)
        assertTrue(got.right <= 100)
        assertTrue(got.bottom <= 80)
        assertTrue(got.left >= 0)
        assertTrue(got.top >= 0)
    }

    @Test
    fun `零宽高的框被撑到最小尺寸`() {
        val got = ImageMath.clampBox(CropBox(50, 50, 50, 50), 100, 80, minSize = 8)
        assertEquals(8, got.width)
        assertEquals(8, got.height)
    }

    @Test
    fun `最小的框也能被裁出来`() {
        // 这是和「用户在裁剪页手抖拖出一个极小的框」对应的场景：
        // 结果必须是一个合法可裁的框，不能是空框
        val srcW = 60
        val srcH = 40
        val got = ImageMath.clampBox(CropBox(59, 39, 60, 40), srcW, srcH, minSize = 8)
        assertFalse("不该是空框：$got", got.isEmpty)
        assertTrue(got.left >= 0 && got.top >= 0)
        assertTrue(got.right <= srcW && got.bottom <= srcH)
    }

    @Test
    fun `minSize 比图片还大时退回图片尺寸`() {
        val got = ImageMath.clampBox(CropBox(0, 0, 5, 5), 10, 10, minSize = 50)
        assertFalse(got.isEmpty)
        assertTrue(got.right <= 10)
        assertTrue(got.bottom <= 10)
    }

    @Test
    fun `尺寸非法时返回空框`() {
        assertEquals(CropBox(0, 0, 0, 0), ImageMath.clampBox(CropBox(0, 0, 10, 10), 0, 0))
    }

    // ---- EXIF 方向 ----

    @Test
    fun `普通方向不做变换`() {
        assertEquals(ExifTransform(0, false), ImageMath.exifTransform(1))
    }

    @Test
    fun `竖拍的后置摄像头照片要转 90 度`() {
        // 这是最常见的一种：像素是横躺的，靠 EXIF 标记提醒看图的人转过来。
        // 不处理的话 OCR 拿到的就是一张躺着的图
        assertEquals(ExifTransform(90, false), ImageMath.exifTransform(6))
        assertEquals(ExifTransform(270, false), ImageMath.exifTransform(8))
    }

    @Test
    fun `倒置和镜像方向`() {
        assertEquals(ExifTransform(180, false), ImageMath.exifTransform(3))
        assertEquals(ExifTransform(0, true), ImageMath.exifTransform(2))
        assertEquals(ExifTransform(180, true), ImageMath.exifTransform(4))
        assertEquals(ExifTransform(90, true), ImageMath.exifTransform(5))
        assertEquals(ExifTransform(270, true), ImageMath.exifTransform(7))
    }

    @Test
    fun `未知方向按不变换处理`() {
        assertEquals(ExifTransform(0, false), ImageMath.exifTransform(0))
        assertEquals(ExifTransform(0, false), ImageMath.exifTransform(99))
        assertEquals(ExifTransform(0, false), ImageMath.exifTransform(-1))
    }
}
