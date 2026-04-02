package yangfentuozi.batteryrecorder.ui.model

import yangfentuozi.batteryrecorder.shared.config.dataclass.UpdateChannel

val UpdateChannel.displayName: String
    get() = when (this) {
        UpdateChannel.Stable -> "稳定版"
        UpdateChannel.Prerelease -> "预发布"
    }
