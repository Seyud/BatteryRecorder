package yangfentuozi.batteryrecorder.server.recorder

import android.app.ActivityManager.RunningTaskInfo
import android.app.IActivityTaskManager
import android.app.ITaskStackListener
import android.app.TaskInfo
import android.app.TaskStackListener
import android.hardware.display.IDisplayManager
import android.hardware.display.IDisplayManagerCallback
import android.os.Handler
import android.os.IPowerManager
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.ServiceManager
import androidx.annotation.Keep
import yangfentuozi.batteryrecorder.server.recorder.sampler.Sampler
import yangfentuozi.batteryrecorder.server.writer.PowerRecordWriter
import yangfentuozi.batteryrecorder.shared.Constants
import yangfentuozi.batteryrecorder.shared.config.ConfigConstants
import yangfentuozi.batteryrecorder.shared.data.LineRecord
import yangfentuozi.batteryrecorder.shared.util.Handlers
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import java.io.IOException

class Monitor(
    private val writer: PowerRecordWriter,
    private val sendBinder: (() -> Unit),
    private val sampler: Sampler
) {
    private val iActivityTaskManager =
        IActivityTaskManager.Stub.asInterface(ServiceManager.getService("activity_task"))
    private val iDisplayManager =
        IDisplayManager.Stub.asInterface(ServiceManager.getService("display"))
    private val iPowerManager = IPowerManager.Stub.asInterface(ServiceManager.getService("power"))
    private val taskStackListener: ITaskStackListener = object : TaskStackListener() {
        @Keep
        override fun onTaskMovedToFront(taskInfo: RunningTaskInfo) {
            onFocusedAppChanged(taskInfo)
        }
    }

    private var displayCallback: IDisplayManagerCallback? = null
    @Volatile
    private var displayCallbackRegistered = false

    @Volatile
    private var currForegroundApp: String? = null

    @Volatile
    private var isInteractive = true

    private val callbacks: RemoteCallbackList<IRecordListener> = RemoteCallbackList()

    @Volatile
    var recordIntervalMs: Long = ConfigConstants.DEF_RECORD_INTERVAL_MS

    @Volatile
    var screenOffRecord: Boolean = ConfigConstants.DEF_SCREEN_OFF_RECORD_ENABLED

    private var mAlwaysPollingScreenStatusEnabled: Boolean = ConfigConstants.DEF_ALWAYS_POLLING_SCREEN_STATUS_ENABLED
    var alwaysPollingScreenStatusEnabled: Boolean
        get() = mAlwaysPollingScreenStatusEnabled
        set(value) {
            val oldValue = mAlwaysPollingScreenStatusEnabled
            if (value != oldValue) {
                mAlwaysPollingScreenStatusEnabled = value
                LoggerX.d<Monitor>("[屏幕] 配置变更: alwaysPollingScreenStatusEnabled $oldValue -> $value")
                if (value) {
                    LoggerX.d<Monitor>("[屏幕] 配置切到轮询模式，清空 DisplayCallback 引用")
                    unregisterDisplayEventCallback()
                } else {
                    LoggerX.d<Monitor>("[屏幕] 配置切到回调模式，注册 DisplayCallback")
                    registerDisplayEventCallback()
                }
            }
        }

    @Volatile
    private var paused = false

    @Volatile
    private var stopped = false
    private val lock = Object()
    private val callbackHandler: Handler
        get() = Handlers.getHandler("CallbackThread")
    private var thread = Thread({
        synchronized(lock) {
            while (!stopped) {
                if (alwaysPollingScreenStatusEnabled && paused && !screenOffRecord) {
                    val oldIsInteractive = isInteractive
                    val latestIsInteractive = iPowerManager.isInteractive
                    if (oldIsInteractive != latestIsInteractive) {
                        LoggerX.d<Monitor>("[轮询] 亮屏状态变化: $oldIsInteractive -> $latestIsInteractive")
                    }
                    isInteractive = latestIsInteractive
                    if (!isInteractive) {
                        lock.wait(recordIntervalMs)
                        continue
                    }
                    LoggerX.d<Monitor>("[采样] 轮询检测到亮屏，恢复采样")
                    paused = false
                }
                try {
                    val timestamp = System.currentTimeMillis()
                    val sample = sampler.sample()
                    val power = sample.voltage * sample.current
                    val status = sample.status
                    val temp = sample.temp
                    if (alwaysPollingScreenStatusEnabled) {
                        val oldIsInteractive = isInteractive
                        val latestIsInteractive = iPowerManager.isInteractive
                        if (oldIsInteractive != latestIsInteractive) {
                            LoggerX.d<Monitor>("[轮询] 亮屏状态变化: $oldIsInteractive -> $latestIsInteractive")
                        }
                        isInteractive = latestIsInteractive
                    }
                    writer.write(
                        LineRecord(
                            timestamp,
                            power,
                            currForegroundApp,
                            sample.capacity,
                            if (isInteractive) 1 else 0,
                            sample.status,
                            sample.temp,
                            sample.voltage,
                            sample.current
                        )
                    )

                    callbackHandler.post {
                        // 回调 app
                        val n: Int = callbacks.beginBroadcast()
                        for (i in 0..<n) {
                            try {
                                callbacks.getBroadcastItem(i)
                                    .onRecord(timestamp, power, status, temp)
                            } catch (e: RemoteException) {
                                LoggerX.e<Monitor>("MonitorThread -> callbackHandler@post: 回调失败", tr = e)
                            }
                        }
                        callbacks.finishBroadcast()
                    }
                } catch (e: IOException) {
                    LoggerX.e<Monitor>("MonitorThread: 读取功耗数据失败", tr = e)
                }

                if (isInteractive || screenOffRecord) {
                    if (paused) {
                        LoggerX.d<Monitor>("[采样] 恢复采样: isInteractive=$isInteractive screenOffRecord=$screenOffRecord")
                    }
                    paused = false
                    lock.wait(recordIntervalMs)
                } else {
                    if (!paused) {
                        if (alwaysPollingScreenStatusEnabled) {
                            LoggerX.d<Monitor>("[采样] 暂停采样，等待轮询亮屏: isInteractive=$isInteractive screenOffRecord=$screenOffRecord")
                        } else {
                            LoggerX.d<Monitor>("[采样] 暂停采样，等待亮屏事件: isInteractive=$isInteractive screenOffRecord=$screenOffRecord")
                        }
                    }
                    paused = true
                    if (alwaysPollingScreenStatusEnabled) {
                        lock.wait(recordIntervalMs)
                    } else {
                        lock.wait()
                    }
                }
            }
        }
        Thread.currentThread().interrupt()
    }, "MonitorThread")

    fun start() {
        LoggerX.d<Monitor>("[屏幕] start: alwaysPollingScreenStatusEnabled=$alwaysPollingScreenStatusEnabled screenOffRecord=$screenOffRecord")
        try {
            iActivityTaskManager.registerTaskStackListener(taskStackListener)
            if (!alwaysPollingScreenStatusEnabled) {
                LoggerX.d<Monitor>("[屏幕] start: 分支命中，注册 DisplayCallback")
                registerDisplayEventCallback()
            }
        } catch (e: RemoteException) {
            throw RuntimeException("start: 注册任务栈监听失败", e)
        }

        try {
            onFocusedAppChanged(iActivityTaskManager.getFocusedRootTaskInfo())
        } catch (e: RemoteException) {
            throw RuntimeException("start: 获取当前焦点任务信息失败", e)
        }
        isInteractive = iPowerManager.isInteractive
        LoggerX.d<Monitor>("[屏幕] start: initial isInteractive=$isInteractive")

        thread.start()
        writer.onChangedCurrRecordsFile = {
            callbackHandler.post {
                // 回调 app
                val n: Int = callbacks.beginBroadcast()
                for (i in 0..<n) {
                    try {
                        callbacks.getBroadcastItem(i).onChangedCurrRecordsFile()
                    } catch (e: RemoteException) {
                        LoggerX.e<Monitor>("writer.onChangedCurrRecordsFile -> callbackHandler@post: 回调失败", tr = e)
                    }
                }
                callbacks.finishBroadcast()
            }
        }
    }

    private fun registerDisplayEventCallback() {
        if (displayCallbackRegistered) {
            LoggerX.v<Monitor>("[屏幕] registerDisplayEventCallback: 已注册，跳过")
            return
        }
        LoggerX.d<Monitor>("[屏幕] registerDisplayEventCallback")
        displayCallback = object : IDisplayManagerCallback.Stub() {
            @Keep
            override fun onDisplayEvent(displayId: Int, event: Int) {
                if (alwaysPollingScreenStatusEnabled) {
                    LoggerX.v<Monitor>("[屏幕] DisplayEvent 已忽略：当前为轮询模式 displayId=$displayId event=$event")
                    return
                }
                val oldIsInteractive = isInteractive
                val latestIsInteractive = iPowerManager.isInteractive
                isInteractive = latestIsInteractive
                LoggerX.d<Monitor>("[屏幕] DisplayEvent: displayId=$displayId event=$event interactive $oldIsInteractive -> $latestIsInteractive paused=$paused")
                if (isInteractive && paused) {
                    LoggerX.d<Monitor>("[采样] 收到亮屏事件，唤醒采样线程")
                    notifyLock()
                }
            }
        }
        iDisplayManager.registerCallback(displayCallback)
        displayCallbackRegistered = true
    }

    /**
     * 自实现注销屏幕事件回调，系统服务端检测进程退出自动处理
     */
    private fun unregisterDisplayEventCallback() {
        LoggerX.v<Monitor>("[屏幕] unregisterDisplayEventCallback: 清空 DisplayCallback 引用")
        displayCallback = null
        displayCallbackRegistered = false
    }

    fun stop() {
        writer.onChangedCurrRecordsFile = null
        stopped = true
        notifyLock()
        try {
            iActivityTaskManager.unregisterTaskStackListener(taskStackListener)
        } catch (e: RemoteException) {
            LoggerX.e<Monitor>( "stop: 注销任务栈监听失败", tr = e)
        }
    }

    fun notifyLock() {
        synchronized(lock) {
            lock.notifyAll()
        }
    }

    fun registerRecordListener(callback: IRecordListener) {
        LoggerX.v<Monitor>("[监听] 注册记录回调")
        callbacks.register(callback)
    }

    fun unregisterRecordListener(callback: IRecordListener) {
        LoggerX.v<Monitor>("[监听] 注销记录回调")
        callbacks.unregister(callback)
    }

    private fun onFocusedAppChanged(taskInfo: TaskInfo) {
        val componentName = taskInfo.topActivity ?: return
        val packageName = componentName.packageName
        if (packageName == Constants.APP_PACKAGE_NAME) {
            LoggerX.d<Monitor>("[前台] 焦点回到 App，尝试重新发送 Binder")
            sendBinder()
        }
        if (currForegroundApp != packageName) {
            LoggerX.d<Monitor>("[前台] 应用切换: ${currForegroundApp} -> $packageName")
        }
        currForegroundApp = packageName
    }
}
