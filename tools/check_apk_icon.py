#!/usr/bin/env python3
"""Require an extractable bitmap application icon before uploading an APK."""
import argparse
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile


def check(apk, aapt):
    result = subprocess.run([str(aapt), 'dump', 'badging', str(apk)], check=True, capture_output=True, text=True)
    match = re.search(r"^application: .* icon='([^']*)'", result.stdout, re.MULTILINE)
    if not match:
        raise ValueError('APK does not declare an application icon')
    path = match.group(1)
    if not path.endswith('.png'):
        raise ValueError('Application icon is not an extractable PNG; distribution sites may show a placeholder')
    with zipfile.ZipFile(apk) as archive:
        image = archive.read(path)
    if image[:8] != b'\x89PNG\r\n\x1a\n' or image[12:16] != b'IHDR':
        raise ValueError('Application icon is not valid PNG data')
    width, height = struct.unpack('>II', image[16:24])
    if min(width, height) < 48:
        raise ValueError('Application icon is smaller than the base launcher size')
    print(f'APK bitmap icon verified: {path} ({width} × {height})')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--aapt', type=Path)
    args = parser.parse_args()
    aapt = args.aapt
    if aapt is None:
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
        if sdk:
            candidates = list(Path(sdk).glob('build-tools/*/aapt'))
            candidates.sort(key=lambda p: tuple(int(x) for x in re.findall(r'\d+', p.parent.name)), reverse=True)
            aapt = candidates[0] if candidates else None
        if aapt is None:
            found = shutil.which('aapt')
            aapt = Path(found) if found else None
    if aapt is None:
        parser.error('Set ANDROID_HOME or pass --aapt /path/to/aapt')
    try:
        check(args.apk, aapt)
    except (ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f'APK icon check failed: {error}\n')


if __name__ == '__main__':
    main()
