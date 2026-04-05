package yangfentuozi.batteryrecorder.startup

import android.content.Context
import yangfentuozi.batteryrecorder.shared.util.LoggerX

private const val TAG = "RootServerStarter"

object RootServerStarter {
    object Source {
        const val BOOT = "开机广播"
        const val HOME_BUTTON = "首页按钮"
    }

    fun start(
        context: Context,
        source: String
    ): Boolean {
        val starterPath = "${context.applicationInfo.nativeLibraryDir}/libstarter.so --apk=${context.applicationInfo.sourceDir}"
        val command = "\"$starterPath\""
        LoggerX.i(TAG, "[启动请求] 来源=$source，准备执行 ROOT 启动命令")
        LoggerX.d(TAG, "[启动请求] 来源=$source，命令摘要=$starterPath")
        return try {
            Runtime.getRuntime().exec(
                arrayOf(
                    "su",
                    "-c",
                    command
                )
            )
            LoggerX.i(TAG, "[启动请求] 来源=$source，已发起 ROOT 后台启动命令")
            true
        } catch (e: Throwable) {
            LoggerX.e(TAG, "[启动请求] 来源=$source，发起启动命令失败", tr = e)
            false
        }
    }
}
