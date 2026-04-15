package yangfentuozi.batteryrecorder.server.notification.server.stream

import yangfentuozi.batteryrecorder.server.notification.NotificationInfo
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

class StreamReader(
    inputStream: InputStream
): Closeable {
    private val input = DataInputStream(inputStream)

    /**
     * 读取下一条通知流消息。
     *
     * @return 成功时返回一条通知流消息；如果还没开始读新帧就到 EOF，则返回 `null`。
     * @throws IOException 当帧读到一半 EOF、数据损坏或协议错误时抛出。
     */
    fun readNext(): NotificationStreamMessage? {
        // 标志位
        val magic = try {
            input.readInt()
        } catch (_: EOFException) {
            // 流正常结束：连新帧的 magic 都没读到
            return null
        }

        if (magic != StreamProtocol.MAGIC) {
            throw IOException(
                "错误标志位: 0x${magic.toUInt().toString(16)}, " +
                        "期望: 0x${StreamProtocol.MAGIC.toUInt().toString(16)}"
            )
        }
        try {
            // flag
            when(val flag = input.readInt()) {
                StreamProtocol.FLAG_DATA ->
                    return NotificationStreamMessage.Data(NotificationInfo.readFromDis(dis = input))
                StreamProtocol.FLAG_STOP -> return NotificationStreamMessage.Stop
                StreamProtocol.FLAG_CANCEL -> return NotificationStreamMessage.CancelNotification
                StreamProtocol.FLAG_SET_COMPATIBILITY_MODE ->
                    return NotificationStreamMessage.SetCompatibilityMode(input.readBoolean())
                else -> throw IOException("无效 flag: $flag")
            }
        } catch (e: EOFException) {
            throw IOException("非预期的 EOF", e)
        }
    }

    override fun close() {
        input.close()
    }
}
