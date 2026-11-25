"""Compare a pinned SDL source registration/callback contract with compiled Java descriptors."""
import argparse
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-source", type=Path, required=True)
    parser.add_argument("--hid-source", type=Path, required=True)
    parser.add_argument("--classes", type=Path, required=True)
    parser.add_argument("--javap", default="javap")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    android = args.android_source.read_text(encoding="utf-8")
    hid = args.hid_source.read_text(encoding="utf-8")
    contracts = []
    for name, body in re.findall(r"(?:static )?JNINativeMethod (\w+)_tab\[[^]]*\]\s*=\s*\{(.*?)\n\};", android + hid, re.S):
        contracts += [(name, method, descriptor, "native-registration") for method, descriptor in
                      re.findall(r'\{\s*"(\w+)",\s*"([^"]+)"', body)]
    for variable, name in (("mActivityClass", "SDLActivity"), ("mAudioManagerClass", "SDLAudioManager"),
                           ("mControllerManagerClass", "SDLControllerManager"), ("g_HIDDeviceManagerCallbackClass", "HIDDeviceManager")):
        contracts += [(name, method, descriptor, "java-callback") for method, descriptor in
                      re.findall(r'Get(?:Static)?MethodID\(\s*(?:env,\s*)?' + variable + r',\s*"(\w+)",\s*"([^"]+)"', android + hid)
                      if method != "registerReceiver"]  # Inherited Android framework API.
    methods = {}
    for name in sorted({contract[0] for contract in contracts}):
        result = subprocess.run([args.javap, "-classpath", str(args.classes), "-s", "-p", "org.libsdl.app." + name],
                                capture_output=True, encoding="utf-8", check=True, timeout=15)
        methods[name] = set(re.findall(r'\b(\w+)\([^;]*\)(?: throws [^;]+)?;\s+descriptor: (\S+)', result.stdout))
    missing = [contract for contract in contracts if (contract[1], contract[2]) not in methods[contract[0]]]
    report = {"checked": len(contracts), "registrations": sum(c[3] == "native-registration" for c in contracts),
              "callbacks": sum(c[3] == "java-callback" for c in contracts), "missing": missing}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report))
    return 1 if missing or not contracts else 0


if __name__ == "__main__":
    raise SystemExit(main())
