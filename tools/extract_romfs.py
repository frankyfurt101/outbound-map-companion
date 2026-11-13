#!/usr/bin/env python3
"""List or extract files from the RomFS of a game package (NSP/NSZ) you own, using your own prod.keys.

Usage:
    python3 extract_romfs.py list    <prod.keys> <package.nsz|nsp> [substring ...]
    python3 extract_romfs.py extract <prod.keys> <package.nsz|nsp> <out-dir> <substring> [substring ...]

`list` prints "<size> <path>" for every RomFS file (optionally only paths containing one of the
substrings). `extract` writes every file whose path contains one of the substrings below <out-dir>,
keeping the RomFS folder structure (e.g. <out-dir>/Data/Managed/Metadata/global-metadata.dat).

Supported: an NSP/NSZ whose program NCA is stored as .ncz (compressed). A plain .nca program entry
is not handled by this script. Use the BASE game package for base-game files (an update package holds
only a patch). Key material is read but never printed. Needs the 'header_key' entry in prod.keys.
Python 3.14+ (compression.zstd) or the 'zstandard' package; plus 'cryptography'.
The argument parsing, folder-preserving output and 'zstandard' fallback were added when this was
adapted for sharing and were not re-run afterwards.
"""
import argparse
import hashlib
import os
import struct
import sys

try:
    from compression import zstd  # Python 3.14+

    def zstd_decompress(b):
        return zstd.decompress(b)
except ImportError:  # older Python: pip install zstandard
    try:
        import zstandard

        def zstd_decompress(b):
            return zstandard.ZstdDecompressor().decompressobj().decompress(b)
    except ImportError:
        sys.exit("error: need Python 3.14+ (compression.zstd) or 'pip install zstandard'")
try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
except ImportError:
    sys.exit("error: missing dependency 'cryptography' (pip install -r tools/requirements.txt)")


def load_keys(path):
    """Read a prod.keys file (name = hex). Values are never printed."""
    keys = {}
    try:
        text = open(path).read()
    except OSError as e:
        sys.exit("error: cannot read keys file %s: %s" % (path, e.strerror))
    for line in text.splitlines():
        if '=' in line:
            a, b = line.split('=', 1)
            try:
                keys[a.strip()] = bytes.fromhex(b.strip())
            except ValueError:
                pass
    return keys


def need(keys, name):
    if name not in keys:
        sys.exit("error: your keys file has no entry named '%s'" % name)
    return keys[name]


def dec(k, mode, b):
    c = Cipher(algorithms.AES(k), mode).decryptor(); return c.update(b) + c.finalize()
u32 = lambda b, o: struct.unpack_from('<I', b, o)[0]
u64 = lambda b, o: struct.unpack_from('<Q', b, o)[0]

def readat(f, o, n):
    f.seek(o); b = f.read(n); assert len(b) == n, 'short read'; return b

def pfs(read):
    h = read(0, 16); assert h[:4] == b'PFS0'; n, ss = struct.unpack_from('<II', h, 4)
    tab = read(16, n * 24 + ss); st = 16 + n * 24 + ss; out = []
    for i in range(n):
        off, size, so, _ = struct.unpack_from('<QQII', tab, 24 * i)
        out.append((tab[n * 24 + so:].split(b'\0', 1)[0].decode(), st + off, size))
    return out

