"""Fail closed if the real receiving-core integration tests did not execute."""
from pathlib import Path
import xml.etree.ElementTree as ET

reports = list(Path('app/build/test-results/testDebugUnitTest').glob('TEST-*RealReceiverIntegrationTest*.xml'))
assert reports, 'RealReceiverIntegrationTest report missing; refusing release'
tests = 0
for report in reports:
    suite = ET.parse(report).getroot()
    assert int(suite.attrib.get('failures', '0')) == 0, report
    assert int(suite.attrib.get('errors', '0')) == 0, report
    assert int(suite.attrib.get('skipped', '0')) == 0, 'Real receiver tests were skipped'
    tests += int(suite.attrib.get('tests', '0'))
assert tests > 0, 'No real receiver tests ran'
print(f'PASS: {tests} real cross-application integration test(s) executed without skips')
