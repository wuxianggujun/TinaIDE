// SDL 资源相对路径重定向 GOT hook。
//
// 背景（源码为证，SDL release-2.32.10 src/file/SDL_rwops.c 与
// src/core/android/SDL_android.c）：
//   - SDL_RWFromFile 对相对路径执行
//       fopen(SDL_AndroidGetInternalStoragePath() + "/" + file, mode)
//     其中 InternalStoragePath = context.getFilesDir().getCanonicalPath()。
//     宏 `#define fopen fopen64`（HAVE_FOPEN64），实际调用的符号是 fopen64。
//   - fopen 失败后才回退到 APK AAssetManager；绝对路径（'/' 开头）直接 fopen，
//     不经过 filesDir 前缀。
//   - 整条链路从不使用 cwd / getcwd，所以 hook cwd 无效。
//
// TinaIDE 的图形运行进程（:sdl2 / :sdl）里，用户资源既不在 getFilesDir() 下，
// 也不在 APK asset 里，而在项目目录中。本 hook 拦截 libSDL* 发起的 fopen/fopen64，
// 当且仅当：
//   1) 打开模式是只读（mode 以 'r' 开头）；
//   2) 路径落在 getFilesDir() 前缀下；
//   3) 该路径在 filesDir 下实际不存在；
//   4) 文件在配置的资源目录或项目根下存在，规范路径仍在允许目录内；
// 才把打开目标重定向回项目。写入 / 追加（包括 'r+'）一律
// 放行，存档仍留在 getFilesDir()，符合 Android 应用私有存储语义。
//
// 两个候选目录的意义：
//   - `<资源目录>/<rel>`：默认 assets/，运行配置可指定项目内的其他目录。导出 APK 时这些资源
//     会映射到 APK 的 assets 根，因此 `SDL_IOFromFile("font.ttf")` 在 APK 里命中
//     `assets/font.ttf`；IDE 直接运行时由这条候选把它接回项目目录，
//     于是同一条相对路径在两种运行方式下都成立。
//   - `<项目根>/<rel>`：兼容显式 assets/... 与源码平铺的工程。

#include <android/log.h>
#include <bytehook.h>
#include "asset_path_policy.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unistd.h>

#include <jni.h>

#define LOG_TAG "SdlAssetRedirect"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// install 发生在 SDL_main 之前的 loadLibraries()（单线程），此后这些值只读，
// 因此无需加锁。用普通 std::string 持有，进程存活期间不释放。
std::string g_files_dir;      // getFilesDir().getCanonicalPath()，无尾部 '/'
std::string g_project_root;   // 项目根绝对/规范路径，无尾部 '/'
std::string g_asset_dir;
bool g_installed = false;
bytehook_stub_t g_stub_fopen = nullptr;
bytehook_stub_t g_stub_fopen64 = nullptr;

std::string strip_trailing_slash(const char *value) {
    std::string result = value == nullptr ? "" : value;
    while (result.size() > 1 && result.back() == '/') {
        result.pop_back();
    }
    return result;
}

// Read-only resolution; traversal, symlink escapes and write modes never redirect.
std::string resolve_redirect(const char *path, const char *mode) {
    return tina::sdl_assets::resolve(g_files_dir, g_project_root, g_asset_dir, path, mode);
}

FILE *proxy_fopen(const char *path, const char *mode) {
    BYTEHOOK_STACK_SCOPE();
    std::string redirected = resolve_redirect(path, mode);
    if (!redirected.empty()) {
        LOGI("redirect fopen: %s -> %s", path, redirected.c_str());
        return BYTEHOOK_CALL_PREV(proxy_fopen, redirected.c_str(), mode);
    }
    return BYTEHOOK_CALL_PREV(proxy_fopen, path, mode);
}

