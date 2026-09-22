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
//   4) 把前缀换成项目根后，文件在项目根下存在；
// 才把打开目标重定向回项目根。写入 / 追加（'w' / 'a' / 'r+' 之外的写模式）一律
// 放行，存档仍留在 getFilesDir()，符合 Android 应用私有存储语义。

#include <android/log.h>
#include <bytehook.h>

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

// 判断 mode 是否为纯读取语义（要求文件已存在）。
// SDL 加载资源用 "rb"；"r"/"rb"/"r+"... 首字符都是 'r'，都要求文件存在，
// 重定向到项目里已存在的同名文件是安全的。'w'/'a' 创建或追加，绝不重定向。
bool is_read_mode(const char *mode) {
    return mode != nullptr && (mode[0] == 'r' || mode[0] == 'R');
}

// 若 path 命中重定向条件，返回重定向后的绝对路径；否则返回空串（放行原路径）。
// 该函数不做任何 I/O 副作用（只用 access 检查存在性），逻辑与 hook 解耦，便于推理。
std::string resolve_redirect(const char *path, const char *mode) {
    if (path == nullptr || path[0] == '\0') return {};
    if (g_files_dir.empty() || g_project_root.empty()) return {};
    if (!is_read_mode(mode)) return {};

    const size_t prefix_len = g_files_dir.size();
    // 必须是 "<filesDir>/<rel>" 形式：前缀相等且紧跟 '/'。
    if (strncmp(path, g_files_dir.c_str(), prefix_len) != 0) return {};
    if (path[prefix_len] != '/') return {};

    const char *relative = path + prefix_len + 1;
    if (relative[0] == '\0') return {};

    // 只有当 filesDir 下确实不存在、才考虑重定向，避免遮蔽用户真实写入的存档。
    if (access(path, F_OK) == 0) return {};

    std::string candidate = g_project_root;
    candidate.push_back('/');
    candidate.append(relative);

    if (access(candidate.c_str(), F_OK) != 0) return {};
    return candidate;
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
    JNIEnv *env, jobject thiz, jstring files_dir, jstring project_root) {
    (void)thiz;
    if (g_installed) return JNI_TRUE;

    const char *files_dir_utf = files_dir == nullptr ? nullptr
                                                     : env->GetStringUTFChars(files_dir, nullptr);
    const char *project_root_utf =
        project_root == nullptr ? nullptr : env->GetStringUTFChars(project_root, nullptr);

    g_files_dir = strip_trailing_slash(files_dir_utf);
    g_project_root = strip_trailing_slash(project_root_utf);

    if (files_dir_utf != nullptr) env->ReleaseStringUTFChars(files_dir, files_dir_utf);
    if (project_root_utf != nullptr) env->ReleaseStringUTFChars(project_root, project_root_utf);

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
