"""Snapshot the exact receiving core for secret-free cross-private-repo CI tests.

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
changed = subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain', '--', 'crates/aurashare-core', 'Cargo.lock'], text=True).strip()
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
for name in ['Cargo.toml', 'src/lib.rs', 'examples/pairing_ci_server.rs']:
    target = core_target / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(core_source / name, target)
# Prune unrelated Tauri workspace entries while retaining the existing locked
# dependency versions. This is a developer-only sync operation, never a CI unlock.
subprocess.run(['cargo', '+1.90.0', 'metadata', '--manifest-path',
                str(destination / 'Cargo.toml'), '--format-version', '1'],
               stdout=subprocess.DEVNULL, check=True)
(destination / 'SOURCE.json').write_text(json.dumps({
    'repository': 'https://github.com/msayed-io/aurashare-desktop-server',
    'commit': sha,
    'purpose': 'Real Android production networking code against the exact Rust receiving core in secret-free CI. Test harness is not packaged in either application.',
}, indent=2) + '\n')
files = sorted(path for path in destination.rglob('*') if path.is_file())
(destination / 'SOURCE_SHA256SUMS').write_text(''.join(
    f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(destination).as_posix()}\n' for path in files
))
print(f'Snapshotted receiving core from {sha}: {len(files)} source files')
