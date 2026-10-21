"""Require the actual Android regression suites, with no failed or skipped tests."""
from pathlib import Path
import xml.etree.ElementTree as ET

required = {
    'com.example.AuraShareRealReceiverIntegrationTest',
    'com.example.MediaAccessRepositoryTest',
    'com.example.MediaPermissionApi29RegressionTest',
    'com.example.MediaPermissionApi33RegressionTest',
    'com.example.MediaPermissionApi34RegressionTest',
    'com.example.VideoThumbnailRegressionTest',
    'com.example.FloatingChromeTest',
    'com.example.FloatingMediaContentTest',
}
reports = list(Path('app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
assert reports, 'Android unit test reports missing'
seen = set()
total = 0
for report in reports:
    suite = ET.parse(report).getroot()
    for key in ('failures', 'errors', 'skipped'):
        assert int(suite.get(key, '0')) == 0, (report, key)
    count = int(suite.get('tests', '0'))
    if count:
        seen.add(suite.get('name'))
    total += count
assert required <= seen, f'Required regression suites missing: {sorted(required - seen)}'
print(f'PASS: {total} Android unit/integration tests; required media, files, permissions and capsule suites ran with no skips')
