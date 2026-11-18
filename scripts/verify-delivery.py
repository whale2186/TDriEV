#!/usr/bin/env python3
"""Verify a built Drive delivery against test evidence and an installed phone APK."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
TOOLS = Path('/mnt/hdd1/Android/Sdk/build-tools/35.0.0')


def command(*args):
    return subprocess.check_output(args, text=True)


def junit_counts(directory):
    roots = [ET.parse(path).getroot() for path in directory.glob('TEST-*.xml')]
    assert roots, f'No JVM results in {directory}'
    result = {key: sum(int(root.get(key, '0')) for root in roots)
              for key in ('tests', 'failures', 'errors', 'skipped')}
    assert result['failures'] == result['errors'] == result['skipped'] == 0, result
    return result


def instrumentation_count(path):
    text = path.read_text()
    match = re.search(r'OK \((\d+) tests?\)', text)
    assert match and 'FAILURES' not in text, f'Instrumentation did not pass: {path.name}'
    return int(match.group(1))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--version', default='0.2.6')
    args = parser.parse_args()
    assert re.fullmatch(r'\d+\.\d+\.\d+', args.version)
    tag = args.version.replace('.', '')
    evidence = ROOT / 'evidence'
    apk = ROOT / 'app/build/outputs/apk/owner/debug/app-owner-debug.apk'
    preview = ROOT / 'app/build/outputs/apk/preview/debug/app-preview-debug.apk'
    command(str(TOOLS / 'apksigner'), 'verify', str(apk))
    permissions = {}
    for name, path in (('owner', apk), ('preview', preview)):
        text = command(str(TOOLS / 'aapt'), 'dump', 'permissions', str(path))
        permissions[name] = re.findall(r"uses-permission: name='([^']+)'", text)
        assert not any(value in permissions[name] for value in (
            'android.permission.READ_SMS', 'android.permission.RECEIVE_SMS',
            'android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_COARSE_LOCATION'))
    assert 'android.permission.INTERNET' in permissions['owner']
    assert 'android.permission.INTERNET' not in permissions['preview']
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    package = 'dev.nemesis.drive'
    adb = lambda *items: command('adb', '-s', args.serial, *items)
    installed = adb('shell', 'dumpsys', 'package', package)
    installed_version = re.search(r'versionName=(\S+)', installed).group(1)
    assert installed_version == args.version
    code = int(re.search(r'versionCode=(\d+)', installed).group(1))
    remote_paths = adb('shell', 'pm', 'path', package).splitlines()
    assert len(remote_paths) == 1 and remote_paths[0].startswith('package:')
    remote_digest = adb('shell', 'sha256sum', remote_paths[0][len('package:'):]).split()[0]
    assert remote_digest == digest, 'Installed APK does not match the deliverable'
    assert not adb('shell', 'pm', 'list', 'packages', package + '.test').strip()
    phone_log = evidence / f'drive-{tag}-phone-read-only.txt'
    assert instrumentation_count(phone_log) == 1
    assert 'garage_refresh_retains_observation=true' in phone_log.read_text()
    core = junit_counts(ROOT / 'core/build/test-results/test')
    runtime = junit_counts(ROOT / 'app/build/test-results/testOwnerDebugUnitTest')
    total = core['tests'] + runtime['tests']
    assert total == 94
    owner_count = instrumentation_count(evidence / f'drive-{tag}-owner-tests.txt')
    preview_count = instrumentation_count(evidence / f'drive-{tag}-preview-tests.txt')
    assert owner_count == 20 and preview_count == 5
    target = ROOT / 'artifacts' / f'drive-{args.version}.apk'
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(apk, target)
    assert hashlib.sha256(target.read_bytes()).hexdigest() == digest
    target.with_suffix('.apk.sha256').write_text(f'{digest}  {target.name}\n')
    result = {
        'verified_at_utc': datetime.now(timezone.utc).isoformat(),
        'package': package, 'version': installed_version, 'version_code': code,
        'artifact': str(target.relative_to(ROOT)), 'bytes': target.stat().st_size,
        'sha256': digest, 'signature_valid': True, 'installed_apk_matches_artifact': True,
        'permissions': permissions, 'core_jvm': core, 'runtime_jvm': runtime,
        'jvm_total': total, 'owner_android_tests': owner_count,
        'preview_android_tests': preview_count, 'phone_read_only_tests': 1,
        'phone_garage_refresh_retains_observation': True,
        'phone_test_helper_absence_verified': True,
        'agent_sent_live_wake_or_control_during_diagnosis': False,
        'earlier_lock_physical_outcome': 'unverified',
        'safety_guard_on_last_passive_read': 'unresolved',
        'evidence': [f'drive-{tag}-{name}' for name in (
            'build.log', 'owner-tests.txt', 'preview-tests.txt', 'phone-read-only.txt')],
    }
    (evidence / f'verification-{args.version}.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
