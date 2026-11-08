package org.libsdl.app;

/** Checks SDL's public ABI before ART invokes JNI_OnLoad. Runs only in the SDL process. */
public final class SdlNativeLibraryProbe {
    private SdlNativeLibraryProbe() { }

    public static int readVersion(String libraryPath) {
        System.loadLibrary("tina_sdl_probe");
        return nativeReadVersion(libraryPath);
    }

    public static boolean isSupported(int version) {
        int major = version / 1000000;
        int minor = version / 1000 % 1000;
        return major == 3 && (minor == 4 || minor == 5);
    }

    public static String formatVersion(int version) {
        return version / 1000000 + "." + version / 1000 % 1000 + "." + version % 1000;
    }

    private static native int nativeReadVersion(String libraryPath);
}
