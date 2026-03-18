package yangfentuozi.batteryrecorder

import android.app.Application
import yangfentuozi.batteryrecorder.shared.Constants
import yangfentuozi.batteryrecorder.shared.config.ConfigConstants
import yangfentuozi.batteryrecorder.shared.config.ConfigUtil
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import java.io.File

class App: Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(ConfigConstants.PREFS_NAME, MODE_PRIVATE)
        val config = ConfigUtil.getConfigBySharedPreferences(prefs)
        LoggerX.d<App>(
            "[应用] SharedPreferences 配置读取完成: intervalMs=${config.recordIntervalMs} " +
                "screenOffRecord=${config.screenOffRecordEnabled} polling=${config.alwaysPollingScreenStatusEnabled}"
        )
        LoggerX.maxLinesPerFile = config.maxLinesPerFile
        LoggerX.maxHistoryDays = config.maxHistoryDays
        LoggerX.logLevel = config.logLevel
        LoggerX.logDir = File(cacheDir, Constants.APP_LOG_DIR_PATH)
        LoggerX.i<App>(
            "[应用] 日志初始化完成: level=${config.logLevel} dir=${File(cacheDir, Constants.APP_LOG_DIR_PATH).absolutePath} " +
                "maxLines=${config.maxLinesPerFile} maxDays=${config.maxHistoryDays}"
        )
    }
}
