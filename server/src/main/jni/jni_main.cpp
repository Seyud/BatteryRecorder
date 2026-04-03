#include <jni.h>
#include "power_reader.h"
#include "dump_parser.h"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    if (!register_sysfs_native_methods(env)) return JNI_ERR;
    if (!register_dump_parser_native_methods(env)) return JNI_ERR;

    return JNI_VERSION_1_6;
}