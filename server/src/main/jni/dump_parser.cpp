#include <jni.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "dump_parser.h"

#define TAG "DumpParserJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static char to_lower_ascii(char c) {
    if (c >= 'A' && c <= 'Z') return (char) (c - 'A' + 'a');
    return c;
}

static const char *find_ignore_case(const char *haystack, const char *needle) {
    if (!haystack || !needle) return nullptr;
    const size_t needleLen = strlen(needle);
    if (needleLen == 0) return haystack;

    for (const char *p = haystack; *p; p++) {
        size_t i = 0;
        for (; i < needleLen; i++) {
            const char hc = p[i];
            if (!hc) break;
            if (to_lower_ascii(hc) != to_lower_ascii(needle[i])) break;
        }
        if (i == needleLen) return p;
    }
    return nullptr;
}

static bool contains_ignore_case(const char *haystack, const char *needle) {
    return find_ignore_case(haystack, needle) != nullptr;
}

static bool starts_with_ignore_case(const char *s, const char *prefix) {
    if (!s || !prefix) return false;
    for (size_t i = 0; prefix[i]; i++) {
        if (!s[i]) return false;
        if (to_lower_ascii(s[i]) != to_lower_ascii(prefix[i])) return false;
    }
    return true;
}

static const char *trim_left(const char *s) {
    while (*s == ' ' || *s == '\t') s++;
    return s;
}

// 仅在行首匹配 key（去掉前导空白），避免把 "xxxVoltage..." 之类的字段误判成目标 key。
static bool parse_line_key_long(const char *line, const char *key, long *outValue) {
    const char *s = trim_left(line);
    if (!starts_with_ignore_case(s, key)) return false;
    s += strlen(key);

    while (*s == ' ' || *s == '\t') s++;
    if (*s != ':' && *s != '=') return false;
    s++;
    while (*s == ' ' || *s == '\t') s++;

    // 与 Kotlin 的 toLongOrNull 更接近：必须以数字/负号开头；遇到非数字立即停止。
    if ((*s < '0' || *s > '9') && *s != '-') return false;

    char *endPtr = nullptr;
    const long value = strtol(s, &endPtr, 10);
    if (endPtr == s) return false;

    *outValue = value;
    return true;
}

static void parse_dump_from_fd(int fd, long *outVoltageMv, long *outTemperature) {
    long voltageMv = 0;
    long temperature = 0;
    long fallbackVoltageMv = 0;
    long fallbackTemperature = 0;
    bool fallbackFoundVoltage = false;
    bool fallbackFoundTemp = false;
    bool foundVoltage = false;
    bool foundTemp = false;
    bool sawHeader = false;
    bool inBatteryState = false;

    const int dupFd = dup(fd);
    if (dupFd < 0) {
        *outVoltageMv = 0;
        *outTemperature = 0;
        return;
    }

    FILE *fp = fdopen(dupFd, "r");
    if (!fp) {
        close(dupFd);
        *outVoltageMv = 0;
        *outTemperature = 0;
        return;
    }

    char *line = nullptr;
    size_t cap = 0;
    while (getline(&line, &cap, fp) != -1) {
        if (!sawHeader && contains_ignore_case(line, "Current Battery Service state:")) {
            // 与 Kotlin 逻辑一致：优先使用该 section 下的字段。
            sawHeader = true;
            inBatteryState = true;
            foundVoltage = false;
            foundTemp = false;
            voltageMv = 0;
            temperature = 0;
            continue;
        }

        bool *targetFoundVoltage = sawHeader ? &foundVoltage : &fallbackFoundVoltage;
        bool *targetFoundTemp = sawHeader ? &foundTemp : &fallbackFoundTemp;
        long *targetVoltage = sawHeader ? &voltageMv : &fallbackVoltageMv;
        long *targetTemp = sawHeader ? &temperature : &fallbackTemperature;

        if (!sawHeader || inBatteryState) {
            if (!(*targetFoundVoltage) && parse_line_key_long(line, "voltage", targetVoltage)) {
                *targetFoundVoltage = true;
            }
            if (!(*targetFoundTemp)) {
                if (parse_line_key_long(line, "temperature", targetTemp)) {
                    *targetFoundTemp = true;
                }
            }
        }

        // 为了避免提前停止读取导致写端阻塞，继续读到 EOF（只是不再做额外解析）。
        if (foundVoltage && foundTemp) {
            // no-op
        }
    }

    free(line);
    fclose(fp); // 会同时关闭 dupFd

    if (sawHeader) {
        *outVoltageMv = voltageMv;
        *outTemperature = temperature;
    } else {
        *outVoltageMv = fallbackVoltageMv;
        *outTemperature = fallbackTemperature;
    }
}

static jlongArray make_result(JNIEnv *env, long voltageMv, long temperature) {
    jlongArray result = env->NewLongArray(2);
    if (!result) return nullptr;
    const jlong values[2] = {static_cast<jlong>(voltageMv), static_cast<jlong>(temperature)};
    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

static jlongArray native_parse_battery_dump_pfd(
        JNIEnv *env,
        jobject /* thiz */,
        jobject parcelFileDescriptor) {
    if (!parcelFileDescriptor) {
        return make_result(env, 0, 0);
    }
    jclass pfdClass = env->GetObjectClass(parcelFileDescriptor);
    if (!pfdClass) {
        LOGE("%s: GetObjectClass(parcelFileDescriptor) failed", __func__);
        return make_result(env, 0, 0);
    }
    jmethodID getFdMethod = env->GetMethodID(pfdClass, "getFd", "()I");
    if (!getFdMethod) {
        LOGE("%s: GetMethodID(getFd) failed", __func__);
        env->DeleteLocalRef(pfdClass);
        return make_result(env, 0, 0);
    }
    const jint fd = env->CallIntMethod(parcelFileDescriptor, getFdMethod);
    if (env->ExceptionCheck()) {
        LOGE("%s: CallIntMethod(getFd) threw exception", __func__);
        env->ExceptionClear();
        env->DeleteLocalRef(pfdClass);
        return make_result(env, 0, 0);
    }
    env->DeleteLocalRef(pfdClass);
    if (fd < 0) {
        return make_result(env, 0, 0);
    }
    long voltageMv = 0;
    long temperature = 0;
    parse_dump_from_fd(fd, &voltageMv, &temperature);
    return make_result(env, voltageMv, temperature);
}

int register_dump_parser_native_methods(JNIEnv *env) {
    const char *class_name =
            "yangfentuozi/batteryrecorder/server/recorder/sampler/DumpsysSampler";
    jclass clazz = env->FindClass(class_name);
    if (!clazz) {
        LOGE("%s: Failed to find class %s", __func__, class_name);
        return JNI_FALSE;
    }
    static const JNINativeMethod methods[] = {
            {
                    "nativeParseBatteryDumpPfd",
                    "(Landroid/os/ParcelFileDescriptor;)[J",
                    (void *) native_parse_battery_dump_pfd
            },
    };
    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) < 0) {
        LOGE("%s: RegisterNatives failed for %s", __func__, class_name);
        env->DeleteLocalRef(clazz);
        return JNI_FALSE;
    }
    env->DeleteLocalRef(clazz);
    return JNI_TRUE;
}
