"""Snapshot the exact secure receiving core for secret-free Android CI integration tests.

Usage: python3 .github/scripts/sync_desktop_fixture.py /path/to/desktop/repository
Run after committing the desktop changes. No authentication material is copied.
"""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

source = Path(sys.argv[1]).resolve()
root = Path(__file__).resolve().parents[2]
destination = root / 'integration' / 'desktop-core'
sha = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
changed = subprocess.check_output(
    ['git', '-C', str(source), 'status', '--porcelain', '--', 'crates/aurashare-core', 'Cargo.lock'],
    text=True,
).strip()
if changed:
    raise SystemExit('Commit the desktop core first; refusing an untraceable fixture.')
if destination.exists():
    shutil.rmtree(destination)
destination.mkdir(parents=True)
(destination / 'Cargo.toml').write_text('[workspace]\nmembers = ["crates/aurashare-core"]\nresolver = "2"\n')
shutil.copy2(source / 'Cargo.lock', destination / 'Cargo.lock')
shutil.copy2(source / 'LICENSE', destination / 'LICENSE')
core_source = source / 'crates/aurashare-core'
core_target = destination / 'crates/aurashare-core'
for name in ['Cargo.toml', 'src/lib.rs', 'src/secure.rs', 'src/protected_store.rs']:
    target = core_target / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(core_source / name, target)
# The desktop's production protected store uses Windows DPAPI. The Android-only
# CI snapshot runs on Linux, so add an in-memory adapter guarded by its test env.
protected_store = core_target / 'src/protected_store.rs'
protected_text = protected_store.read_text()
protected_text = protected_text.replace('#[cfg(not(windows))]\nuse anyhow::anyhow;\n', '')
start_marker = '#[cfg(not(windows))]\npub fn load'
end_marker = '#[cfg(all(test, windows))]'
start = protected_text.find(start_marker)
end = protected_text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('Could not locate the expected non-Windows protected-store fallback.')
overlay = (root / '.github' / 'scripts' / 'ci-linux-protected-store.rs').read_text()
protected_store.write_text(protected_text[:start] + overlay + '\n\n' + protected_text[end:])
# Keep the CI's established example target name while using the secure-only harness.
example = core_target / 'examples/pairing_ci_server.rs'
example.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(core_source / 'examples/secure_ci_server.rs', example)
# The desktop lockfile also covers the desktop-app workspace member. Regenerate the lock for this
# deliberately smaller fixture so CI can build the exact core with `cargo --locked`.
subprocess.run([
    'cargo', '+1.90.0', 'generate-lockfile', '--manifest-path', str(destination / 'Cargo.toml')
], check=True)
(destination / 'SOURCE.json').write_text(json.dumps({
    'repository': 'https://github.com/msayed-io/aurashare-desktop',
    'commit': sha,
    'purpose': 'Real Android production networking code against the exact TLS 1.3 secure receiving core in secret-free CI. Test harness is not packaged in either application.',
    'ci_overlay': 'The Linux-only receiving fixture uses a process-memory protected-store adapter gated by AURASHARE_INTEGRATION_TEST=1; desktop production source and Windows DPAPI remain unchanged.',
}, indent=2) + '\n')
files = sorted(path for path in destination.rglob('*') if path.is_file())
(destination / 'SOURCE_SHA256SUMS').write_text(''.join(
    f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(destination).as_posix()}\n' for path in files
))
print(f'Snapshotted secure receiving core from {sha}: {len(files)} source files')
