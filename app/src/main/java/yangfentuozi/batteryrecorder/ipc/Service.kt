package yangfentuozi.batteryrecorder.ipc

import android.os.IBinder
import android.os.RemoteException
import yangfentuozi.batteryrecorder.server.IService
import yangfentuozi.batteryrecorder.shared.util.LoggerX
import java.util.concurrent.CopyOnWriteArrayList

object Service {
    @Volatile
    private var mBinder: IBinder? = null
    @Volatile
    private var mService: IService? = null
    private val mDeathRecipient = IBinder.DeathRecipient {
        LoggerX.w<Service>("[BINDER] DeathRecipient 触发，服务断连")
        mBinder = null
        mService = null
        scheduleListeners()
    }
    private val mListener = CopyOnWriteArrayList<ServiceConnection>()

    var binder: IBinder?
        get() = mBinder
        set(value) {
            LoggerX.d<Service>("[BINDER] 设置 binder: valueNull=${value == null}")
            mBinder = value
            mService = null
            try {
                mBinder?.linkToDeath(mDeathRecipient, 0)
                if (value != null) {
                    LoggerX.d<Service>("[BINDER] linkToDeath 成功")
                }
                mService = if (value != null) IService.Stub.asInterface(value) else null
                LoggerX.d<Service>("[BINDER] IService 包装完成: serviceNull=${mService == null}")
            } catch (e: RemoteException) {
                LoggerX.w<Service>("[BINDER] binder linkToDeath 失败", tr = e)
            }
            scheduleListeners()
        }

    var service: IService?
        get() = mService
        set(value) {
            LoggerX.d<Service>("[BINDER] 直接设置 service: valueNull=${value == null}")
            mBinder = null
            mService = null
            try {
                value?.asBinder()?.linkToDeath(mDeathRecipient, 0)
                mBinder = value?.asBinder()
                mService = value
                if (value != null) {
                    LoggerX.d<Service>("[BINDER] service.asBinder linkToDeath 成功")
                }
            } catch (e: RemoteException) {
                LoggerX.w<Service>("[BINDER] service linkToDeath 失败", tr = e)
            }
            scheduleListeners()
        }

    fun addListener(listener: ServiceConnection) {
        if (listener !in mListener) mListener += listener
        LoggerX.v<Service>("[BINDER] addListener: total=${mListener.size}")
        if (mService != null) {
            listener.onServiceConnected()
        } else {
            listener.onServiceDisconnected()
        }
    }

    fun removeListener(listener: ServiceConnection) {
        if (listener in mListener) mListener -= listener
        LoggerX.v<Service>("[BINDER] removeListener: total=${mListener.size}")
    }

    private fun scheduleListeners() {
        val connected = mService != null
        LoggerX.v<Service>("[BINDER] scheduleListeners: connected=$connected listeners=${mListener.size}")
        for (listener in mListener) {
            if (connected) {
                listener.onServiceConnected()
            } else {
                listener.onServiceDisconnected()
            }
        }
    }

    interface ServiceConnection {
        fun onServiceConnected()
        fun onServiceDisconnected()
    }
}
