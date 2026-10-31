#!/usr/bin/env python3
"""Real install -r regression check on an isolated emulator-only package."""
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'it.voicescribe.pro.updatetest'


def run(*command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)


def main():
    # Never install or uninstall the real application during this check.
    assert PACKAGE.endswith('.updatetest')
    with tempfile.TemporaryDirectory(prefix='voicescribe-upgrade-test-') as folder:
        temp = Path(folder)
        env = os.environ.copy()
        env['VOICESCRIBE_TEST_KEYSTORE_FILE'] = str(temp / 'test.p12')
        env['VOICESCRIBE_TEST_KEYSTORE_PASSWORD'] = secrets.token_urlsafe(32)
        run('keytool', '-genkeypair', '-noprompt', '-keystore', env['VOICESCRIBE_TEST_KEYSTORE_FILE'],
            '-storetype', 'PKCS12', '-alias', 'voicescribe', '-keyalg', 'RSA', '-keysize', '2048',
            '-validity', '2', '-dname', 'CN=VoiceScribe isolated update test',
            '-storepass:env', 'VOICESCRIBE_TEST_KEYSTORE_PASSWORD',
            '-keypass:env', 'VOICESCRIBE_TEST_KEYSTORE_PASSWORD', env=env, capture_output=True)
        wrapper = ['java', '-classpath', 'gradle/wrapper/gradle-wrapper.jar',
                   'org.gradle.wrapper.GradleWrapperMain', '--no-daemon', ':app:assembleUpgradeTest']
        for version in (25, 26):
            env['VOICESCRIBE_VERSION_CODE'] = str(version)
            run(*wrapper, cwd=ROOT / 'android', env=env)
            source = ROOT / 'android/app/build/outputs/apk/upgradeTest/app-upgradeTest.apk'
            shutil.copyfile(source, temp / f'v{version}.apk')
        try:
            run('adb', 'install', str(temp / 'v25.apk'))
            for marker in ('files/session-update-check/audio.wav',
                           'shared_prefs/update-check.xml', 'app_webview/update-check'):
                # The marker covers native session files, preferences and WebView data.
                parent = str(Path(marker).parent)
                command = f'mkdir -p {parent} && echo preserved-after-update > {marker}'
                run('adb', 'shell', 'run-as', PACKAGE, 'sh', '-c', f"'{command}'")
            run('adb', 'install', '-r', str(temp / 'v26.apk'))
            for marker in ('files/session-update-check/audio.wav',
                           'shared_prefs/update-check.xml', 'app_webview/update-check'):
                result = run('adb', 'shell', 'run-as', PACKAGE, 'cat', marker,
                             capture_output=True, text=True)
                assert result.stdout.strip() == 'preserved-after-update', marker
            info = run('adb', 'shell', 'dumpsys', 'package', PACKAGE, capture_output=True, text=True)
            assert 'versionCode=26 ' in info.stdout, 'Installed package version did not increase'
            print('PASS: Android accepted the APK update and preserved all three private data markers')
        finally:
            run('adb', 'uninstall', PACKAGE, capture_output=True)


if __name__ == '__main__':
    main()
