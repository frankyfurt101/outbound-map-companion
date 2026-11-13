#!/usr/bin/env python3
"""Derive the Outbound save key from your own copy of the game's global-metadata.dat.

Usage:
    python3 derive_key.py <global-metadata.dat> <out.key> [--force] [--verify <save file>]

The input must be the BASE game's (v0, un-updated) Data/Managed/Metadata/global-metadata.dat,
extracted from your own dump (see tools/extract_romfs.py). The file is checked against a known
SHA-256 and size first; anything else is refused. The 32-byte key is a string literal inside the
file (literal index 29457). It is written raw (32 bytes) to <out.key> with mode 600 and is
NEVER printed. Keep it private and do not publish it.

--verify <save file> (optional, needs 'cryptography'): Base64-decodes the save, decrypts it with the
derived key (AES-256-CBC, zero IV), strips the PKCS7 padding and checks the result parses as JSON. It
prints only a pass/fail line, never key bytes or save contents.

Status: the derivation has been run successfully on the real base-game file; the owner verified that
the key decodes a save.
"""
import argparse
import hashlib
import os
import struct
import sys

EXPECTED_SHA256 = "2b95d26e315ad3d87ff63d2f4af6a3df036dfd6ea3e336845842d03b95580087"
EXPECTED_SIZE = 13715712
LITERAL_INDEX = 29457
KEY_LEN = 32


def die(msg):
    print("error: " + msg, file=sys.stderr)
    sys.exit(1)


def verify(key, save_path):
    """Decrypt a save with [key] and check it is JSON. Prints only a pass/fail line."""
    try:
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    except ImportError:
        die("--verify needs the 'cryptography' package (pip install -r tools/requirements.txt)")
    import base64
    import binascii
    import json
    try:
        with open(save_path, "rb") as f:
            raw = f.read()
    except OSError as e:
        die("cannot read save file %s: %s" % (save_path, e.strerror))
    try:
        blob = base64.b64decode(raw, validate=False)
        if not blob or len(blob) % 16:
            raise ValueError("size")
        dec = Cipher(algorithms.AES(key), modes.CBC(bytes(16))).decryptor()
        plain = dec.update(blob) + dec.finalize()
        pad = plain[-1]
        if not 1 <= pad <= 16 or plain[-pad:] != bytes([pad]) * pad:
            raise ValueError("padding")
        json.loads(plain[:-pad].decode("utf-8"))
    except (binascii.Error, ValueError, UnicodeDecodeError):
        die("key NOT verified: the save did not decrypt to valid JSON with this key "
            "(wrong key, or the file is not a save from this game)")
    print("key verified against save")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                 epilog="Writes 32 raw bytes to <out.key> (mode 600). The key is never printed.")
    ap.add_argument("metadata", help="path to the base game's global-metadata.dat")
    ap.add_argument("out", help="output key file, e.g. secrets/outbound.key")
    ap.add_argument("--force", action="store_true", help="overwrite <out.key> if it exists")
    ap.add_argument("--verify", metavar="SAVE", help="after deriving, check the key decrypts this save file (prints only pass/fail)")
    a = ap.parse_args()

    try:
        size = os.path.getsize(a.metadata)
    except OSError as e:
        die("cannot read %s: %s" % (a.metadata, e.strerror))
    if size != EXPECTED_SIZE:
        die("wrong game version/file: size is %d bytes, expected %d. Use the base game's (v0) "
            "global-metadata.dat, not the one from an update." % (size, EXPECTED_SIZE))
    with open(a.metadata, "rb") as f:
        data = f.read()
    if hashlib.sha256(data).hexdigest() != EXPECTED_SHA256:
        die("wrong game version/file: SHA-256 does not match the expected base-game "
            "global-metadata.dat. Use the base game's (v0) file.")

    lit_off, _ = struct.unpack_from("<II", data, 8)
    lit_data_off, lit_data_size = struct.unpack_from("<II", data, 16)
    entry = lit_off + LITERAL_INDEX * 8
    if entry + 8 > len(data):
        die("string literal table is out of range; file layout is not as expected")
    length, rel = struct.unpack_from("<II", data, entry)
    if length != KEY_LEN:
        die("unexpected key length %d (expected %d); file layout is not as expected" % (length, KEY_LEN))
    if rel + length > lit_data_size or lit_data_off + rel + length > len(data):
        die("key literal is out of range; file layout is not as expected")
    key = data[lit_data_off + rel: lit_data_off + rel + length]
    assert len(key) == KEY_LEN

    if os.path.exists(a.out) and not a.force:
        die("%s already exists (use --force to overwrite)" % a.out)
    parent = os.path.dirname(os.path.abspath(a.out))
    os.makedirs(parent, mode=0o700, exist_ok=True)
    fd = os.open(a.out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as w:
        w.write(key)
    os.chmod(a.out, 0o600)
    print("wrote %d bytes to %s (mode 600). Keep it private; do not share it." % (KEY_LEN, a.out))
    if a.verify:
        verify(key, a.verify)


if __name__ == "__main__":
    main()
