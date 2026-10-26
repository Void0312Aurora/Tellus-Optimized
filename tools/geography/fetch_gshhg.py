"""Explicit download/cache step for official GSHHG 2.3.7. Normal studies are offline."""
import argparse
import hashlib
import json
from pathlib import Path
import time
import urllib.request
import zipfile

URL='https://www.soest.hawaii.edu/pwessel/gshhg/gshhg-bin-2.3.7.zip'
ARCHIVE_SHA256='28600e8f7a08645aab43079326df6504212ec5ccb2b4bcf3b5f4f12ed60e82bc'

def fetch(directory):
    directory.mkdir(parents=True,exist_ok=True)
    archive=directory/'gshhg-bin-2.3.7.zip'
    if not archive.exists():
        temporary=archive.with_suffix('.partial')
        with urllib.request.urlopen(URL,timeout=40) as response, temporary.open('wb') as out:
            length=int(response.headers.get('Content-Length',0))
            received=0
            next_report=time.monotonic()+10
            while data:=response.read(1024*1024):
                out.write(data)
                received+=len(data)
                if time.monotonic()>=next_report:
                    print(f'Dataset download {received}/{length} bytes',flush=True)
                    next_report=time.monotonic()+10
        if length and received!=length:
            raise RuntimeError('truncated dataset download')
        temporary.replace(archive)
    digest=hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()
    if digest!=ARCHIVE_SHA256:
        raise RuntimeError('GSHHG archive differs from the recorded official-source snapshot')
    print(f'Archive SHA256 {digest}',flush=True)
    with zipfile.ZipFile(archive) as source:
        for item in source.infolist():
            name=Path(item.filename).name
            if name in ('gshhs_f.b','gshhs_h.b','README.TXT','LICENSE.TXT','COPYING.LESSERv3'):
                target=directory/name
                # Reading the whole member verifies the ZIP's CRC; names are
                # selected explicitly, so archive paths are never extracted.
                target.write_bytes(source.read(item))
                print(f'Cached {name} ({item.file_size} bytes)',flush=True)
    (directory/'download-manifest.json').write_text(json.dumps({'url':URL,'version':'2.3.7','archive_bytes':archive.stat().st_size,'archive_sha256':digest},indent=2)+'\n',encoding='utf-8')

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--cache',type=Path,default=Path('build/geography/gshhg-2.3.7'))
    fetch(parser.parse_args().cache)