FILE *proxy_fopen64(const char *path, const char *mode) {
    BYTEHOOK_STACK_SCOPE();
    std::string redirected = resolve_redirect(path, mode);
    if (!redirected.empty()) {
        LOGI("redirect fopen64: %s -> %s", path, redirected.c_str());
        return BYTEHOOK_CALL_PREV(proxy_fopen64, redirected.c_str(), mode);
    }
    return BYTEHOOK_CALL_PREV(proxy_fopen64, path, mode);
}

// caller 过滤器：只 hook 名字含 "libSDL" 的库（libSDL2.so、libSDL2-2.0.so.0、
// libSDL3.so、libSDL2_mixer.so ...）。扩展库最终经 SDL_RWFromFile 走到 libSDL 里的
// fopen，但把扩展库也纳入过滤可覆盖它们直接调用 fopen 的少数情况。
bool caller_allow(const char *caller_path_name, void *arg) {
    (void)arg;
    if (caller_path_name == nullptr) return false;
    return strstr(caller_path_name, "libSDL") != nullptr;
}

void on_hooked(bytehook_stub_t task_stub, int status_code, const char *caller_path_name,
               const char *sym_name, void *new_func, void *prev_func, void *arg) {
    (void)task_stub;
    (void)new_func;
    (void)prev_func;
    (void)arg;
    if (status_code != BYTEHOOK_STATUS_CODE_OK) {
        LOGW("hook %s in %s failed: status=%d", sym_name == nullptr ? "?" : sym_name,
             caller_path_name == nullptr ? "?" : caller_path_name, status_code);
    }
}

}  // namespace

// Kotlin `object SdlAssetRedirect` 的 `external fun` 编译为单例实例方法，
// 因此 JNI 第二参是 jobject（INSTANCE），不是 jclass。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_wuxianggujun_tinaide_ui_sdl_SdlAssetRedirect_nativeInstall(
    JNIEnv *env, jobject thiz, jbyteArray files_dir, jbyteArray project_root, jbyteArray asset_dir) {
    (void)thiz;
    if (g_installed) return JNI_TRUE;

    auto utf8_path = [env](jbyteArray bytes) -> std::string {
        if (!bytes) return {};
        std::string value(env->GetArrayLength(bytes), '\0');
        env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()), reinterpret_cast<jbyte *>(value.data()));
        if (env->ExceptionCheck() || value.find('\0') != std::string::npos) return {};
        return strip_trailing_slash(value.c_str());
    };
    g_files_dir = utf8_path(files_dir);
    g_project_root = utf8_path(project_root);
    g_asset_dir = utf8_path(asset_dir);
    if (env->ExceptionCheck()) return JNI_FALSE;

    if (g_files_dir.empty() || g_project_root.empty()) {
        LOGW("install skipped: filesDir='%s' projectRoot='%s'", g_files_dir.c_str(),
             g_project_root.c_str());
        return JNI_FALSE;
    }

    const int init_status = bytehook_init(BYTEHOOK_MODE_AUTOMATIC, false);
    if (init_status != BYTEHOOK_STATUS_CODE_OK) {
        LOGW("bytehook_init failed: status=%d", init_status);
        return JNI_FALSE;
    }

    g_stub_fopen64 = bytehook_hook_partial(caller_allow, nullptr, nullptr, "fopen64",
                                           reinterpret_cast<void *>(proxy_fopen64), on_hooked,
                                           nullptr);
    g_stub_fopen = bytehook_hook_partial(caller_allow, nullptr, nullptr, "fopen",
                                         reinterpret_cast<void *>(proxy_fopen), on_hooked, nullptr);

    g_installed = g_stub_fopen64 != nullptr || g_stub_fopen != nullptr;
    LOGI("install done: filesDir='%s' projectRoot='%s' fopen64=%p fopen=%p", g_files_dir.c_str(),
         g_project_root.c_str(), g_stub_fopen64, g_stub_fopen);
    return g_installed ? JNI_TRUE : JNI_FALSE;
}
