package yangfentuozi.batteryrecorder.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import yangfentuozi.batteryrecorder.shared.data.RecordsFile

/**
 * 捕获滚动内容区的长截图。
 *
 * @param scrollState 内容区滚动状态；用于逐段滚动并恢复用户原位置。
 * @param viewportSize 当前内容区可见区域尺寸。
 * @param graphicsLayer 当前内容区对应的图层录制对象。
 * @param backgroundColorArgb 最终长图需要填充的背景色。
 * @return 拼接完成的长截图位图。
 */
suspend fun captureLongScreenshot(
    scrollState: ScrollState,
    viewportSize: IntSize,
    graphicsLayer: GraphicsLayer,
    backgroundColorArgb: Int
): Bitmap {
    check(viewportSize.width > 0 && viewportSize.height > 0) {
        "内容区尚未完成布局，无法保存长截图"
    }
    val originalScrollOffset = scrollState.value
    val totalHeight = scrollState.maxValue + viewportSize.height
    val resultBitmap = Bitmap.createBitmap(
        viewportSize.width,
        totalHeight,
        Bitmap.Config.ARGB_8888
    )
    resultBitmap.eraseColor(backgroundColorArgb)
    val canvas = AndroidCanvas(resultBitmap)
    val captureOffsets = buildLongScreenshotOffsets(
        maxScrollOffset = scrollState.maxValue,
        viewportHeight = viewportSize.height
    )
    var drawnUntilY = 0
    try {
        for (offset in captureOffsets) {
            scrollState.scrollTo(offset)
            awaitLongScreenshotFrame()
            val recordedBitmap = graphicsLayer.toImageBitmap().asAndroidBitmap()
            val segmentBitmap = recordedBitmap.copy(Bitmap.Config.ARGB_8888, false)
            try {
                val segmentBottom = min(offset + segmentBitmap.height, totalHeight)
                val drawStartY = maxOf(offset, drawnUntilY)
                if (segmentBottom <= drawStartY) {
                    continue
                }
                val sourceTop = drawStartY - offset
                val sourceBottom = sourceTop + (segmentBottom - drawStartY)
                canvas.drawBitmap(
                    segmentBitmap,
                    Rect(0, sourceTop, segmentBitmap.width, sourceBottom),
                    Rect(0, drawStartY, segmentBitmap.width, segmentBottom),
                    null
                )
                drawnUntilY = segmentBottom
            } finally {
                if (recordedBitmap !== segmentBitmap) {
                    recordedBitmap.recycle()
                }
                segmentBitmap.recycle()
            }
        }
    } finally {
        scrollState.scrollTo(originalScrollOffset)
        awaitLongScreenshotFrame()
    }
    return resultBitmap
}

/**
 * 保存 PNG 位图到系统图片目录。
 *
 * @param context 应用上下文。
 * @param displayName 输出文件名，需包含扩展名。
 * @param bitmap 待保存的位图。
 * @return 成功写入后的媒体库 Uri。
 * @throws IllegalStateException 当媒体库条目或输出流创建失败时抛出。
 */
fun saveBitmapToPictures(
    context: Context,
    displayName: String,
    bitmap: Bitmap
): Uri {
    val resolver = context.contentResolver
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            "Pictures/BatteryRecorder"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val imageUri = resolver.insert(collection, contentValues)
        ?: throw IllegalStateException("创建图片媒体条目失败: $displayName")
    try {
        resolver.openOutputStream(imageUri, "w")?.use { outputStream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                throw IllegalStateException("PNG 编码失败: $displayName")
            }
        } ?: throw IllegalStateException("创建图片输出流失败: $displayName")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(
                imageUri,
                ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                },
                null,
                null
            )
        }
        return imageUri
    } catch (t: Throwable) {
        resolver.delete(imageUri, null, null)
        throw t
    }
}

/**
 * 构建记录详情页长截图的默认文件名。
 *
 * @param recordsFile 当前详情页对应的逻辑记录文件。
 * @return 包含 `.png` 后缀的文件名。
 */
fun buildRecordDetailScreenshotFileName(recordsFile: RecordsFile): String {
    val recordName = recordsFile.name.removeSuffix(".txt")
    return "batteryrecorder-${recordsFile.type.dataDirName}-$recordName.png"
}

/**
 * 生成长截图所需的滚动采样点。
 *
 * @param maxScrollOffset 当前内容区的最大滚动距离。
 * @param viewportHeight 当前内容区可见区域高度。
 * @return 按从上到下顺序排列且去重后的滚动偏移列表。
 */
private fun buildLongScreenshotOffsets(
    maxScrollOffset: Int,
    viewportHeight: Int
): List<Int> {
    if (maxScrollOffset <= 0 || viewportHeight <= 0) {
        return listOf(0)
    }
    val offsets = mutableListOf<Int>()
    var nextOffset = 0
    while (nextOffset < maxScrollOffset) {
        offsets += nextOffset
        nextOffset += viewportHeight
    }
    offsets += maxScrollOffset
    return offsets.distinct()
}

/**
 * 等待 Compose 完成一次滚动后的重绘。
 *
 * @return 无返回值；仅用于保证图层录制的是最新画面。
 */
private suspend fun awaitLongScreenshotFrame() {
    withFrameNanos { }
    withFrameNanos { }
}
