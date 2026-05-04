package yangfentuozi.batteryrecorder.server.notification.server.stream

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import yangfentuozi.batteryrecorder.server.notification.NotificationInfo
import yangfentuozi.batteryrecorder.shared.config.dataclass.ServerSettings
import java.io.Closeable
import java.io.DataOutputStream
import java.io.OutputStream

class StreamWriter(
    outputStream: OutputStream
) : Closeable {
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

    @OptIn(ExperimentalSerializationApi::class)
    fun writeSettings(settings: ServerSettings) {
        // 标志位
        out.writeInt(StreamProtocol.MAGIC)
        // flag
        out.writeInt(StreamProtocol.FLAG_SETTINGS)

        val bytes = ProtoBuf.encodeToByteArray(settings)
        out.writeInt(bytes.size)
        out.write(bytes)

        out.flush()
    }

    override fun close() {
        out.close()
    }
}
