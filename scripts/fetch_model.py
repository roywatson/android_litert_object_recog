#!/usr/bin/env python3
"""Restore the exact checked-in model from Google's versioned public endpoint."""
import hashlib
import urllib.request
import zipfile
from verify_model import ASSETS, MODEL, SHA256, URL, verify

ASSETS.mkdir(parents=True, exist_ok=True)
with urllib.request.urlopen(URL, timeout=120) as response:
    data = response.read()
if hashlib.sha256(data).hexdigest() != SHA256:
    raise SystemExit('Download checksum mismatch; existing model was not changed')
MODEL.write_bytes(data)
with zipfile.ZipFile(MODEL) as archive:
    (ASSETS / 'labels.txt').write_bytes(archive.read('labels_without_background.txt'))
verify()
