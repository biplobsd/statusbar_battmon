#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include <cstring>
#include <cstdio>
#include <vector>
#include "zygisk.hpp"
#include "battmon_dex.h"

#define LOG_TAG "BattMonNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using zygisk::Api;
using zygisk::AppSpecializeArgs;

static void* InitWorker(void* arg) {
    JavaVM* vm = reinterpret_cast<JavaVM*>(arg);
    JNIEnv* env = nullptr;
    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("Failed to attach worker thread to JVM");
        return nullptr;
    }

    LOGI("InitWorker running inside com.android.systemui (pid=%d)", getpid());

    jclass actThreadClass = nullptr;
    jmethodID currentAppMethod = nullptr;
    jobject appObj = nullptr;

    // Retry loop waiting for ActivityThread.currentApplication() to become ready
    for (int i = 0; i < 120; i++) {
        usleep(100000); // 100ms
        if (!actThreadClass) {
            actThreadClass = env->FindClass("android/app/ActivityThread");
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                actThreadClass = nullptr;
                continue;
            }
        }
        if (!currentAppMethod && actThreadClass) {
            currentAppMethod = env->GetStaticMethodID(actThreadClass, "currentApplication", "()Landroid/app/Application;");
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                currentAppMethod = nullptr;
                continue;
            }
        }
        if (currentAppMethod && actThreadClass) {
            appObj = env->CallStaticObjectMethod(actThreadClass, currentAppMethod);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                appObj = nullptr;
            }
            if (appObj != nullptr) {
                break;
            }
        }
    }

    if (!appObj) {
        LOGE("Timed out waiting for Application instance");
        vm->DetachCurrentThread();
        return nullptr;
    }

    // Determine DEX source: dynamic file on disk if present, else embedded fallback
    const char* dynamic_dex_path = "/data/local/tmp/battmon/classes.dex";
    std::vector<unsigned char> file_buf;
    const unsigned char* dex_bytes = battmon_dex;
    size_t dex_size = battmon_dex_len;

    FILE* df = fopen(dynamic_dex_path, "rb");
    if (df) {
        fseek(df, 0, SEEK_END);
        long sz = ftell(df);
        fseek(df, 0, SEEK_SET);
        if (sz > 0) {
            file_buf.resize(sz);
            if (fread(file_buf.data(), 1, sz, df) == static_cast<size_t>(sz)) {
                dex_bytes = file_buf.data();
                dex_size = static_cast<size_t>(sz);
                LOGI("Loaded dynamic DEX from %s (%zu bytes)", dynamic_dex_path, dex_size);
            }
        }
        fclose(df);
    }

    if (dex_bytes == battmon_dex) {
        LOGI("Loading embedded fallback DEX (%zu bytes)...", dex_size);
    }

    jclass appClass = env->GetObjectClass(appObj);
    jmethodID getClMethod = env->GetMethodID(appClass, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject appCl = env->CallObjectMethod(appObj, getClMethod);
    if (!appCl) {
        LOGE("Failed to get ClassLoader from Application");
        vm->DetachCurrentThread();
        return nullptr;
    }

    jobject byteBuffer = env->NewDirectByteBuffer(const_cast<unsigned char*>(dex_bytes), static_cast<jlong>(dex_size));
    if (!byteBuffer) {
        LOGE("Failed to create DirectByteBuffer for DEX");
        vm->DetachCurrentThread();
        return nullptr;
    }

    jclass dexLoaderClass = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (!dexLoaderClass) {
        LOGE("dalvik.system.InMemoryDexClassLoader class not found");
        vm->DetachCurrentThread();
        return nullptr;
    }

    jmethodID dexLoaderCtor = env->GetMethodID(dexLoaderClass, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    jobject dexLoader = env->NewObject(dexLoaderClass, dexLoaderCtor, byteBuffer, appCl);
    if (env->ExceptionCheck()) {
        LOGE("Exception instantiating InMemoryDexClassLoader");
        env->ExceptionDescribe();
        env->ExceptionClear();
        vm->DetachCurrentThread();
        return nullptr;
    }

    jclass clClass = env->FindClass("java/lang/ClassLoader");
    jmethodID loadClassMethod = env->GetMethodID(clClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring className = env->NewStringUTF("com.battmon.BattMon");
    jclass battMonClass = reinterpret_cast<jclass>(env->CallObjectMethod(dexLoader, loadClassMethod, className));
    env->DeleteLocalRef(className);

    if (env->ExceptionCheck() || !battMonClass) {
        LOGE("Failed to load com.battmon.BattMon via InMemoryDexClassLoader");
        env->ExceptionDescribe();
        env->ExceptionClear();
        vm->DetachCurrentThread();
        return nullptr;
    }

    jmethodID initMethod = env->GetStaticMethodID(battMonClass, "init", "(Landroid/content/Context;)V");
    if (!initMethod) {
        LOGE("com.battmon.BattMon.init(Context) method not found");
        vm->DetachCurrentThread();
        return nullptr;
    }

    env->CallStaticVoidMethod(battMonClass, initMethod, appObj);
    if (env->ExceptionCheck()) {
        LOGE("Exception executing BattMon.init(Context)");
        env->ExceptionDescribe();
        env->ExceptionClear();
    } else {
        LOGI("BattMon.init executed successfully inside SystemUI");
    }

    vm->DetachCurrentThread();
    return nullptr;
}

class BattMonModule : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void postAppSpecialize(const AppSpecializeArgs *args) override {
        const char* nice_name = env->GetStringUTFChars(args->nice_name, nullptr);
        if (!nice_name || strcmp(nice_name, "com.android.systemui") != 0) {
            if (nice_name) env->ReleaseStringUTFChars(args->nice_name, nice_name);
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        LOGI("postAppSpecialize matched target package: %s", nice_name);
        env->ReleaseStringUTFChars(args->nice_name, nice_name);

        JavaVM* vm = nullptr;
        if (env->GetJavaVM(&vm) != JNI_OK || !vm) {
            LOGE("Failed to get JavaVM pointer");
            return;
        }

        pthread_t tid;
        if (pthread_create(&tid, nullptr, InitWorker, reinterpret_cast<void*>(vm)) == 0) {
            pthread_detach(tid);
        } else {
            LOGE("Failed to launch background InitWorker thread");
        }
    }

private:
    Api *api;
    JNIEnv *env;
};

REGISTER_ZYGISK_MODULE(BattMonModule)
