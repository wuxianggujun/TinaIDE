#define _GNU_SOURCE

#include <android/log.h>
#include <android/native_activity.h>
#include <dlfcn.h>
#include <jni.h>
#include <limits.h>
#include <pthread.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "TinaNativeActivity"

enum host_error_code {
    HOST_ERROR_NOT_CONFIGURED = 1,
    HOST_ERROR_DEPENDENCY_LOAD = 2,
    HOST_ERROR_MAIN_LOAD = 3,
    HOST_ERROR_ENTRY_MISSING = 4,
    HOST_ERROR_RAYLIB_MAIN_MISSING = 5,
    HOST_ERROR_ENTRY_RECURSION = 6,
    HOST_ERROR_MULTIPLE_ENTRIES = 7,
};

typedef int (*user_main_fn)(int argc, char **argv);
typedef void (*native_activity_entry_fn)(
    ANativeActivity *activity,
    void *saved_state,
    size_t saved_state_size
);

static pthread_mutex_t g_config_lock = PTHREAD_MUTEX_INITIALIZER;
static char *g_main_library_path = NULL;
static char **g_dependency_library_paths = NULL;
static size_t g_dependency_library_count = 0;
static user_main_fn g_user_main = NULL;

__attribute__((visibility("default"))) int main(int argc, char **argv);
__attribute__((visibility("default"))) void ANativeActivity_onCreate(
    ANativeActivity *activity,
    void *saved_state,
    size_t saved_state_size
);

static void free_configuration_locked(void) {
    free(g_main_library_path);
    g_main_library_path = NULL;
    for (size_t index = 0; index < g_dependency_library_count; ++index) {
        free(g_dependency_library_paths[index]);
    }
    free(g_dependency_library_paths);
    g_dependency_library_paths = NULL;
    g_dependency_library_count = 0;
    g_user_main = NULL;
}

static char *copy_jstring(JNIEnv *env, jstring value) {
    if (value == NULL) return NULL;
    const char *utf_value = (*env)->GetStringUTFChars(env, value, NULL);
    if (utf_value == NULL) return NULL;
    char *copy = strdup(utf_value);
    (*env)->ReleaseStringUTFChars(env, value, utf_value);
    return copy;
}

JNIEXPORT jboolean JNICALL
Java_com_wuxianggujun_tinaide_ui_nativeactivity_NativeActivityHostBridge_nativeConfigure(
    JNIEnv *env,
    jobject thiz,
    jstring main_library_path,
    jobjectArray dependency_library_paths
) {
    (void)thiz;
    pthread_mutex_lock(&g_config_lock);
    free_configuration_locked();

    g_main_library_path = copy_jstring(env, main_library_path);
    const jsize dependency_count = dependency_library_paths == NULL
        ? 0
        : (*env)->GetArrayLength(env, dependency_library_paths);
    if (dependency_count > 0) {
        g_dependency_library_paths = calloc((size_t)dependency_count, sizeof(char *));
        if (g_dependency_library_paths == NULL) {
            free_configuration_locked();
            pthread_mutex_unlock(&g_config_lock);
            return JNI_FALSE;
        }

        for (jsize index = 0; index < dependency_count; ++index) {
            jstring path = (jstring)(*env)->GetObjectArrayElement(
                env,
                dependency_library_paths,
                index
            );
            g_dependency_library_paths[index] = copy_jstring(env, path);
            (*env)->DeleteLocalRef(env, path);
            if (g_dependency_library_paths[index] == NULL) {
                free_configuration_locked();
                pthread_mutex_unlock(&g_config_lock);
                return JNI_FALSE;
            }
            g_dependency_library_count++;
        }
    }

    const jboolean configured = g_main_library_path != NULL && g_main_library_path[0] != '\0'
        ? JNI_TRUE
        : JNI_FALSE;
    pthread_mutex_unlock(&g_config_lock);
    return configured;
}

static void report_host_error(
    ANativeActivity *activity,
    enum host_error_code error_code,
    const char *detail
) {
    const char *safe_detail = detail == NULL ? "" : detail;
    __android_log_print(
        ANDROID_LOG_ERROR,
        LOG_TAG,
        "NativeActivity host error code=%d detail=%s",
        error_code,
        safe_detail
    );
    if (activity == NULL || activity->env == NULL || activity->clazz == NULL) return;

    JNIEnv *env = activity->env;
    jclass activity_class = (*env)->GetObjectClass(env, activity->clazz);
    if (activity_class == NULL) return;
    jmethodID callback = (*env)->GetMethodID(
        env,
        activity_class,
        "onNativeHostError",
        "(ILjava/lang/String;)V"
    );
    if (callback == NULL) {
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, activity_class);
        return;
    }

    jstring detail_string = (*env)->NewStringUTF(env, safe_detail);
    (*env)->CallVoidMethod(env, activity->clazz, callback, (jint)error_code, detail_string);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    if (detail_string != NULL) (*env)->DeleteLocalRef(env, detail_string);
    (*env)->DeleteLocalRef(env, activity_class);
}

static int is_raylib_entry(native_activity_entry_fn entry) {
    Dl_info entry_info;
    memset(&entry_info, 0, sizeof(entry_info));
    if (dladdr((void *)entry, &entry_info) == 0 || entry_info.dli_fname == NULL) return 0;
    return strstr(entry_info.dli_fname, "libraylib.so") != NULL;
}

