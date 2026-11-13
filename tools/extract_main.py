#!/usr/bin/env python3
"""Extract the game's main executable (NSO) from an NSP/NSZ you own into a flat memory image.

Usage:
    python3 extract_main.py <prod.keys> <package.nsp|nsz> <out.bin> [--force]
    e.g. out.bin = work/main.bin (an existing output is only overwritten with --force)

Use the UPDATE package of the build you play (for the live-position cheat: the Outbound v1.2.0
update, build id 9C6124686BE4AFFA). The output is a flat image: the decompressed text, ro and data
segments placed at their memory offsets (this is the input expected by tools/gen_live.py). The build id
and segment layout are printed; key material is never printed. If your keys cannot decrypt the
package (missing ticket / key entries) the script stops with a message naming the missing entry.
Python 3.14+ (compression.zstd) or the 'zstandard' package; plus 'cryptography'.

Status: adapted from the author's working script. The refactor into a function, the argument
parsing and the optional 'zstandard' fallback were not re-run after adapting.
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


def extract(keys, pkg, outp):
    with open(pkg, 'rb') as f:
        f.seek(0, 2); fsize = f.tell()
        entries = pfs(lambda o, n: readat(f, o, n)); tickets = {}
        for name, off, size in entries:
            if name.endswith('.tik'):
                t = readat(f, off, size)
                if t[:4] == b'\x04\x00\x01\x00' and t[0x281] == 0:
                    gen = t[0x285] or t[0x286]
                    tickets[t[0x2a0:0x2b0]] = dec(need(keys, f'titlekek_{max(gen - 1, 0):02x}'), modes.ECB(), t[0x180:0x190])
        for name, off, size in entries:
            if not name.endswith(('.nca', '.ncz')): continue
            h = header(f, off, keys)
            if h[0x205] != 0: continue  # program content only
            print('program NCA', name, 'title', f'{u64(h, 0x210):016X}')
            fh = h[0x400:0x600]; assert hashlib.sha256(fh).digest() == h[0x280:0x2a0]; assert fh[2] == 1 and fh[3] == 2
            section = u32(h, 0x240) * 512; po, ps = struct.unpack_from('<QQ', fh, 0x40)
            if name.endswith('.ncz'):
                get = NCZ(f, off, size).get
            else:
                enc = fh[4]; assert enc in (1, 3)
                if enc == 3:
                    rights = h[0x230:0x240]; gen = max(h[0x206], h[0x220]); ka = ['application', 'ocean', 'system'][h[0x207]]
                    key = tickets[rights] if any(rights) else dec(need(keys, f'key_area_key_{ka}_{max(gen - 1, 0):02x}'), modes.ECB(), h[0x300:0x340])[32:48]
                def get(o, n, enc=enc):
                    if enc == 1: return readat(f, off + o, n)
                    al = o // 16 * 16; sk = o - al; raw = readat(f, off + al, n + sk + (-(n + sk)) % 16)
                    return dec(key, modes.CTR(fh[0x140:0x148][::-1] + (al // 16).to_bytes(8, 'big')), raw)[sk:sk + n]
            exefs = pfs(lambda o, n: get(section + po + o, n))
            main = next(e for e in exefs if e[0] == 'main')
            nso = get(section + po + main[1], main[2]); assert nso[:4] == b'NSO0'
            flags = u32(nso, 0xC); segs = []
            for i, (ho, fo) in enumerate(((0x10, 0x60), (0x20, 0x64), (0x30, 0x68))):
                foff, moff, msize = struct.unpack_from('<III', nso, ho); fsz = u32(nso, fo)
                raw = nso[foff:foff + fsz]; data = lz4_block(raw, msize) if flags & (1 << i) else raw
                if flags & (8 << i): assert hashlib.sha256(data).digest() == nso[0xA0 + 32 * i:0xC0 + 32 * i], 'segment hash mismatch'
                segs.append((moff, data))
            img = bytearray(max(m + len(d) for m, d in segs))
            for m, d in segs: img[m:m + len(d)] = d
            with open(outp, 'wb') as w:
                w.write(img)
            print('build_id', nso[0x40:0x60].hex().upper(), 'cheat_bid', nso[0x40:0x48].hex().upper())
            print('segments', [(hex(m), hex(len(d))) for m, d in segs], 'hash_checked', bool(flags & 0x38))
            break




def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog="See the module docstring (top of this file) for details. Key material is never printed.")
    ap.add_argument('keys', help='your prod.keys')
    ap.add_argument('package', help='your .nsp / .nsz')
    ap.add_argument('out', help='output flat image, e.g. main.bin')
    ap.add_argument('--force', action='store_true', help='overwrite <out.bin> if it exists')
    a = ap.parse_args()
    if os.path.exists(a.out) and not a.force:
        sys.exit('error: %s already exists (use --force to overwrite)' % a.out)
    extract(load_keys(a.keys), a.package, a.out)


if __name__ == '__main__':
    main()
