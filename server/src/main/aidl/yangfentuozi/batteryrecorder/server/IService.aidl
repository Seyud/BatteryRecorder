package yangfentuozi.batteryrecorder.server;

import yangfentuozi.batteryrecorder.server.recorder.IRecordListener;
import yangfentuozi.batteryrecorder.shared.config.dataclass.ServerSettings;
import yangfentuozi.batteryrecorder.shared.data.RecordsFile;

interface IService {
    void restartServer(String nativeLibraryDir) = 0;
    void stopServer() = 1;
    int getVersion() = 2;

    RecordsFile getCurrRecordsFile() = 10;

    void registerRecordListener(IRecordListener listener) = 100;
    void unregisterRecordListener(IRecordListener listener) = 101;

    void syncSettings(in ServerSettings config) = 200;

    ParcelFileDescriptor sync() = 300;

    // 专用日志导出接口，不复用记录文件同步的删除语义。
    ParcelFileDescriptor exportLogs() = 301;
}
