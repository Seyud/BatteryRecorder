package yangfentuozi.batteryrecorder.startup

import android.content.Context
import yangfentuozi.batteryrecorder.shared.util.LoggerX

object RootServerStarter {
    object Source {
        const val BOOT = "开机广播"
        const val HOME_BUTTON = "首页按钮"
    }

    fun start(
        context: Context,
        source: String
    ): Boolean {
        val command =
            "CLASSPATH=$(pm path yangfentuozi.batteryrecorder | cut -d: -f2) setsid app_process /system/bin yangfentuozi.batteryrecorder.server.Main </dev/null >/dev/null 2>&1 &"
        LoggerX.i<RootServerStarter>("[启动请求] 来源=$source，准备执行 ROOT 启动命令")
        LoggerX.d<RootServerStarter>("[启动请求] 来源=$source，命令摘要=setsid app_process /system/bin yangfentuozi.batteryrecorder.server.Main")
        return try {
            Runtime.getRuntime().exec(
                arrayOf(
                    "su",
                    "-c",
                    command
                )
            )
            LoggerX.i<RootServerStarter>("[启动请求] 来源=$source，已发起 ROOT 后台启动命令")
            true
        } catch (e: Throwable) {
            LoggerX.e<RootServerStarter>("[启动请求] 来源=$source，发起启动命令失败", tr = e)
            false
        }
    }
}
