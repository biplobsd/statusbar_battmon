#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>
#include <atomic>
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
using zygisk::ServerSpecializeArgs;

namespace {

constexpr const char *kTargetPackage = "com.android.systemui";
constexpr const char *kDynamicDexPath = "/data/local/tmp/battmon/classes.dex";
constexpr const char *kEntryClass = "com.battmon.BattMon";

// ---------------------------------------------------------------------------
// DEX keep-alive buffer.
//
// dalvik.system.DexFile#createCookieWithDirectBuffer reads the buffer address with
// Buffer.address(buffer) and hands that raw pointer to ART; the class loader keeps
// referencing that memory for the whole process lifetime (no copy is made). The buffer
// backing an InMemoryDexClassLoader therefore has to stay alive - and unchanged - until
// the process dies. Holding it in a translation-unit static (instead of a stack vector
// inside the worker, which used to be freed on return) removes a real use-after-free
// that could take SystemUI down at an arbitrary later point.
// ---------------------------------------------------------------------------
std::vector<unsigned char> g_dex_keepalive;

std::atomic<bool> g_worker_started{false};

jobject GetApplication(JNIEnv *env) {
    jclass activity_thread = nullptr;
    jmethodID current_application = nullptr;

    // The JVM needs a moment before ActivityThread.currentApplication() returns the
    // Application instance. Wait without burning CPU, and resolve symbols only once.
    for (int attempt = 0; attempt < 120; ++attempt) {
        if (activity_thread == nullptr) {
            activity_thread = env->FindClass("android/app/ActivityThread");
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                activity_thread = nullptr;
            }
        }
        if (activity_thread != nullptr && current_application == nullptr) {
            current_application = env->GetStaticMethodID(
                    activity_thread, "currentApplication", "()Landroid/app/Application;");
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                current_application = nullptr;
            }
        }
        if (activity_thread != nullptr && current_application != nullptr) {
            jobject app = env->CallStaticObjectMethod(activity_thread, current_application);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                app = nullptr;
            }
            if (app != nullptr) {
                return app;
            }
        }
        // 50 ms -> 400 ms backoff; bounded at ~10 s total.
        usleep(attempt < 20 ? 50000 : (attempt < 40 ? 200000 : 400000));
    }
    return nullptr;
}

