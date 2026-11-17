#!/usr/bin/env python3
"""Build the SDK-only lifecycle controller without rebuilding or weakening the target App."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
CERTIFICATE_SHA256 = 'e1ef5c24ca8349376e6f3ebf0d9b93a7c44c20d7bb9ae2144f053abb649fd45c'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--build-tools', default='36.0.0')
    parser.add_argument('--signing-root', type=Path, default=ROOT)
    parser.add_argument('--output', type=Path, default=ROOT / 'app/build/reports/release-ui-blackbox/sdk-driver')
    args = parser.parse_args()
    tools = args.sdk / 'build-tools' / args.build_tools
    android = args.sdk / 'platforms/android-34/android.jar'
    if not android.is_file():
        parser.error('Android API 34 is required for this lifecycle controller')
    args.output.mkdir(parents=True, exist_ok=True)
    for directory in ('classes', 'dex', 'signer'):
        (args.output / directory).mkdir(exist_ok=True)
    suffix = '.exe' if os.name == 'nt' else ''
    script_suffix = '.bat' if os.name == 'nt' else ''
    java = args.java_home / 'bin'
    environment = os.environ.copy()
    environment['JAVA_HOME'] = str(args.java_home)
    manifest = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.wuxianggujun.tinaide.qa.lifecycle" android:versionCode="1" android:versionName="1">
<uses-sdk android:minSdkVersion="28" android:targetSdkVersion="36" />
<application android:debuggable="true" android:testOnly="true" />
<instrumentation android:name="com.wuxianggujun.tinaide.testing.ReleaseLifecycleInstrumentation" android:targetPackage="com.wuxianggujun.tinaide" android:functionalTest="true" />
</manifest>'''
    (args.output / 'AndroidManifest.xml').write_text(manifest, encoding='utf-8')

    def run(command, capture=False):
        return subprocess.run(list(map(str, command)), env=environment, check=True, timeout=60,
                              capture_output=capture)

    controller = ROOT / 'tools/testing/android/ReleaseLifecycleInstrumentation.java'
    signer = ROOT / 'tools/testing/android/SignTestArtifact.java'
    compiler = java / ('javac' + suffix)
    run([compiler, '-J-Duser.language=en', '-J-Dfile.encoding=UTF-8', '--release', '8',
         '-encoding', 'UTF-8', '-classpath', android, '-d', args.output / 'classes', controller])
    run([java / ('jar' + suffix), 'cf', args.output / 'driver.jar', '-C', args.output / 'classes', '.'])
    run([tools / ('d8' + script_suffix), '--min-api', '28', '--lib', android,
         '--output', args.output / 'dex', args.output / 'driver.jar'])
    run([tools / ('aapt' + suffix), 'package', '-f', '-M', args.output / 'AndroidManifest.xml',
         '-I', android, '-F', args.output / 'unsigned.apk'])
    with zipfile.ZipFile(args.output / 'unsigned.apk', 'a') as archive:
        archive.write(args.output / 'dex/classes.dex', 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
    run([tools / ('zipalign' + suffix), '-f', '4', args.output / 'unsigned.apk', args.output / 'aligned.apk'])
    run([compiler, '-J-Duser.language=en', '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8',
         '-d', args.output / 'signer', signer])
    apk = args.output / 'lifecycle-driver.apk'
    run([java / ('java' + suffix), '-classpath', args.output / 'signer',
         'com.wuxianggujun.tinaide.testing.SignTestArtifact', args.signing_root,
         tools / ('apksigner' + script_suffix), args.output / 'aligned.apk', apk])
    result = run([tools / ('apksigner' + script_suffix), 'verify', '--print-certs', apk], capture=True)
    match = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', result.stdout.decode('utf-8'))
    if match is None or match.group(1) != CERTIFICATE_SHA256:
        raise ValueError('Lifecycle controller certificate does not match TinaIDE Release')
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    report = dict(apk=str(apk.resolve()), sha256=digest, certificateSha256=match.group(1),
                  sdkOnly=True, controllerSourceSha256=hashlib.sha256(controller.read_bytes()).hexdigest())
    (args.output / 'artifact.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report, indent=2), flush=True)


if __name__ == '__main__':
    main()
