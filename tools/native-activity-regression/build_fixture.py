"""Build a separate NativeActivity APK without changing TinaIDE or its private data.

Uses the production native bridge and a selected Registry archive. Deliberately
does NOT use the IDE shutdown watchdog: this tests raylib's own destroy handling.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import zipfile


ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.wuxianggujun.tinaide.nativerepeat"
OUTPUT = ROOT / "app/build/reports/native-activity-regression/fixture"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--ndk-version", default="27.2.12479018")
    parser.add_argument("--build-tools", default="36.0.0")
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64"), default="arm64-v8a")
    parser.add_argument("--raylib-archive", type=Path)
    args = parser.parse_args()
    if args.sdk is None or args.java_home is None:
        parser.error("Set ANDROID_HOME/JAVA_HOME or pass --sdk and --java-home")
    sdk, java = args.sdk.resolve(), args.java_home.resolve()
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    exe = ".exe" if os.name == "nt" else ""
    script = ".bat" if os.name == "nt" else ""
    ndk = sdk / f"ndk/{args.ndk_version}/toolchains/llvm/prebuilt/{host}"
    bt = sdk / "build-tools" / args.build_tools
    android = sdk / "platforms/android-36/android.jar"
    archive_path = (args.raylib_archive or ROOT / (
        f"docker/tinaide-pkg/output/registry/raylib/6.0/raylib-{args.abi}.tar.xz"
    )).resolve()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    libs = OUTPUT / "lib" / args.abi
    libs.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java)
    env["JAVA_TOOL_OPTIONS"] = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"

    def run(*command):
        subprocess.run([str(part) for part in command], cwd=OUTPUT, env=env, check=True, timeout=50)

    def write(name, text):
        path = OUTPUT / name
        path.write_text(text, encoding="utf-8", newline="\n")
        return path

    with tarfile.open(archive_path) as archive:
        metadata = json.load(archive.extractfile("./package.json"))
        (libs / "libraylib.so").write_bytes(archive.extractfile(f"./lib/{args.abi}/libraylib.so").read())
        (OUTPUT / "raylib.h").write_bytes(archive.extractfile("./include/raylib.h").read())

    launcher = write("LaunchActivity.java", '''package com.wuxianggujun.tinaide.ui.nativeactivity;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
public class LaunchActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Button button = new Button(this);
        button.setText("START RAYLIB - gesture Back, then run again");
        button.setOnClickListener(v -> startActivity(new Intent(this, RepeatActivity.class)));
        setContentView(button);
    }
}
''')
    activity = write("RepeatActivity.java", '''package com.wuxianggujun.tinaide.ui.nativeactivity;
import android.app.NativeActivity;
import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
public class RepeatActivity extends NativeActivity {
    private void record(String message) {
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "lifecycle.txt"), true)) {
            out.write((System.currentTimeMillis() + " pid=" + Process.myPid() + " " + message + "\\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { throw new RuntimeException(error); }
    }
    @Override public void onCreate(Bundle state) {
        record("create-enter");
        String dir = getApplicationInfo().nativeLibraryDir;
        NativeActivityHostBridge.nativeConfigure(dir + "/libruntime_smoke.so", new String[]{dir + "/libraylib.so"});
        if (getIntent().getBooleanExtra("cancel_before_create", false)) finish();
        super.onCreate(state);
        if (getIntent().getBooleanExtra("cancel_before_window", false)) finish();
        record("create-return");
    }
    @Override public void finish() { record("finish"); super.finish(); }
    @Override public void onDestroy() {
        record("destroy-enter finishing=" + isFinishing());
        boolean terminate = isFinishing() && !isChangingConfigurations();
        try { super.onDestroy(); } finally {
            record("destroy-return");
            if (terminate) Process.killProcess(Process.myPid());
        }
    }
    public void onNativeHostError(int code, String detail) { record("host-error " + code); finish(); }
}
final class NativeActivityHostBridge {
    static { System.loadLibrary("tina_native_activity_host"); }
    static native boolean nativeConfigure(String path, String[] dependencies);
}
''')
    native = write("repeat_main.c", '''#include <raylib.h>
#include <stdio.h>
#include <unistd.h>
static void record(const char *event, int frames) {
    FILE *file = fopen("/data/user/0/com.wuxianggujun.tinaide.nativerepeat/files/frames.txt", "a");
    if (file) { fprintf(file, "pid=%d tid=%d %s frames=%d\\n", getpid(), gettid(), event, frames); fclose(file); }
}
int main(void) {
    record("main-enter", 0);
    InitWindow(480, 640, "Native repeat regression");
    if (!IsWindowReady()) { record("init-cancelled", 0); record("main-return", 0); return 0; }
    SetTargetFPS(30);
    int frames = 0;
    while (!WindowShouldClose()) {
        int key; bool back = false;
        while ((key = GetKeyPressed()) != 0) if (key == KEY_BACK || key == KEY_ESCAPE) back = true;
        if (back) break;
        BeginDrawing();
        ClearBackground(DARKBLUE);
        DrawRectangle(20 + frames % 220, 200, 120, 120, SKYBLUE);
        DrawText("Swipe BACK then run again", 30, 60, 30, RAYWHITE);
        DrawText(TextFormat("frames %d", frames), 30, 120, 26, GREEN);
        EndDrawing();
        if (++frames % 30 == 0) record("frame", frames);
    }
    record("loop-exit", frames);
    CloseWindow();
    record("main-return", frames);
    return 0;
}
''')
    manifest = write("AndroidManifest.xml", f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PACKAGE}">
  <uses-sdk android:minSdkVersion="28" android:targetSdkVersion="36"/>
  <application android:label="Tina native repeat regression" android:debuggable="true" android:extractNativeLibs="true" android:theme="@android:style/Theme.Material.NoActionBar.Fullscreen">
    <activity android:name="com.wuxianggujun.tinaide.ui.nativeactivity.LaunchActivity" android:exported="true"/>
    <activity android:name="com.wuxianggujun.tinaide.ui.nativeactivity.RepeatActivity" android:exported="true" android:process=":gui" android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenLayout|screenSize|smallestScreenSize|uiMode">
      <meta-data android:name="android.app.lib_name" android:value="tina_native_activity_host"/>
    </activity>
  </application>
</manifest>
''')
    clang = ndk / ("bin/clang" + exe)
    triple = "aarch64" if args.abi == "arm64-v8a" else "x86_64"
    flags = (f"--target={triple}-linux-android28", "-shared", "-fPIC", "-O1", "-Wl,-z,max-page-size=16384")
    run(clang, *flags, "-Wl,-z,global", "-Wl,-soname,libtina_native_activity_host.so",
        ROOT / "app/src/main/cpp/native_activity/native_activity_host.c", "-landroid", "-ldl", "-llog", "-o", libs / "libtina_native_activity_host.so")
    run(clang, *flags, "-Wl,-soname,libruntime_smoke.so", "-Wl,-rpath,$ORIGIN", "-I", OUTPUT, native,
        "-L", libs, "-lraylib", "-o", libs / "libruntime_smoke.so")
    classes, dex = OUTPUT / "classes", OUTPUT / "dex"
    classes.mkdir(exist_ok=True)
    dex.mkdir(exist_ok=True)
    run(java / ("bin/javac" + exe), "-encoding", "UTF-8", "-source", "8", "-target", "8", "-classpath", android, "-d", classes, launcher, activity)
    run(bt / ("d8" + script), "--lib", android, "--min-api", "28", "--output", dex, *classes.glob("com/wuxianggujun/tinaide/ui/nativeactivity/*.class"))
    unsigned, aligned = OUTPUT / "unsigned.apk", OUTPUT / "aligned.apk"
    run(bt / ("aapt2" + exe), "link", "-I", android, "--manifest", manifest, "-o", unsigned)
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_STORED) as archive:
        archive.write(dex / "classes.dex", "classes.dex")
        for library in libs.glob("*.so"):
            archive.write(library, f"lib/{args.abi}/{library.name}")
    run(bt / ("zipalign" + exe), "-f", "4", unsigned, aligned)
    key = OUTPUT / "fixture.keystore"
    if not key.exists():
        run(java / ("bin/keytool" + exe), "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
            "-alias", "fixture", "-dname", "CN=Local Runtime Regression", "-keyalg", "RSA", "-validity", "36500", "-noprompt")
    apk = OUTPUT / "repro.apk"
    run(bt / ("apksigner" + script), "sign", "--ks", key, "--ks-key-alias", "fixture", "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", apk, aligned)
    print(f"Fixture APK: {apk}")
    print(f"raylib revision: {metadata.get('packageRevision')}")
    print(f"raylib SHA-256: {hashlib.sha256((libs / 'libraylib.so').read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