void *InitWorker(void *arg) {
    JavaVM *vm = reinterpret_cast<JavaVM *>(arg);
    JNIEnv *env = nullptr;
    if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("Failed to attach worker thread to JVM");
        return nullptr;
    }

    LOGI("InitWorker running inside %s (pid=%d)", kTargetPackage, getpid());

    jobject app = GetApplication(env);
    if (app == nullptr) {
        LOGE("Timed out waiting for the Application instance");
        vm->DetachCurrentThread();
        return nullptr;
    }

    // Prefer an updated DEX from disk, otherwise use the embedded fallback. Either way the
    // bytes are copied into g_dex_keepalive so they outlive this thread.
    bool dynamic = false;
    FILE *df = fopen(kDynamicDexPath, "rb");
    if (df != nullptr) {
        fseek(df, 0, SEEK_END);
        const long sz = ftell(df);
        fseek(df, 0, SEEK_SET);
        if (sz > 0 && sz <= 8 * 1024 * 1024) {
            g_dex_keepalive.resize(static_cast<size_t>(sz));
            if (fread(g_dex_keepalive.data(), 1, static_cast<size_t>(sz), df)
                    == static_cast<size_t>(sz)) {
                dynamic = true;
            } else {
                g_dex_keepalive.clear();
            }
        }
        fclose(df);
    }
    if (!dynamic) {
        g_dex_keepalive.assign(battmon_dex, battmon_dex + battmon_dex_len);
        LOGI("Using embedded DEX (%zu bytes)", static_cast<size_t>(battmon_dex_len));
    } else {
        LOGI("Using dynamic DEX from %s (%zu bytes)", kDynamicDexPath, g_dex_keepalive.size());
    }

    jclass app_class = env->GetObjectClass(app);
    jmethodID get_class_loader = env->GetMethodID(app_class, "getClassLoader",
                                                 "()Ljava/lang/ClassLoader;");
    jobject app_loader = get_class_loader != nullptr
            ? env->CallObjectMethod(app, get_class_loader) : nullptr;
    if (app_loader == nullptr) {
        LOGE("Failed to get the ClassLoader of the Application");
        vm->DetachCurrentThread();
        return nullptr;
    }

    jobject byte_buffer = env->NewDirectByteBuffer(g_dex_keepalive.data(),
                                                   static_cast<jlong>(g_dex_keepalive.size()));
    if (byte_buffer == nullptr) {
        LOGE("Failed to create the DirectByteBuffer for the DEX");
        vm->DetachCurrentThread();
        return nullptr;
    }

    jclass loader_class = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    if (loader_class == nullptr) {
        LOGE("dalvik.system.InMemoryDexClassLoader not found");
        env->ExceptionClear();
        vm->DetachCurrentThread();
        return nullptr;
    }

    jmethodID loader_ctor = env->GetMethodID(loader_class, "<init>",
                                             "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    jobject dex_loader = env->NewObject(loader_class, loader_ctor, byte_buffer, app_loader);
    if (env->ExceptionCheck() || dex_loader == nullptr) {
        LOGE("Failed to instantiate InMemoryDexClassLoader");
        env->ExceptionDescribe();
        env->ExceptionClear();
        vm->DetachCurrentThread();
        return nullptr;
    }

    jclass class_loader_class = env->FindClass("java/lang/ClassLoader");
    jmethodID load_class = env->GetMethodID(class_loader_class, "loadClass",
                                            "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring class_name = env->NewStringUTF(kEntryClass);
    jclass entry_class = reinterpret_cast<jclass>(
            env->CallObjectMethod(dex_loader, load_class, class_name));
    env->DeleteLocalRef(class_name);

    if (env->ExceptionCheck() || entry_class == nullptr) {
        LOGE("Failed to load %s", kEntryClass);
        env->ExceptionDescribe();
        env->ExceptionClear();
        vm->DetachCurrentThread();
        return nullptr;
    }

    jmethodID init_method = env->GetStaticMethodID(entry_class, "init",
                                                   "(Landroid/content/Context;)V");
    if (init_method == nullptr) {
        LOGE("%s.init(Context) not found", kEntryClass);
        env->ExceptionClear();
        vm->DetachCurrentThread();
        return nullptr;
    }

    env->CallStaticVoidMethod(entry_class, init_method, app);
    if (env->ExceptionCheck()) {
        LOGE("Exception while executing %s.init(Context)", kEntryClass);
        env->ExceptionDescribe();
        env->ExceptionClear();
    } else {
        LOGI("%s.init executed successfully", kEntryClass);
    }

    vm->DetachCurrentThread();
    return nullptr;
}

}  // namespace

class BattMonModule : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void postAppSpecialize(const AppSpecializeArgs *args) override {
        if (args == nullptr || args->nice_name == nullptr) {
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        const char *nice_name = env->GetStringUTFChars(args->nice_name, nullptr);
        const bool is_target = nice_name != nullptr && strcmp(nice_name, kTargetPackage) == 0;
        if (nice_name != nullptr) {
            env->ReleaseStringUTFChars(args->nice_name, nice_name);
        }
        if (!is_target) {
            // Nothing to do in any other process: unmap the module immediately so it
            // cannot cost memory (or exist at all) anywhere else.
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        LOGI("postAppSpecialize matched %s", kTargetPackage);

        if (g_worker_started.exchange(true)) {
            return;   // never start a second initialization thread
        }

        JavaVM *vm = nullptr;
        if (env->GetJavaVM(&vm) != JNI_OK || vm == nullptr) {
            LOGE("Failed to get the JavaVM pointer");
            g_worker_started.store(false);
            return;
        }

        pthread_t tid;
        if (pthread_create(&tid, nullptr, InitWorker, reinterpret_cast<void *>(vm)) == 0) {
            pthread_detach(tid);
        } else {
            LOGE("Failed to launch the initialization worker thread");
            g_worker_started.store(false);
        }
    }

    /**
     * system_server is forked from zygote like any other process, so Zygisk calls
     * preServerSpecialize()/postServerSpecialize() for it. This module has nothing to do
     * there; without this override the base-class no-op left the module library mapped
     * inside system_server for the whole uptime of the device.
     */
    void postServerSpecialize(const ServerSpecializeArgs *) override {
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    Api *api = nullptr;
    JNIEnv *env = nullptr;
};

REGISTER_ZYGISK_MODULE(BattMonModule)
