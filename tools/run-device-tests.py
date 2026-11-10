#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Run the instrumentation suite on a project emulator, one class per process with data reset.

    python tools/run-device-tests.py --api 31 --tag beta15            # full suite, Android 12
    python tools/run-device-tests.py --api 35 --tag beta15 --quick    # Android 13+ set, ~5 min
    python tools/run-device-tests.py --api 35 --tag beta15 --resume   # finish an interrupted run

Starts the emulator when it is not running and stops it at the end if it started it. If the
emulator dies during a class (the API 35 image can crash its host process, exit 139), it is
started again and that class runs again (up to 3 times), so a crash costs one class, not the run.
Progress is saved after every class: --resume skips classes that already passed under the tag.

Validation is tools/android_test_inventory.py's: every expected test must report, and the only
accepted skip is the API-33+ ocean shader on older platforms. Install the APKs first (--install
installs the current debug build). Results go to .tools/test-results/<tag>-<Class>.txt.
"""
import argparse
import json
import os
import pathlib
import subprocess
import sys
import time
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'tools'))
import android_test_inventory as inventory  # noqa: E402

TOOLS = ROOT / '.tools'
ADB = str(TOOLS / 'android-sdk/platform-tools/adb.exe')
EMULATOR = str(TOOLS / 'emulator-35.6.11/emulator/emulator.exe')
OUT = TOOLS / 'test-results'
FIXTURE = TOOLS / 'native/hyfixture-current.exe'
RUNNER = 'dev.pingclash.app.test/dev.pingclash.app.PingClashTestRunner'
APKS = [ROOT / 'app/build/outputs/apk/debug/app-debug.apk',
        ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']

DEVICES = {
    # API 31 is the stable baseline; software GPU without Vulkan avoids its known render hangs.
    31: ('PingClash_API31', 5558, ['-gpu', 'swiftshader_indirect', '-feature', '-Vulkan']),
    # The real GPU draws the live scene and the 3D badge as phones do.
    35: ('PingClash_API35', 5560, ['-gpu', 'host']),
}

# Classes whose behaviour depends on Android 13 or later, run on API 35 every round. The full
# suite still runs there before a release.
ANDROID_13_PLUS = {
    'SetupGuideTest': 'per-app language and activity recreation',
    'TranslationLayoutTest': 'per-app language, pages in every language',
    'AdvancedSettingsTest': 'per-app language, screenshot protection',
    'PageInkTest': 'live scene colours and system bar icons',
    'OceanShaderTest': 'live scene shader (skipped below 33)',
    'OceanDesignTest': 'live scene and themes',
    'ScenicGlassTest': 'glass backdrop layer',
    'LiquidOpticsTest': 'glass optics',
    'CustomBackgroundUiTest': 'picture background over the scene',
    'LanyardThreeTest': '3D badge on the real GPU',
    'AutomationTest': 'Quick Settings tile and notification permission',
    'RecentPreviewTest': 'recents screenshot handling',
    'VpnForegroundRecoveryTest': 'foreground service type and notifications',
    'NetworkReconnectionTest': 'network callbacks and notifications',
    'HysteriaCompatibilityTest': 'badge taps and the VPN on the real GPU',
    'SubscriptionImportTest': 'system dark mode and screenshot protection',
}

BOOT_TIMEOUT = 300
CLASS_TIMEOUT = 900
# A class runs again only when the emulator itself died; an app failure counts the first time.
CRASH_ATTEMPTS = 3


class Device:
    def __init__(self, api):
        self.avd, self.port, self.flags = DEVICES[api]
        self.serial = f'emulator-{self.port}'
        self.process = None
        self.restarts = 0

    def adb(self, *args, timeout=120):
        try:
            return subprocess.run([ADB, '-s', self.serial, *args], capture_output=True,
                                  text=True, encoding='utf-8', errors='replace',
                                  timeout=timeout).stdout
        except subprocess.TimeoutExpired:
            return ''

    def alive(self):
        # A guest soft reboot ("System has crashed") reports a finished boot before the user's
        # storage is unlocked, so wait for that too.
        return (self.adb('get-state', timeout=10).strip() == 'device'
                and self.adb('shell', 'getprop', 'sys.boot_completed', timeout=10).strip() == '1'
                and self.adb('shell', 'getprop', 'sys.user.0.ce_available',
                             timeout=10).strip() == 'true')

    def settle(self):
        """Waits for Android to come back after a guest reboot; restarts the emulator if not."""
        # A dead emulator never comes back on its own: restart it at once.
        gone = self.process is not None and self.process.poll() is not None
        if gone or self.adb('get-state', timeout=10).strip() != 'device':
            self.restart()
            return
        deadline = time.time() + BOOT_TIMEOUT
        while time.time() < deadline:
            if self.alive():
                time.sleep(15)
                return
            time.sleep(3)
        self.restart()

    def start(self):
        env = dict(os.environ,
                   ANDROID_HOME=str(TOOLS / 'android-sdk'),
                   ANDROID_USER_HOME=str(TOOLS / 'android-user'),
                   ANDROID_AVD_HOME=str(TOOLS / 'avd'),
                   TEMP=str(TOOLS / 'tmp'), TMP=str(TOOLS / 'tmp'))
        print(f'Starting {self.avd} on {self.serial}', flush=True)
        self.process = subprocess.Popen(
            [EMULATOR, '-avd', self.avd, '-port', str(self.port), '-no-window', '-no-audio',
             '-no-snapshot', '-accel', 'on', '-no-metrics', *self.flags],
            env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.time() + BOOT_TIMEOUT
        while time.time() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError(f'{self.avd} exited while booting ({self.process.returncode})')
            if self.alive():
                # Let package and settings services settle before the first install or test.
                time.sleep(15)
                return
            time.sleep(3)
        raise RuntimeError(f'{self.avd} did not boot within {BOOT_TIMEOUT} s')

    def restart(self):
        self.restarts += 1
        print(f'Emulator stopped responding; restarting (restart {self.restarts})', flush=True)
        self.stop()
        self.start()

    def stop(self):
        self.adb('emu', 'kill', timeout=20)
        if self.process is not None:
            try:
                self.process.wait(30)
            except subprocess.TimeoutExpired:
                self.process.kill()
            self.process = None

    def reset(self):
        self.adb('shell', 'am', 'force-stop', 'dev.pingclash.app')
        self.adb('shell', 'pm', 'clear', 'dev.pingclash.app')
        for key in ('window_animation_scale', 'transition_animation_scale',
                    'animator_duration_scale'):
            self.adb('shell', 'settings', 'put', 'global', key, '0')
        self.adb('shell', 'settings', 'put', 'global', 'private_dns_mode', 'off')
        self.adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
        self.adb('shell', 'cmd', 'uimode', 'night', 'no')
        self.adb('shell', 'input', 'keyevent', '3')


def start_fixture():
    ident = uuid.uuid4().hex
    ready = OUT / (ident + '-ready.json')
    stop = OUT / (ident + '-stop')
    process = subprocess.Popen([str(FIXTURE), '-ready', str(ready), '-stop', str(stop)],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deadline = time.time() + 15
    while not ready.exists():
        if process.poll() is not None or time.time() > deadline:
            raise RuntimeError('Hysteria host fixture did not start')
        time.sleep(.1)
    return process, stop, json.loads(ready.read_text(encoding='utf-8-sig'))


def device_failed(output):
    """Output that means Android itself went away rather than a test failing."""
    return (not output.strip()
            or 'System has crashed' in output
            or 'not available until after user' in output)


def run_class(device, name, hy):
    try:
        return subprocess.run(
            [ADB, '-s', device.serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
             name, '-e', 'hy2Port', str(hy['hy2Port']), '-e', 'hy2HttpPort',
             str(hy['httpPort']), '-e', 'hy2DnsPort', str(hy['dnsPort']), '-e', 'hy2Host',
             hy['host'], RUNNER],
            capture_output=True, text=True, encoding='utf-8', errors='replace',
            timeout=CLASS_TIMEOUT).stdout
    except subprocess.TimeoutExpired:
        return 'TIMEOUT'


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--api', type=int, choices=sorted(DEVICES), default=31)
    parser.add_argument('--tag', required=True, help='result name prefix, e.g. beta15-api35')
    parser.add_argument('--quick', action='store_true', help='only the Android 13+ set')
    parser.add_argument('--resume', action='store_true', help='skip classes already passed')
    parser.add_argument('--install', action='store_true', help='install the debug APKs first')
    parser.add_argument('--keep', action='store_true', help='leave the emulator running')
    parser.add_argument('classes', nargs='*', help='run only these classes')
    args = parser.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    summary_path = OUT / f'{args.tag}-summary.json'
    summary = {}
    if args.resume and summary_path.exists():
        summary = {row['class']: row for row in json.loads(summary_path.read_text('utf-8'))}

    wanted = [(name, expected) for name, expected in inventory.test_inventory(ROOT)]
    if args.quick:
        missing = set(ANDROID_13_PLUS) - {name.rsplit('.', 1)[1] for name, _ in wanted}
        if missing:
            raise SystemExit(f'Quick set names classes that do not exist: {sorted(missing)}')
        wanted = [w for w in wanted if w[0].rsplit('.', 1)[1] in ANDROID_13_PLUS]
    if args.classes:
        wanted = [w for w in wanted if w[0].rsplit('.', 1)[1] in set(args.classes)]

    device = Device(args.api)
    started_here = False
    if not device.alive():
        device.start()
        started_here = True
    if args.install:
        for apk in APKS:
            out = device.adb('install', '-r', str(apk), timeout=300)
            if 'Success' not in out:
                raise SystemExit(f'Install failed for {apk.name}: {out.strip()[-200:]}')
    sdk = int(device.adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    fixture, fixture_stop, hy = start_fixture()
    began = time.time()
    try:
        for name, expected in wanted:
            short = name.rsplit('.', 1)[1]
            if short in summary and summary[short]['ok']:
                print(f'SKIP {short} (passed earlier)', flush=True)
                continue
            for attempt in range(1, CRASH_ATTEMPTS + 1):
                if not device.alive():
                    device.restart()
                device.reset()
                started = time.time()
                output = run_class(device, name, hy)
                crashed = not device.alive() or device_failed(output)
                if crashed and attempt < CRASH_ATTEMPTS:
                    # The emulator or Android, not the app, failed: run the class again once
                    # the device is back.
                    device.settle()
                    continue
                break
            (OUT / f'{args.tag}-{short}.txt').write_text(output, encoding='utf-8')
            try:
                result = inventory.validate_output(output, expected, sdk)
                ok = True
            except RuntimeError as failure:
                result = str(failure) + (' (emulator crashed)' if crashed else '')
                ok = False
            summary[short] = {'class': short, 'expected': expected, 'ok': ok, 'result': result,
                              'seconds': round(time.time() - started), 'retried': attempt > 1}
            summary_path.write_text(json.dumps(list(summary.values()), indent=2), 'utf-8')
            print(f"{'PASS' if ok else 'FAIL'} {short} {result}"
                  + (' after an emulator restart' if attempt > 1 else ''), flush=True)
    finally:
        fixture_stop.write_text('stop')
        try:
            fixture.wait(10)
        except subprocess.TimeoutExpired:
            fixture.kill()
        if started_here and not args.keep:
            device.stop()
    rows = [summary[n.rsplit('.', 1)[1]] for n, _ in wanted if n.rsplit('.', 1)[1] in summary]
    passed = sum(1 for row in rows if row['ok'])
    print(f'DONE {passed}/{len(rows)} classes passed on API {sdk} in '
          f'{(time.time() - began) / 60:.1f} min, {device.restarts} emulator restart(s)',
          flush=True)
    return 0 if passed == len(rows) else 1


if __name__ == '__main__':
    sys.exit(main())