def header(f, off, keys):
    b = readat(f, off, 0xc00)
    h = b''.join(dec(need(keys, 'header_key'), modes.XTS((i // 512).to_bytes(16, 'big')), b[i:i + 512]) for i in range(0, 0xc00, 512))
    assert h[0x200:0x204] in (b'NCA3', b'NCA2'); return h

class NCZ:
    def __init__(self, f, off, size):
        self.f, self.off = f, off
        f.seek(off + 0x4000); assert f.read(8) == b'NCZSECTN'; n = int.from_bytes(f.read(8), 'little')
        self.sections = []; total = 0
        for _ in range(n):
            d = f.read(64); start, ln, _c = struct.unpack_from('<QQQ', d); self.sections.append((start, ln, total)); total += ln
        self.start = f.tell() - off; magic = f.read(8); self.block = magic == b'NCZBLOCK'
        if self.block:
            h = f.read(16); exp = h[3]; count = u32(h, 4); self.total = u64(h, 8); self.bs = 1 << exp
            self.sizes = struct.unpack('<' + 'I' * count, f.read(count * 4)); data = f.tell() - off
            self.cum = [data]; [self.cum.append(self.cum[-1] + s) for s in self.sizes]; self.bcache = {}
        else:
            # solid zstd stream from self.start; decompress whole body once (bounded by package size)
            f.seek(off + self.start); self.plain = zstd_decompress(f.read(size - self.start))
    def get(self, offset, n):
        sec = next(s for s in self.sections if s[0] <= offset and offset + n <= s[0] + s[1])
        at = sec[2] + offset - sec[0]; end = at + n
        if not self.block: d = self.plain[at:end]
        else:
            parts = []
            for i in range(at // self.bs, (end - 1) // self.bs + 1):
                if i not in self.bcache:
                    ln = min(self.bs, self.total - i * self.bs); raw = readat(self.f, self.off + self.cum[i], self.sizes[i])
                    self.bcache[i] = zstd_decompress(raw) if len(raw) < ln else raw; assert len(self.bcache[i]) == ln
                parts.append(self.bcache[i])
            d = b''.join(parts)[at % self.bs: at % self.bs + n]
        assert len(d) == n; return d

def lz4_block(src, outlen):
    dst = bytearray(); i = 0; n = len(src)
    while i < n:
        tok = src[i]; i += 1; lit = tok >> 4
        if lit == 15:
            while True:
                b = src[i]; i += 1; lit += b
                if b != 255: break
        dst += src[i:i + lit]; i += lit
        if i >= n: break
        offs = src[i] | (src[i + 1] << 8); i += 2; ml = tok & 15
        if ml == 15:
            while True:
                b = src[i]; i += 1; ml += b
                if b != 255: break
        ml += 4; s = len(dst) - offs
        if offs >= ml: dst += dst[s:s + ml]
        else:
            for k in range(ml): dst.append(dst[s + k])
    assert len(dst) == outlen, (len(dst), outlen); return bytes(dst)




def walk_romfs(get, base, hd, files):
    dmo, dms, fmo, fms, dato = hd[3], hd[4], hd[7], hd[8], hd[9]
    dm = get(base + dmo, dms)
    fm = get(base + fmo, fms)

    def walk(doff, path):
        par, sib, child, fil, hsh, nl = struct.unpack_from('<6I', dm, doff)
        nm = dm[doff + 24:doff + 24 + nl].decode()
        p = path + nm + ('/' if nm else '')
        fo = fil
        while fo != 0xFFFFFFFF:
            fpar, fsib, fofs, fsz, fh_, fnl = struct.unpack_from('<IIQQII', fm, fo)
            files.append((p + fm[fo + 32:fo + 32 + fnl].decode(), fofs, fsz))
            fo = fsib
        c = child
        while c != 0xFFFFFFFF:
            walk(c, p)
            c = struct.unpack_from('<I', dm, c + 4)[0]
    walk(0, '')
    return dato


def safe_dest(outdir, rel):
    dst = os.path.normpath(os.path.join(outdir, *rel.split('/')))
    root = os.path.abspath(outdir)
    if not os.path.abspath(dst).startswith(root + os.sep):
        sys.exit("error: refusing path outside the output folder: " + rel)
    return dst


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog="See the module docstring (top of this file) for details. Key material is never printed.")
    sub = ap.add_subparsers(dest='mode', required=True)
    l = sub.add_parser('list', help='list RomFS files')
    l.add_argument('keys'); l.add_argument('package'); l.add_argument('patterns', nargs='*')
    e = sub.add_parser('extract', help='extract matching RomFS files')
    e.add_argument('keys'); e.add_argument('package'); e.add_argument('outdir'); e.add_argument('patterns', nargs='+')
    e.add_argument('--force', action='store_true', help='overwrite files that already exist in outdir')
    a = ap.parse_args()
    keys = load_keys(a.keys)
    found = False
    with open(a.package, 'rb') as f:
        entries = pfs(lambda o, n: readat(f, o, n))
        for name, off, size in entries:
            if not name.endswith('.ncz'):
                continue
            h = header(f, off, keys)
            if h[0x205] != 0:  # program content only
                continue
            get = NCZ(f, off, size).get
            for si in range(4):
                fh = h[0x400 + 0x200 * si:0x600 + 0x200 * si]
                st = u32(h, 0x240 + 0x10 * si) * 512
                if st == 0 or fh[3] != 3:
                    continue
                assert fh[8:12] == b'IVFC'
                l6off, l6size = struct.unpack_from('<QQ', fh, 8 + 0x10 + 5 * 0x18)
                base = st + l6off
                hd = struct.unpack('<10Q', get(base, 0x50))
                assert hd[0] == 0x50
                files = []
                dato = walk_romfs(get, base, hd, files)
                found = True
                sel = [x for x in files if not a.patterns or any(p in x[0] for p in a.patterns)]
                if a.mode == 'list':
                    for n_, o_, s_ in sorted(sel):
                        print(s_, n_)
                else:
                    for n_, o_, s_ in sel:
                        dst = safe_dest(a.outdir, n_)
                        if os.path.exists(dst) and not a.force:
                            sys.exit("error: %s already exists (use --force to overwrite)" % dst)
                        os.makedirs(os.path.dirname(dst), exist_ok=True)
                        with open(dst, 'wb') as w:
                            done = 0
                            while done < s_:
                                k = min(1 << 24, s_ - done)
                                w.write(get(base + dato + o_ + done, k))
                                done += k
                        print('wrote', s_, dst)
                    if not sel:
                        print('no file path matched', a.patterns, file=sys.stderr)
                break
            break
    if not found:
        sys.exit("error: no RomFS found. The package needs a program NCA stored as .ncz with a RomFS "
                 "(use the base game package, not an update).")


if __name__ == '__main__':
    main()
