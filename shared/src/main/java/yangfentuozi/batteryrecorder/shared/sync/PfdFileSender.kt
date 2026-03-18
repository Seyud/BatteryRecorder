package yangfentuozi.batteryrecorder.shared.sync

import android.os.ParcelFileDescriptor
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path

object PfdFileSender {

    fun sendFile(
        writePfd: ParcelFileDescriptor,
        file: File,
        callback: ((File) -> Unit)? = null
    ) {
        val basePath = file.toPath()
        LoggerX.i<PfdFileSender>("[SYNC] 开始发送文件: base=${file.absolutePath}")
        var sentCount = 0
        var sentBytes = 0L
        ParcelFileDescriptor.AutoCloseOutputStream(writePfd).use { raw ->
            BufferedOutputStream(raw, SyncConstants.BUF_SIZE).use { out ->
                sendFileInner(out, file, basePath, callback) { size ->
                    sentCount += 1
                    sentBytes += size
                }
                // 结束码
                out.write(SyncConstants.CODE_FINISHED)
                out.flush()
            }
        }
        LoggerX.i<PfdFileSender>("[SYNC] 文件发送完成: count=$sentCount bytes=$sentBytes")
    }

    private fun sendFileInner(
        out: OutputStream,
        file: File,
        basePath: Path,
        callback: ((File) -> Unit)?,
        onSent: (Long) -> Unit
    ) {
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach {
                sendFileInner(out, it, basePath, callback, onSent)
            }
        } else {
            val size = file.length()
            if (size < 0) throw IOException("Invalid file size: $size")

            // 文件识别码
            out.write(SyncConstants.CODE_FILE)

            // 基于 basePath 的文件路径 (UTF-8)
            out.write(basePath.relativize(file.toPath()).toString().toByteArray(Charsets.UTF_8))

            // 00位
            out.write(SyncConstants.CODE_DELIM)

            // 文件大小 (ASCII 十进制)
            out.write(size.toString().toByteArray(Charsets.US_ASCII))

            // 00位
            out.write(SyncConstants.CODE_DELIM)

            // 文件内容：size: Long 字节
            BufferedInputStream(FileInputStream(file), SyncConstants.BUF_SIZE).use { fis ->
                val buf = ByteArray(SyncConstants.BUF_SIZE)
                var remaining = size
                while (remaining > 0) {
                    val toRead = minOf(remaining, buf.size.toLong()).toInt()
                    val n = fis.read(buf, 0, toRead)
                    if (n < 0) throw EOFException("Unexpected EOF reading file: ${file.absolutePath}")
                    out.write(buf, 0, n)
                    remaining -= n.toLong()
                }
            }
            out.flush()

            LoggerX.d<PfdFileSender>("[SYNC] 发送文件: relative=${basePath.relativize(file.toPath())} size=$size")
            onSent(size)
            callback?.invoke(file)
        }
    }
}
