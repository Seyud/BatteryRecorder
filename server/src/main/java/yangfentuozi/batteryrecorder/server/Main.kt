package yangfentuozi.batteryrecorder.server

import android.ddm.DdmHandleAppName
import androidx.annotation.Keep
import yangfentuozi.batteryrecorder.shared.Constants
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import java.io.File
import java.io.IOException

@Keep
object Main {

    @Keep
    @JvmStatic
    fun main(args: Array<String>) {
        LoggerX.i<Main>("[启动] main() 进入，准备初始化 Server")
        DdmHandleAppName.setAppName("battery_recorder", 0)
        // 设置OOM保活
        setSelfOomScoreAdj()
        /* ColorOS 调度使得某些 Soc 的 1 核在息屏后被禁用，会导致某些机型息屏后无法正常记录，故不自行 taskset，全权交由系统调度
        try {
            Runtime.getRuntime().exec(new String[]{"taskset", "-ap", "1", String.valueOf(Os.getpid())});
        } catch (IOException e) {
            Log.e("Main", "Failed to set task affinity", e);
            throw new RuntimeException(e);
        }
        */
        // 指定日志文件夹
        LoggerX.logDirPath = "${Constants.SHELL_DATA_DIR_PATH}/${Constants.SHELL_LOG_DIR_PATH}"
        LoggerX.i<Main>("[启动] 日志目录初始化完成: ${Constants.SHELL_DATA_DIR_PATH}/${Constants.SHELL_LOG_DIR_PATH}")
        LoggerX.d<Main>("[启动] 即将进入 Server 初始化")

        Server()
    }

    private fun setSelfOomScoreAdj() {
        val oomScoreAdjFile = File("/proc/self/oom_score_adj")
        val oomScoreAdjValue = -1000
        try {
            oomScoreAdjFile.writeText("$oomScoreAdjValue\n")
            val actualValue: String = oomScoreAdjFile.readText().trim()
            if (oomScoreAdjValue.toString() != actualValue) {
                LoggerX.e<Main>("[启动] 设置 oom_score_adj 失败，期望 $oomScoreAdjValue，实际 $actualValue")
                return
            }
            LoggerX.i<Main>("[启动] 设置 oom_score_adj 成功，实际 $oomScoreAdjValue")
        } catch (e: IOException) {
            LoggerX.e<Main>("[启动] 设置 oom_score_adj 失败", e)
        } catch (e: RuntimeException) {
            LoggerX.e<Main>("[启动] 设置 oom_score_adj 失败", e)
        }
    }
}
