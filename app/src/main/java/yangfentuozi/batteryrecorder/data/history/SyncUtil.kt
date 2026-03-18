package yangfentuozi.batteryrecorder.data.history

import android.content.Context
import yangfentuozi.batteryrecorder.ipc.Service
import yangfentuozi.batteryrecorder.shared.Constants
import yangfentuozi.batteryrecorder.shared.sync.PfdFileReceiver
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import java.io.File

object SyncUtil {
    fun sync(context: Context) {
        val service = Service.service ?: run {
            LoggerX.w<SyncUtil>("[SYNC] 服务未连接，跳过同步")
            return
        }
        LoggerX.i<SyncUtil>("[SYNC] 开始从服务端拉取记录文件")
        val readPfd = service.sync() ?: run {
            LoggerX.w<SyncUtil>("[SYNC] 服务端未返回同步管道")
            return
        }
        val outDir = File(context.dataDir, Constants.APP_POWER_DATA_PATH)

        try {
            PfdFileReceiver.receiveToDir(readPfd, outDir)
            LoggerX.i<SyncUtil>("[SYNC] 客户端接收完成: ${outDir.absolutePath}")
        } catch (e: Exception) {
            LoggerX.e<SyncUtil>("[SYNC] 客户端接收失败", tr = e)
        }
    }

}
