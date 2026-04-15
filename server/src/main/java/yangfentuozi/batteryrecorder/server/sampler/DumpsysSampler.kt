package yangfentuozi.batteryrecorder.server.sampler

import android.os.BatteryManager
import android.os.BatteryProperty
import android.os.IBatteryPropertiesRegistrar
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import androidx.annotation.Keep
import yangfentuozi.batteryrecorder.shared.data.BatteryStatus
import yangfentuozi.batteryrecorder.shared.util.LoggerX

@Keep
class DumpsysSampler : Sampler() {

    private val tag = "DumpsysSampler"

    private val batteryService = ServiceManager.getService("battery")
    private var registrar: IBatteryPropertiesRegistrar =
        IBatteryPropertiesRegistrar.Stub.asInterface(
            ServiceManager.getService("batteryproperties")
        )

    private val prop = BatteryProperty()

    private external fun nativeParseBatteryDumpPfd(pfd: ParcelFileDescriptor): LongArray

    init {
        LoggerX.d(tag, "init: 启用 Dumpsys 回退采样器")
    }

    private var printedWarning = false

    override fun sample(): BatteryData {
        val pipe = ParcelFileDescriptor.createPipe()

        val readSide = pipe[0]
        val writeSide = pipe[1]

        // 执行 dump
        Thread {
            try {
                batteryService.dump(writeSide.fileDescriptor, arrayOf())
            } catch (e: Exception) {
                LoggerX.e(tag, "@dumpThread: dump 失败", tr = e)
            } finally {
                writeSide.close()
            }
        }.start()

        var flag = false
        var voltage: Long = 0
        var current: Long = 0
        var rawCurrentNow: Long = 0
        var capacity = 0
        var rawCapacity: Long = 0
        var status: BatteryStatus = BatteryStatus.Unknown
        var rawStatus: Long = 0
        var temp = 0
        var rawVoltageLine: String? = null
        var rawTemperatureLine: String? = null
        var readSideAutoClosed = false
        try {
            registrar.getProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, prop)
            rawCurrentNow = prop.long
            current = rawCurrentNow
            registrar.getProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, prop)
            rawCapacity = prop.long
            capacity = rawCapacity.toInt()
            registrar.getProperty(BatteryManager.BATTERY_PROPERTY_STATUS, prop)
            rawStatus = prop.long
            status = BatteryStatus.fromValue(rawStatus.toInt())

            try {
                val result = nativeParseBatteryDumpPfd(readSide)
                voltage = result.getOrNull(0) ?: 0
                temp = (result.getOrNull(1) ?: 0).toInt()
            } catch (e: UnsatisfiedLinkError) {
                if (!printedWarning) {
                    LoggerX.d(tag, "sample: JNI 未加载，回退 Kotlin 解析 dump 输出流", tr = e)
                    printedWarning = true
                }
                ParcelFileDescriptor.AutoCloseInputStream(readSide).bufferedReader().use { reader ->
                    var line: String?
                    while ((reader.readLine().also { line = it }) != null) {
                        if (line != null) if (flag) {
                            when {
                                line.contains("voltage:") -> {
                                    rawVoltageLine = line.trim()
                                    line.substringAfter(": ").trim().toLongOrNull().let {
                                        if (it != null) voltage = it
                                    }
                                }

                                line.contains("temperature:") -> {
                                    rawTemperatureLine = line.trim()
                                    line.substringAfter(": ").trim().toIntOrNull().let {
                                        if (it != null) temp = it
                                    }
                                }
                            }
                        } else if (line.contains("Current Battery Service state:")) flag = true
                    }
                }
                readSideAutoClosed = true
            }
        } catch (e: Exception) {
            LoggerX.e(tag, "sample: 读取 dump 输出流失败", tr = e)
        } finally {
            if (!readSideAutoClosed) {
                try {
                    readSide.close()
                } catch (e: Exception) {
                    LoggerX.w(tag, "sample: 关闭 readSide 失败", tr = e)
                }
            }
        }
        if (LoggerX.isLoggable(LoggerX.LogLevel.Debug) &&
            (rawVoltageLine == null || rawTemperatureLine == null)
        ) {
            captureRawDumpLinesForDebug()?.let { dumpLines ->
                if (rawVoltageLine == null) rawVoltageLine = dumpLines.first
                if (rawTemperatureLine == null) rawTemperatureLine = dumpLines.second
            }
        }
        val batteryData = BatteryData(
            // dumpsys 电压一定是 sysfs 电压除以 1000
            voltage = normalizeVoltageToMicroVolt(voltage * 1000),
            current = current,
            capacity = capacity,
            status = status,
            temp = temp
        )
        if (LoggerX.isLoggable(LoggerX.LogLevel.Debug)) {
            LoggerX.d(
                tag,
                "sample: 原始 batteryproperties current_now=%d capacity=%d status=%d; 原始 dumpsys voltageLine=%s temperatureLine=%s; 解析后 voltage=%d current=%d capacity=%d status=%s temp=%d",
                rawCurrentNow,
                rawCapacity,
                rawStatus,
                rawVoltageLine ?: "<未命中>",
                rawTemperatureLine ?: "<未命中>",
                batteryData.voltage,
                batteryData.current,
                batteryData.capacity,
                batteryData.status,
                batteryData.temp
            )
        }
        return batteryData
    }

    private fun captureRawDumpLinesForDebug(): Pair<String?, String?>? {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        Thread {
            try {
                batteryService.dump(writeSide.fileDescriptor, arrayOf())
            } catch (e: Exception) {
                LoggerX.w(tag, "captureRawDumpLinesForDebug: dump 失败", tr = e)
            } finally {
                writeSide.close()
            }
        }.start()
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(readSide).bufferedReader().use { reader ->
                val dumpText = reader.readText()
                dumpText.lineSequence().firstOrNull { it.contains("voltage:") } to
                    dumpText.lineSequence().firstOrNull { it.contains("temperature:") }
            }
        } catch (e: Exception) {
            LoggerX.w(tag, "captureRawDumpLinesForDebug: 读取 dump 失败", tr = e)
            null
        } finally {
            try {
                readSide.close()
            } catch (_: Exception) {
            }
        }
    }
}
