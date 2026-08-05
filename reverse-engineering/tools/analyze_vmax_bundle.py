#!/usr/bin/env python3
from pathlib import Path
import hashlib
import tarfile
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")

for path in sorted(root.iterdir()):
    if path.is_file():
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        print(digest, path.stat().st_size, path.name)

source = root / "source.tar.gz.0"
if source.exists():
    print("\nsource.tar.gz.0 contents:")
    with tarfile.open(source, "r:gz") as archive:
        for member in archive.getmembers():
            print(member.name, member.size)
