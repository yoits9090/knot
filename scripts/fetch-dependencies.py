#!/usr/bin/env python3
"""Fetch the pinned Java numerical library, verifying its SHA-256."""
import hashlib
from pathlib import Path
import urllib.request

NAME = "commons-math3-3.6.1.jar"
SHA256 = "1e56d7b058d28b65abd256b8458e3885b674c1d588fa43cd7d1cbb9c7ef2b308"
URL = "https://repo.maven.apache.org/maven2/org/apache/commons/commons-math3/3.6.1/" + NAME
target = Path(__file__).resolve().parents[1] / "build" / "deps" / NAME
if target.exists():
    data = target.read_bytes()
else:
    with urllib.request.urlopen(URL, timeout=60) as response:
        data = response.read()
if hashlib.sha256(data).hexdigest() != SHA256:
    raise SystemExit("Commons Math checksum mismatch; refusing dependency")
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(data)
print("Verified " + NAME)
