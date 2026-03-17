package yangfentuozi.batteryrecorder.data.log

import android.content.Context
import android.net.Uri
import yangfentuozi.batteryrecorder.shared.Constants
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object LogRepository {

    /**
     * 导出 app 日志到 ZIP。
     *
     * @param context 应用上下文。
     * @param destinationUri SAF 目标 URI。
     * @return 无返回值。
     */
    @Throws(IOException::class)
    fun exportLogsZip(
        context: Context,
        destinationUri: Uri
    ) {
        val outputStream = context.contentResolver.openOutputStream(destinationUri, "w")
            ?: throw IOException("Failed to open destination: $destinationUri")
        val appLogsDir = File(context.cacheDir, Constants.APP_LOG_DIR_PATH)

        outputStream.use { rawOutput ->
            ZipOutputStream(rawOutput).use { zipOutput ->
                val exportedCount = zipDirectory(zipOutput, appLogsDir, "app")
                if (exportedCount == 0) {
                    throw FileNotFoundException("No log files found")
                }
            }
        }
    }

    /**
     * 将目录中的文件递归写入 ZIP。
     *
     * @param zipOutput ZIP 输出流。
     * @param directory 待打包目录。
     * @param rootEntryName ZIP 内根目录名称。
     * @return 写入的文件数量。
     */
    private fun zipDirectory(
        zipOutput: ZipOutputStream,
        directory: File?,
        rootEntryName: String
    ): Int {
        if (directory == null || !directory.exists() || !directory.isDirectory) {
            return 0
        }
        val basePath = directory.toPath()
        var entryCount = 0
        directory.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val relativePath = basePath.relativize(file.toPath()).toString().replace('\\', '/')
                val entryName = "$rootEntryName/$relativePath"
                zipOutput.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { input ->
                    input.copyTo(zipOutput)
                }
                zipOutput.closeEntry()
                entryCount += 1
            }
        return entryCount
    }
}
