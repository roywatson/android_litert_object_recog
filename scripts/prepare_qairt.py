#!/usr/bin/env python3
"""Prepare only Android HTP libraries for the two benchmark SoCs from pinned QAIRT.

Use --download for the public official archive, or --sdk-zip for a local copy.
HTTP range reads avoid downloading the entire 2.6 GB SDK. Zip CRCs, response ranges
and ETag consistency are checked; extracted SHA-256 hashes are recorded locally.
Vendor binaries and their notices stay in ignored .local/ and are not project code.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import urllib.request
import zipfile

VERSION = '2.50.0.260828'
URL = ('https://softwarecenter.qualcomm.com/api/download/software/sdks/'
       f'Qualcomm_AI_Runtime_Community/All/{VERSION}/v{VERSION}.zip')
ROOT = Path(__file__).resolve().parents[1]
PREFIX = f'qairt/{VERSION}/'
LIBRARIES = [
    'lib/aarch64-android/libQnnSystem.so',
    'lib/aarch64-android/libQnnHtp.so',
    'lib/aarch64-android/libQnnHtpPrepare.so',
    *[f'lib/aarch64-android/libQnnHtpV{v}Stub.so' for v in (69, 79)],
    *[f'lib/hexagon-v{v}/unsigned/libQnnHtpV{v}Skel.so' for v in (69, 79)],
]


class RemoteZip(io.RawIOBase):
    def __init__(self, url):
        self.url, self.position, self.etag = url, 0, None
        _, self.size = self._fetch(0, 0)

    def _fetch(self, first, last):
        request = urllib.request.Request(self.url, headers={'Range': f'bytes={first}-{last}'})
        with urllib.request.urlopen(request, timeout=120) as response:
            match = re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)', response.headers.get('Content-Range', ''))
            if response.status != 206 or not match or tuple(map(int, match.groups()[:2])) != (first, last):
                raise RuntimeError('Server did not honor the requested byte range')
            etag = response.headers.get('ETag')
            if self.etag is not None and etag != self.etag:
                raise RuntimeError('Archive changed while reading')
            self.etag = etag
            data = response.read(last - first + 2)
            if len(data) != last - first + 1:
                raise RuntimeError('Truncated archive response')
            return data, int(match.group(3))

    def seekable(self): return True
    def readable(self): return True
    def tell(self): return self.position

    def seek(self, offset, whence=0):
        position = offset if whence == 0 else self.position + offset if whence == 1 else self.size + offset
        if position < 0: raise ValueError('Negative position')
        self.position = position
        return position

    def read(self, size=-1):
        size = min(self.size - self.position, size if size >= 0 else self.size)
        if size <= 0: return b''
        data, total = self._fetch(self.position, self.position + size - 1)
        if total != self.size: raise RuntimeError('Archive size changed')
        self.position += len(data)
        return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--download', action='store_true')
    source.add_argument('--sdk-zip', type=Path)
    parser.add_argument('--inspect', action='store_true', help='Read archive notices and selected file list only')
    parser.add_argument('--output', type=Path, default=ROOT / '.local/qairt' / VERSION)
    args = parser.parse_args()
    with (RemoteZip(URL) if args.download else args.sdk_zip.open('rb')) as stream:
        with zipfile.ZipFile(stream) as archive:
            notices = [name for name in archive.namelist() if name.startswith(PREFIX)
                       and name.count('/') <= 3 and not name.endswith('/')
                       and any(word in Path(name).name.lower() for word in ('license', 'notice', 'agreement'))]
            if not notices: raise RuntimeError('Vendor notices missing; inspect archive before using it')
            for name in notices:
                content = archive.read(name)
                if args.inspect:
                    print(name, '\n', content.decode('utf8', errors='replace')[:40000] if not name.endswith('.pdf') else '[PDF license; copied into the local notices directory during preparation]')
            for relative in LIBRARIES:
                info = archive.getinfo(PREFIX + relative)
                print(f'{relative}: {info.file_size:,} bytes')
            if args.inspect: return
            # Read and validate the complete selection before changing the output.
            files = {Path(relative).name: archive.read(PREFIX + relative) for relative in LIBRARIES}
            if not all(content[:4] == b'\x7fELF' for content in files.values()):
                raise RuntimeError('Expected ELF shared libraries')
            expected = json.loads((ROOT / 'scripts/qairt-2.50.0.260828-checksums.json').read_text())
            for name, content in files.items():
                if hashlib.sha256(content).hexdigest() != expected['files'][name]['sha256']:
                    raise RuntimeError(f'Pinned QAIRT checksum mismatch: {name}')
            output = args.output.resolve()
            (output / 'arm64-v8a').mkdir(parents=True, exist_ok=True)
            manifest = {'qairt_version': VERSION, 'source_url': URL, 'archive_etag': getattr(stream, 'etag', None), 'files': {}}
            for name, content in files.items():
                (output / 'arm64-v8a' / name).write_bytes(content)
                manifest['files'][name] = {'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest()}
            (output / 'notices').mkdir(exist_ok=True)
            for index, name in enumerate(notices):
                (output / 'notices' / f'{index}-{Path(name).name}').write_bytes(archive.read(name))
            (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
            (output / 'provenance').mkdir(exist_ok=True)
            (output / 'provenance/npu-runtime.json').write_text(json.dumps(manifest, indent=2) + '\n')
            print('Prepared:', output)
            print(f'Build with: ./gradlew -PqualcommNpuDir={output} :app:assembleDebug')


if __name__ == '__main__':
    main()
