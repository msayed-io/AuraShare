"""Validate the signed APK using Android SDK tools before creating release assets."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess


def checked(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


apk = Path('app/build/outputs/apk/release/app-release.apk')
assert apk.is_file() and apk.stat().st_size > 0, 'Installable release APK missing'
tools = Path(os.environ['ANDROID_HOME']) / 'build-tools' / '36.0.0'
report = checked(str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk))
match = re.search(r'^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)$', report, re.MULTILINE)
assert match, 'APK must have a verified signing certificate'
certificate = match.group(1).lower()
expected_certificate = Path('.github/android-release-certificate.sha256').read_text().strip()
assert certificate == expected_certificate, 'Unexpected APK signing identity'
badging = checked(str(tools / 'aapt'), 'dump', 'badging', str(apk))
package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.MULTILINE)
assert package, 'Unable to inspect APK manifest'
assert package.group(1) == 'com.aistudio.aurashare.vzkqnm', 'Application ID changed'
assert package.group(2) == os.environ['AURASHARE_VERSION_CODE'], 'Wrong versionCode'
assert package.group(3) == os.environ['AURASHARE_VERSION_NAME'], 'Wrong versionName'
assert "sdkVersion:'24'" in badging, 'Unexpected minimum Android SDK'
assert re.search(r'^launchable-activity:', badging, re.MULTILINE), 'APK has no launcher activity'

dist = Path('dist')
dist.mkdir(exist_ok=True)
asset = os.environ['RELEASE_ASSET']
assert re.fullmatch(r'AuraShare-\d+\.\d+\.\d+\.apk', asset), 'Unsafe asset name'
output = dist / asset
shutil.copyfile(apk, output)
digest = hashlib.sha256(output.read_bytes()).hexdigest()
metadata = {
    'asset': asset,
    'applicationId': package.group(1),
    'versionName': package.group(3),
    'versionCode': int(package.group(2)),
    'minSdk': 24,
    'signingCertificateSha256': certificate,
    'sha256': digest,
    'commit': os.environ['GITHUB_SHA'],
    'runId': os.environ['GITHUB_RUN_ID'],
    'tag': os.environ['RELEASE_TAG'],
}
metadata_path = dist / 'release.json'
metadata_path.write_text(json.dumps(metadata, indent=2) + '\n')
metadata_digest = hashlib.sha256(metadata_path.read_bytes()).hexdigest()
(dist / 'SHA256SUMS').write_text(f'{digest}  {asset}\n{metadata_digest}  release.json\n')
print(json.dumps(metadata, indent=2))
