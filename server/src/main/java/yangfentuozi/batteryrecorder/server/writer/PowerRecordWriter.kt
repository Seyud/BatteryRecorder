package yangfentuozi.batteryrecorder.server.writer

import android.os.Handler
import yangfentuozi.batteryrecorder.shared.data.BatteryStatus
import yangfentuozi.batteryrecorder.shared.data.BatteryStatus.Charging
import yangfentuozi.batteryrecorder.shared.data.BatteryStatus.Discharging
import yangfentuozi.batteryrecorder.shared.data.LineRecord
import yangfentuozi.batteryrecorder.shared.util.Handlers
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

class PowerRecordWriter(
    powerDir: File,
    private val fixFileOwner: ((File) -> Unit)
) {
    private val chargeDir = File(powerDir, "charge")
    private val dischargeDir = File(powerDir, "discharge")

    @Volatile
    var lastStatus: BatteryStatus? = null
        private set

    val chargeDataWriter = ChargeDataWriter(chargeDir)
    val dischargeDataWriter = DischargeDataWriter(dischargeDir)

    @Volatile
    var batchSize = 200

    @Volatile
    var flushIntervalMs = 30 * 1000L

    @Volatile
    var maxSegmentDurationMs = 24 * 60 * 60 * 1000L

    @Volatile
    var onChangedCurrRecordsFile: (() -> Unit)? = null

    init {
        fun makeSureExists(file: File) {
            if (!file.exists()) {
                if (!file.mkdirs()) {
                    LoggerX.e<PowerRecordWriter>("[写盘] 创建功率数据目录失败: ${file.absolutePath}")
                    throw IOException("makeSureExists: 创建功率数据文件夹: ${file.absolutePath} 失败")
                }
                LoggerX.d<PowerRecordWriter>("[写盘] 创建功率数据目录: ${file.absolutePath}")
            } else if (!file.isDirectory()) {
                LoggerX.e<PowerRecordWriter>("[写盘] 功率数据路径不是目录: ${file.absolutePath}")
                throw IOException("makeSureExists: 功率数据文件夹: ${file.absolutePath} 不是一个文件夹")
            }
            fixFileOwner(file)
        }
        makeSureExists(powerDir)
        makeSureExists(chargeDir)
        makeSureExists(dischargeDir)
    }

    fun write(record: LineRecord) {
        if (lastStatus != record.status) {
            LoggerX.d<PowerRecordWriter>("[写盘] 电池状态切换: $lastStatus -> ${record.status}")
        }
        when (record.status) {
            Charging -> {
                chargeDataWriter.write(record, lastStatus != Charging)
            }

            Discharging -> {
                dischargeDataWriter.write(record, lastStatus != Discharging)
            }

            else -> {}
        }
        lastStatus = record.status
    }

    fun close() {
        chargeDataWriter.closeCurrentSegment()
        dischargeDataWriter.closeCurrentSegment()
    }

    fun flushBuffer() {
        chargeDataWriter.flushBuffer()
        dischargeDataWriter.flushBuffer()
    }

    inner class ChargeDataWriter(dir: File) : BaseDelayedRecordWriter(dir) {
        override fun needStartNewSegment(justChangedStatus: Boolean, nowTime: Long): Boolean {
            // case1 记录超过最大分段时间（0 表示不按时间分段）
            return (maxSegmentDurationMs > 0 && nowTime - startTime > maxSegmentDurationMs) ||
                    // case2 允许短时间内续接之前记录
                    (justChangedStatus && nowTime - lastTime > 30 * 1000)
        }

        override fun needDeleteSegment(nowTime: Long): Boolean {
            return nowTime - startTime < 1 * 60 * 1000 // 1min
        }
    }

    inner class DischargeDataWriter(dir: File) : BaseDelayedRecordWriter(dir) {
        override fun needStartNewSegment(justChangedStatus: Boolean, nowTime: Long): Boolean {
            // case1 记录超过最大分段时间（0 表示不按时间分段）
            return (maxSegmentDurationMs > 0 && nowTime - startTime > maxSegmentDurationMs) || justChangedStatus
                    // case2 允许短时间内续接之前记录 (暂时禁用)
                    // (justChangedStatus && nowTime - lastTime > 10 * 60 * 1000)
        }

        override fun needDeleteSegment(nowTime: Long): Boolean {
            return nowTime - startTime < 10 * 60 * 1000 // 10min
        }
    }

    abstract inner class BaseDelayedRecordWriter(val dir: File) {
        @Volatile
        var segmentFile: File? = null
            private set
        protected var autoRetryWriter: AutoRetryStringWriter? = null

        protected var startTime: Long = 0L
        protected var lastTime: Long = 0L
        protected var lastChangedStatusTime = 0L

        protected val buffer = StringBuilder(4096)
        protected var batchCount = 0

        protected val handler: Handler = Handlers.getHandler("RecorderWritingThread")
        protected val writingRunnable = Runnable {
            flushBuffer()
            // 防止异步写完被忽略掉
            if (batchCount > 0) {
                postDelayedWriting()
            }
        }

        fun postDelayedWriting() {
            handler.postDelayed(writingRunnable, flushIntervalMs)
        }

        fun write(
            record: LineRecord,
            justChangedStatus: Boolean
        ) {

            // 选择性丢弃一些干扰数据
            if (justChangedStatus) lastChangedStatusTime = record.timestamp
            if (record.timestamp - lastChangedStatusTime < 2 * 1000L) {
                if ((record.power > 0) != (record.status == Discharging)) {
                    if (justChangedStatus) {
                        closeCurrentSegment()
                    }
                    LoggerX.v<BaseDelayedRecordWriter>("[写盘] 跳过状态切换瞬时干扰数据: dir=${dir.name}")
                    return
                }
            }

            val startedNewSegment = startNewSegmentIfNeed(justChangedStatus)
            lastTime = record.timestamp

            buffer.append(record).append("\n")
            batchCount++

            if (startedNewSegment) {
                LoggerX.d<BaseDelayedRecordWriter>("[写盘] 新分段已创建，立即落盘: file=${segmentFile?.name}")
                flushBuffer()
                if (handler.hasCallbacks(writingRunnable)) {
                    handler.removeCallbacks(writingRunnable)
                }
                return
            }

            if (batchCount >= batchSize) {
                flushBuffer()
                if (handler.hasCallbacks(writingRunnable)) {
                    handler.removeCallbacks(writingRunnable)
                }
            } else {
                if (!handler.hasCallbacks(writingRunnable)) {
                    postDelayedWriting()
                }
            }
            if (justChangedStatus) {
                LoggerX.d<BaseDelayedRecordWriter>("[写盘] 当前记录文件已切换: file=${segmentFile?.name}")
                onChangedCurrRecordsFile?.invoke()
            }
        }

        private fun startNewSegmentIfNeed(
            justChangedStatus: Boolean
        ): Boolean {
            val nowTime = System.currentTimeMillis()
            if (needStartNewSegment(justChangedStatus, nowTime) ||
                // case 还没记录过
                autoRetryWriter == null
            ) {
                // 关闭之前的记录，打开新的
                closeCurrentSegment()
                startTime = nowTime
                val fileName = "$nowTime.txt"
                val file = File(dir, fileName)
                segmentFile = file
                LoggerX.d<BaseDelayedRecordWriter>(
                    "[写盘] 创建新分段: dir=${dir.name} file=${file.name} justChangedStatus=$justChangedStatus"
                )

                val openOutputStream: (() -> OutputStream) = {
                    if (!file.exists() && !file.createNewFile()) {
                        throw IOException("@openOutputStream: 创建分段文件: ${file.absolutePath} 失败")
                    }
                    fixFileOwner(file)
                    FileOutputStream(file, true)
                }
                autoRetryWriter = AutoRetryStringWriter(
                    openOutputStream(),
                    3,
                    1000,
                    openOutputStream
                )
                return true
            }
            return false
        }

        abstract fun needStartNewSegment(
            justChangedStatus: Boolean,
            nowTime: Long = System.currentTimeMillis()
        ): Boolean

        abstract fun needDeleteSegment(
            nowTime: Long = System.currentTimeMillis()
        ): Boolean

        fun flushBuffer() {
            if (batchCount == 0 || autoRetryWriter == null) return
            LoggerX.d<BaseDelayedRecordWriter>("[写盘] flushBuffer: dir=${dir.name} batchCount=$batchCount file=${segmentFile?.name}")
            autoRetryWriter!!.write(buffer)
            buffer.setLength(0) // 清空 StringBuilder
            batchCount = 0
        }

        fun closeCurrentSegment() {
            flushBuffer()
            if (autoRetryWriter != null) {
                try {
                    autoRetryWriter!!.close()
                } catch (e: IOException) {
                    LoggerX.e<BaseDelayedRecordWriter>("[写盘] 关闭分段文件失败", tr = e)
                }
                autoRetryWriter = null
                if (needDeleteSegment(System.currentTimeMillis())) {
                    LoggerX.v<BaseDelayedRecordWriter>("[写盘] 删除短分段: file=${segmentFile?.name}")
                    segmentFile!!.delete()
                }
                segmentFile = null
            }
        }

        fun getCurrFile(
            justChangedStatus: Boolean
        ): File? {
            if (needStartNewSegment(justChangedStatus)) closeCurrentSegment()
            return segmentFile
        }
    }
}
