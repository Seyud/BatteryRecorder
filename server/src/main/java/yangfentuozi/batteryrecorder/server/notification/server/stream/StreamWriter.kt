package yangfentuozi.batteryrecorder.server.notification.server.stream

import yangfentuozi.batteryrecorder.server.notification.NotificationInfo
import java.io.Closeable
import java.io.DataOutputStream
import java.io.OutputStream

class StreamWriter(
    outputStream: OutputStream
): Closeable {
    private val out = DataOutputStream(outputStream)

    fun write(record: NotificationInfo) {
        // 标志位
        out.writeInt(StreamProtocol.MAGIC)
        // flag
        out.writeInt(StreamProtocol.FLAG_DATA)

        record.writeToDos(dos = out)

        // 刷新一下
        out.flush()
    }

    fun writeClose() {
        // 标志位
        out.writeInt(StreamProtocol.MAGIC)
        // flag
        out.writeInt(StreamProtocol.FLAG_STOP)
    }

    fun writeCancel() {
        // 标志位
        out.writeInt(StreamProtocol.MAGIC)
        // flag
        out.writeInt(StreamProtocol.FLAG_CANCEL)
    }

    /**
     * 下发通知兼容模式配置。
     *
     * @param enabled `true` 表示每次更新通知都新建 Builder；`false` 表示继续复用 Builder。
     * @return 无。
     */
    fun writeCompatibilityModeEnabled(enabled: Boolean) {
        out.writeInt(StreamProtocol.MAGIC)
        out.writeInt(StreamProtocol.FLAG_SET_COMPATIBILITY_MODE)
        out.writeBoolean(enabled)
        out.flush()
    }

    override fun close() {
        out.close()
    }
}
