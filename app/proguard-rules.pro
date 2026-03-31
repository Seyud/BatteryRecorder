# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# androidx.window 会通过反射探测设备厂商扩展实现；这些类在编译期并不会随 APK 一起打包。
# R8 在 release shrink 时会把这类可选厂商扩展当成缺失类，需要显式忽略 warning。
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**

# Compose rememberSaveable 会通过 ParcelableSnapshotMutable*State 恢复状态。
# Release 混淆后若 CREATOR 被裁剪，进程在后台被杀后恢复会抛 BadParcelableException。
-keepclassmembers class androidx.compose.runtime.ParcelableSnapshotMutableState {
    public static final android.os.Parcelable$Creator CREATOR;
}

-keepclassmembers class androidx.compose.runtime.ParcelableSnapshotMutableIntState {
    public static final android.os.Parcelable$Creator CREATOR;
}

-keepclassmembers class androidx.compose.runtime.ParcelableSnapshotMutableLongState {
    public static final android.os.Parcelable$Creator CREATOR;
}

-keepclassmembers class androidx.compose.runtime.ParcelableSnapshotMutableFloatState {
    public static final android.os.Parcelable$Creator CREATOR;
}

-keepclassmembers class androidx.compose.runtime.ParcelableSnapshotMutableDoubleState {
    public static final android.os.Parcelable$Creator CREATOR;
}
