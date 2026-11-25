#include <dlfcn.h>
#include <jni.h>

// dlopen runs ELF constructors, but never JNI_OnLoad. Inspect the public SDL3
// version before ART attempts to register a potentially incompatible JNI table.
JNIEXPORT jint JNICALL
Java_org_libsdl_app_SdlNativeLibraryProbe_nativeReadVersion(JNIEnv *env, jclass type, jstring path) {
    (void) type;
    if (path == NULL) return 0;
    const char *library_path = (*env)->GetStringUTFChars(env, path, NULL);
    if (library_path == NULL) return 0;
    void *library = dlopen(library_path, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, path, library_path);
    if (library == NULL) {
        const char *error = dlerror();
        jclass error_type = (*env)->FindClass(env, "java/lang/UnsatisfiedLinkError");
        if (error_type != NULL) (*env)->ThrowNew(env, error_type, error ? error : "SDL probe failed");
        return 0;
    }
    int (*get_version)(void) = (int (*)(void)) dlsym(library, "SDL_GetVersion");
    int version = get_version ? get_version() : 0;
    dlclose(library);
    return version;
}