static int paths_refer_to_same_file(const char *left, const char *right) {
    if (left == NULL || right == NULL) return 0;
    if (strcmp(left, right) == 0) return 1;

    char resolved_left[PATH_MAX];
    char resolved_right[PATH_MAX];
    return realpath(left, resolved_left) != NULL &&
        realpath(right, resolved_right) != NULL &&
        strcmp(resolved_left, resolved_right) == 0;
}

static int symbol_belongs_to_library(const void *symbol, const char *library_path) {
    if (symbol == NULL || library_path == NULL) return 0;

    Dl_info symbol_info;
    memset(&symbol_info, 0, sizeof(symbol_info));
    return dladdr(symbol, &symbol_info) != 0 &&
        paths_refer_to_same_file(symbol_info.dli_fname, library_path);
}

static void *resolve_owned_symbol(
    void *handle,
    const char *symbol_name,
    const char *library_path
) {
    if (handle == NULL || symbol_name == NULL || library_path == NULL) return NULL;

    dlerror();
    void *symbol = dlsym(handle, symbol_name);
    if (dlerror() != NULL || !symbol_belongs_to_library(symbol, library_path)) return NULL;
    return symbol;
}

static native_activity_entry_fn resolve_native_activity_entry(
    void *handle,
    const char *library_path
) {
    return (native_activity_entry_fn)resolve_owned_symbol(
        handle,
        "ANativeActivity_onCreate",
        library_path
    );
}

static int merge_native_activity_entry(
    native_activity_entry_fn candidate,
    native_activity_entry_fn *resolved
) {
    if (candidate == NULL) return 1;
    if (candidate == &ANativeActivity_onCreate) return 0;
    if (*resolved == NULL || *resolved == candidate) {
        *resolved = candidate;
        return 1;
    }
    return -1;
}

__attribute__((visibility("default"))) int main(int argc, char **argv) {
    user_main_fn user_main = g_user_main;
    if (user_main == NULL || user_main == &main) {
        __android_log_write(
            ANDROID_LOG_ERROR,
            LOG_TAG,
            "raylib requested main before a user main entry was installed"
        );
        return EXIT_FAILURE;
    }
    return user_main(argc, argv);
}

__attribute__((visibility("default"))) void ANativeActivity_onCreate(
    ANativeActivity *activity,
    void *saved_state,
    size_t saved_state_size
) {
    pthread_mutex_lock(&g_config_lock);
    if (g_main_library_path == NULL || g_main_library_path[0] == '\0') {
        pthread_mutex_unlock(&g_config_lock);
        report_host_error(activity, HOST_ERROR_NOT_CONFIGURED, NULL);
        return;
    }

    native_activity_entry_fn entry = NULL;
    for (size_t index = 0; index < g_dependency_library_count; ++index) {
        const char *dependency_path = g_dependency_library_paths[index];
        dlerror();
        void *dependency_handle = dlopen(dependency_path, RTLD_NOW | RTLD_GLOBAL);
        if (dependency_handle == NULL) {
            const char *error = dlerror();
            pthread_mutex_unlock(&g_config_lock);
            report_host_error(
                activity,
                HOST_ERROR_DEPENDENCY_LOAD,
                error == NULL ? dependency_path : error
            );
            return;
        }

        const int entry_merge_result = merge_native_activity_entry(
            resolve_native_activity_entry(dependency_handle, dependency_path),
            &entry
        );
        if (entry_merge_result <= 0) {
            pthread_mutex_unlock(&g_config_lock);
            report_host_error(
                activity,
                entry_merge_result == 0
                    ? HOST_ERROR_ENTRY_RECURSION
                    : HOST_ERROR_MULTIPLE_ENTRIES,
                dependency_path
            );
            return;
        }
    }

    dlerror();
    void *main_handle = dlopen(g_main_library_path, RTLD_NOW | RTLD_GLOBAL);
    if (main_handle == NULL) {
        const char *error = dlerror();
        pthread_mutex_unlock(&g_config_lock);
        report_host_error(
            activity,
            HOST_ERROR_MAIN_LOAD,
            error == NULL ? g_main_library_path : error
        );
        return;
    }

    user_main_fn resolved_user_main = (user_main_fn)resolve_owned_symbol(
        main_handle,
        "main",
        g_main_library_path
    );

    const int main_entry_merge_result = merge_native_activity_entry(
        resolve_native_activity_entry(main_handle, g_main_library_path),
        &entry
    );
    if (main_entry_merge_result <= 0) {
        pthread_mutex_unlock(&g_config_lock);
        report_host_error(
            activity,
            main_entry_merge_result == 0
                ? HOST_ERROR_ENTRY_RECURSION
                : HOST_ERROR_MULTIPLE_ENTRIES,
            g_main_library_path
        );
        return;
    }
    if (entry == NULL) {
        pthread_mutex_unlock(&g_config_lock);
        report_host_error(activity, HOST_ERROR_ENTRY_MISSING, g_main_library_path);
        return;
    }
    if (is_raylib_entry(entry) && resolved_user_main == NULL) {
        pthread_mutex_unlock(&g_config_lock);
        report_host_error(activity, HOST_ERROR_RAYLIB_MAIN_MISSING, g_main_library_path);
        return;
    }

    g_user_main = resolved_user_main;
    pthread_mutex_unlock(&g_config_lock);
    entry(activity, saved_state, saved_state_size);
}
