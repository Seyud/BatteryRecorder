package yangfentuozi.batteryrecorder.server.sampler

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Build
import android.system.Os
import androidx.annotation.Keep
import yangfentuozi.batteryrecorder.shared.data.BatteryStatus
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

@Keep
object SysfsSampler: Sampler() {

    private const val TAG = "SysfsSampler"
    private const val VOLTAGE_NOW_PATH = "/sys/class/power_supply/battery/voltage_now"
    private const val CURRENT_NOW_PATH = "/sys/class/power_supply/battery/current_now"
    private const val CAPACITY_PATH = "/sys/class/power_supply/battery/capacity"
    private const val STATUS_PATH = "/sys/class/power_supply/battery/status"
    private const val TEMP_PATH = "/sys/class/power_supply/battery/temp"

    @JvmStatic
    external fun nativeInit(): Int

    @JvmStatic
    external fun nativeGetVoltage(): Long

    @JvmStatic
    external fun nativeGetCurrent(): Long

    @JvmStatic
    external fun nativeGetCapacity(): Int

    @JvmStatic
    external fun nativeGetStatus(): Int

    @JvmStatic
    external fun nativeGetTemp(): Int

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun init(appInfo: ApplicationInfo): Boolean {
        try {
            val libraryTmpPath = "/data/local/tmp/libbatteryrecorder.so"
            runCatching { Os.remove(libraryTmpPath) }
            val apk = ZipFile(appInfo.sourceDir)
            apk.getInputStream(apk.getEntry("lib/${Build.SUPPORTED_ABIS[0]}/libbatteryrecorder.so"))
                .copyTo(out = FileOutputStream(libraryTmpPath, false))
            File(libraryTmpPath).apply {
                deleteOnExit()
            }
            Os.chmod(libraryTmpPath, "400".toInt(8))
            System.load(libraryTmpPath)
            LoggerX.i(TAG, "init: JNI 库加载成功, path=$libraryTmpPath")
            val initResult = nativeInit() == 1
            if (initResult) {
                LoggerX.i(TAG, "init: nativeInit() 成功")
            } else {
                LoggerX.w(TAG, "init: nativeInit() 返回失败, fallback DumpsysSampler")
            }
            return initResult
        } catch (e: Throwable) {
            LoggerX.w(TAG, "init: 加载 JNI 失败, fallback DumpsysSampler", tr = e)
            return false
        }
    }

    override fun sample(): BatteryData {
        val rawVoltage = nativeGetVoltage()
        val rawCurrent = nativeGetCurrent()
        val rawCapacity = nativeGetCapacity()
        val rawStatus = nativeGetStatus()
        val rawTemp = nativeGetTemp()
        val batteryData = BatteryData(
            voltage = normalizeVoltageToMicroVolt(rawVoltage),
            current = rawCurrent,
            capacity = rawCapacity,
            status = when (rawStatus.toChar()) {
                'C' -> BatteryStatus.Charging
                'D' -> BatteryStatus.Discharging
                'N' -> BatteryStatus.NotCharging
                'F' -> BatteryStatus.Full
                else -> BatteryStatus.Unknown
            },
            temp = rawTemp
        )
        if (LoggerX.isLoggable(LoggerX.LogLevel.Debug)) {
            LoggerX.d(
                TAG,
                "sample: 原始 sysfs voltage_now=%s current_now=%s capacity=%s status=%s temp=%s; 解析后 voltage=%d current=%d capacity=%d status=%s temp=%d",
                readRawSysfsValue(VOLTAGE_NOW_PATH),
                readRawSysfsValue(CURRENT_NOW_PATH),
                readRawSysfsValue(CAPACITY_PATH),
                readRawSysfsValue(STATUS_PATH),
                readRawSysfsValue(TEMP_PATH),
                batteryData.voltage,
                batteryData.current,
                batteryData.capacity,
                batteryData.status,
                batteryData.temp
            )
        }
        return batteryData
    }

    private fun readRawSysfsValue(path: String): String {
        return runCatching { File(path).readText().trim() }
            .getOrElse { error ->
                "<读取失败:${error.javaClass.simpleName}:${error.message}>"
            }
    }
}
