#!/usr/bin/env python3
"""Build/install a Qualcomm debug or signed release APK and collect NPU parity/offload evidence.

Does not open the camera, clear logcat, uninstall the app, or erase app data.
Evidence stays under ignored .local/validation/ until reviewed for publication.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'com.roywatson.android_litert_object_recog'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, help='adb device serial (required to avoid testing the wrong device)')
    parser.add_argument('--qairt-dir', type=Path, default=ROOT / '.local/qairt/2.50.0.260828')
    parser.add_argument('--adb', default=shutil.which('adb') or str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--build-type', choices=('debug', 'release'), default='debug')
    args = parser.parse_args()

    def adb(*command, check=True, timeout=300):
        return subprocess.run([args.adb, '-s', args.serial, *command], capture_output=True,
                              text=True, check=check, timeout=timeout)

    variant = args.build_type.capitalize()
    package = PACKAGE + ('.release' if args.build_type == 'release' else '')
    apk_paths = (f'app/build/outputs/apk/{args.build_type}/app-{args.build_type}.apk',
                 f'app/build/outputs/apk/androidTest/{args.build_type}/app-{args.build_type}-androidTest.apk')
    if not args.skip_build:
        subprocess.run([str(ROOT / 'gradlew'), f'-PqualcommNpuDir={args.qairt_dir.resolve()}',
                        f'-PvalidationBuildType={args.build_type}',
                        f':app:assemble{variant}', f':app:assemble{variant}AndroidTest'], cwd=ROOT, check=True)
    # Check AGP's current output metadata rather than accidentally using a stale signed APK.
    metadata = json.loads((ROOT / f'app/build/outputs/apk/{args.build_type}/output-metadata.json').read_text())
    current_apk = metadata['elements'][0]['outputFile']
    if current_apk != f'app-{args.build_type}.apk' or metadata['applicationId'] != package:
        raise SystemExit('Expected a signed APK for the selected variant; check local release signing credentials')
    import zipfile
    with zipfile.ZipFile(ROOT / apk_paths[0]) as apk:
        if 'lib/arm64-v8a/libvision_npu.so' not in apk.namelist():
            raise SystemExit('The selected APK is a portable build; build it with qualcommNpuDir first')
    model = adb('shell', 'getprop', 'ro.product.model').stdout.strip()
    if model not in ('SM-S908U', 'SM-S938U'):
        raise SystemExit(f'Expected one of the selected phones, found {model!r}')
    name = re.sub(r'[^a-zA-Z0-9_-]', '_', model)
    destination = ROOT / '.local/validation' / f'{name}-{datetime.now(timezone.utc):%Y%m%dT%H%M%SZ}'
    destination.mkdir(parents=True, exist_ok=False)
    for relative in apk_paths:
        print(adb('install', '-r', str(ROOT / relative)).stdout, flush=True)
    # Remove only the previous test report, so a crash cannot be mistaken for a new pass.
    if args.build_type == 'debug':
        adb('shell', 'run-as', package, 'rm', '-f', 'files/npu-validation.json')
    start = adb('shell', "date '+%m-%d %H:%M:%S.000'").stdout.strip()
    result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                 PACKAGE + '.StartupSmokeTest,' + PACKAGE + '.NpuValidationTest,' + PACKAGE + '.VisionPipelineTest', package + '.test/androidx.test.runner.AndroidJUnitRunner',
                 check=False, timeout=600)
    (destination / 'instrumentation.txt').write_text(result.stdout + result.stderr)
    status = None
    match = re.search(r'INSTRUMENTATION_STATUS: vision_npu_report=(\{[^\n]+\})', result.stdout)
    if match:
        status = json.loads(match.group(1))
        (destination / 'npu-validation.json').write_text(json.dumps(status, indent=2) + '\n')
    pid = status.get('process_id') if status else None
    if pid is None:
        match = re.search(r'vision_npu_pid=(\d+)', result.stdout)
        if match: pid = int(match.group(1))
    if pid is not None:
        logs = adb('logcat', '-d', '-v', 'threadtime', '--pid', str(pid), '-T', start, check=False)
        (destination / 'process-logcat.txt').write_text(logs.stdout + logs.stderr)
    # Preserve the exact opt-in SDK provenance and APK hashes alongside runtime evidence.
    import hashlib
    provenance = {'serial': args.serial, 'device': model, 'build_type': args.build_type, 'package': package, 'apks': {}}
    for relative in apk_paths:
        provenance['apks'][Path(relative).name] = hashlib.sha256((ROOT / relative).read_bytes()).hexdigest()
    provenance['qairt_manifest'] = json.loads((args.qairt_dir / 'manifest.json').read_text())
    (destination / 'provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print('Evidence saved:', destination)
    # adb can exit successfully even when instrumentation crashes or another
    # test fails after the NPU report. Require the entire six-test suite too.
    suite_passed = re.search(r'OK \(6 tests\)', result.stdout) is not None
    if (status is None or status.get('result') != 'PASS' or result.returncode != 0
            or not suite_passed or 'FAILURES!!!' in result.stdout
            or 'Process crashed' in result.stdout):
        print(json.dumps(status, indent=2) if status else result.stdout, file=sys.stderr)
        raise SystemExit('NPU validation did not pass; inspect the saved report and process log')
    print(json.dumps(status, indent=2))


if __name__ == '__main__':
    main()
