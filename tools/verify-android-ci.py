"""Run synthetic APK integration tests only on the dedicated GitHub CI AVD."""
import json
import os
import pathlib
import re
import subprocess
import time
import urllib.request
from android_test_inventory import test_inventory, validate_output

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / '.tools/test-results'
SERIAL = 'emulator-5554'


def adb(*args, check=True):
    result = subprocess.run(['adb', '-s', SERIAL, *args], capture_output=True, text=True, timeout=45)
    if check and result.returncode:
        raise RuntimeError('ADB operation failed: ' + args[0])
    return result.stdout


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true':
        raise RuntimeError('This script only operates the dedicated GitHub CI emulator')
    if 'pingclash-ci' not in adb('emu', 'avd', 'name').splitlines():
        raise RuntimeError('Refusing to reset an unrelated emulator')
    OUT.mkdir(parents=True, exist_ok=True)
    app = ROOT / '.tools/ci-apks/debug/app-debug.apk'
    tests = ROOT / '.tools/ci-apks/androidTest/debug/app-debug-androidTest.apk'
    if not app.is_file() or not tests.is_file():
        raise RuntimeError('CI APK artifacts are missing')
    for package in ('dev.pingclash.app.test', 'dev.pingclash.app'):
        adb('uninstall', package, check=False)
    for key in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
        adb('shell', 'settings', 'put', 'global', key, '0')
    adb('shell', 'settings', 'put', 'global', 'private_dns_mode', 'off')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    adb('shell', 'setprop', 'debug.hwui.renderer', 'skiagl')
    adb('shell', 'input', 'keyevent', '82')
    adb('install', '-r', str(app))
    adb('install', '-r', str(tests))
    adb('shell', 'pm', 'clear', 'dev.pingclash.app')
    sdk = int(adb('shell', 'getprop', 'ro.build.version.sdk').strip())
    if sdk >= 33:
        adb('shell', 'cmd', 'locale', 'set-app-locales', 'dev.pingclash.app', '--user', '0', '--locales', 'zh-CN')
    ready = OUT / 'ci-fixture-ready.json'
    stop = OUT / 'ci-fixture-stop'
    # CI starts in a fresh checkout; never reuse unknown fixture state.
    if ready.exists() or stop.exists():
        raise RuntimeError('Fixture state already exists')
    with (OUT / 'ci-fixture.log').open('w', encoding='utf-8') as log:
        fixture = subprocess.Popen([str(ROOT / '.tools/hyfixture'), '-ready', str(ready), '-stop', str(stop)], stdout=log, stderr=log)
        try:
            deadline = time.monotonic() + 15
            while not ready.exists():
                if fixture.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError('Controlled Hysteria fixture did not start')
                time.sleep(.1)
            data = json.loads(ready.read_text())
            # Negative import/security fixtures deliberately mutate local storage.
            # Use a fresh target process/data directory per class, like an orchestrated
            # runner; lifecycle/stress tests still exercise repeated transitions in one run.
            totals = {'passed': 0, 'platformSkipped': 0}
            failures = []
            for test_class, expected in test_inventory(ROOT):
                adb('shell', 'pm', 'clear', 'dev.pingclash.app')
                for key in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
                    adb('shell', 'settings', 'put', 'global', key, '0')
                adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
                adb('shell', 'cmd', 'uimode', 'night', 'no')
                args = ['adb', '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-r',
                        '-e', 'class', test_class]
                for key, field in [('hy2Port', 'hy2Port'), ('hy2HttpPort', 'httpPort'), ('hy2DnsPort', 'dnsPort'), ('hy2Host', 'host')]:
                    args += ['-e', key, str(data[field])]
                args += ['dev.pingclash.app.test/androidx.test.runner.AndroidJUnitRunner']
                live = OUT / ('ci-' + test_class.rsplit('.', 1)[-1] + '-live-logcat.txt')
                with live.open('w', encoding='utf-8') as live_output:
                    logger = subprocess.Popen(
                        ['adb', '-s', SERIAL, 'logcat', '-T', '1', '-v', 'brief', '*:W'],
                        stdout=live_output, stderr=live_output)
                    try:
                        result = subprocess.run(args, capture_output=True, text=True, timeout=1200)
                    finally:
                        logger.terminate()
                        try:
                            logger.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            logger.kill()
                            logger.wait()
                output = result.stdout + result.stderr
                (OUT / ('ci-' + test_class.rsplit('.', 1)[-1] + '.txt')).write_text(output, encoding='utf-8')
                # Keep failure screenshots too, before the next class resets data.
                evidence = '/sdcard/Android/data/dev.pingclash.app/files/synthetic-test-evidence'
                if adb('shell', 'ls', '-d', evidence, check=False).strip() == evidence:
                    destination = OUT / 'ci-device' / test_class.rsplit('.', 1)[-1]
                    destination.mkdir(parents=True, exist_ok=True)
                    adb('pull', evidence, str(destination))
                if result.returncode:
                    print(output, flush=True)
                    raise RuntimeError('Instrumentation command failed')
                try:
                    checked = validate_output(output, expected, sdk)
                except RuntimeError:
                    (OUT / ('ci-' + test_class.rsplit('.', 1)[-1] + '-logcat.txt')).write_text(
                        adb('logcat', '-d', '-t', '1500', check=False), encoding='utf-8')
                    print(output, flush=True)
                    failures.append(test_class)
                else:
                    for key in totals:
                        totals[key] += checked[key]
                    print(test_class, checked, flush=True)
                # Continue isolated assertion failures, but never report a green run
                # after any failure. Transport/device failures still stop immediately.
                (OUT / 'ci-android.txt').write_text(
                    json.dumps({**totals, 'failedClasses': failures}, indent=2), encoding='utf-8')
            port = int(data['httpPort'])
            if not 0 < port < 65536:
                raise RuntimeError('Invalid fixture port')
            no_proxy = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with no_proxy.open(f'http://127.0.0.1:{port}/stats', timeout=5) as response:
                stats = json.load(response)
            if stats.get('dnsQueries', 0) < 1 or stats.get('peerRequests', 0) < 1:
                raise RuntimeError('Real VPN DNS and separate-UID HTTP relay were not observed')
            (OUT / 'ci-relay-stats.json').write_text(json.dumps(stats, indent=2), encoding='utf-8')
            if failures:
                raise RuntimeError('Failed instrumentation classes: ' + ', '.join(failures))
            print('Controlled VPN DNS and peer HTTP relay passed; no user subscription was used.')
        finally:
            stop.write_text('stop', encoding='ascii')
            try:
                fixture.wait(timeout=10)
            except subprocess.TimeoutExpired:
                fixture.kill()
                fixture.wait()


if __name__ == '__main__':
    main()
