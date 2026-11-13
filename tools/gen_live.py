#!/usr/bin/env python3
"""Generate the Eden "live position" cheat for Outbound, build id 9C6124686BE4AFFA only.

Usage:
    python3 gen_live.py <main.bin> [--out work/cheat.txt] [--mount Save] [--path persistentDataPath/livepos.bin]
                        [--cave-out work/cave.bin] [--force]

An existing output file is only overwritten with --force. --mount and --path are restricted to
A-Z a-z 0-9 _ . / : - because they are spliced into assembler source.

<main.bin> is YOUR OWN extracted main executable of the game build with build id 9C6124686BE4AFFA
(Outbound v1.2.0 update, v524288), as produced by tools/extract_main.py. Required format: a FLAT
image of the decompressed NSO, i.e. text, ro and data segments placed at their memory offsets
(offset 0 = start of .text), with the zero padding after .text left in place (the code cave lives
there, at 0x05000600). The script refuses to run when the instruction at the hook address does not
match the expected original word or the cave area is not empty, which means "wrong build".

What the generated cheat does: it hooks Player.Update (0x01C670B4) and jumps to a small code cave
which, about twice a second, writes 16 bytes to <mount>:/<path> (default Save:/persistentDataPath/livepos.bin):
float32 x, y, z of the player transform position and int32 playerState (1 = driving, 6 = passenger).
Then it runs the replaced instruction and returns. Nothing else in the game is changed.

All function offsets inside the script are for that one build. On any other build the cheat is
wrong and may crash the game.

Needs: clang with the AArch64 target and llvm-objdump (Xcode command line tools on macOS provide
both; on Linux install clang and llvm). Set LLVM_OBJDUMP to override the llvm-objdump location.
"""
import argparse
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile

HOOK = 0x01C670B4          # Player.Update: ldp s2,s1,[x19,#0xb8]
HOOK_ORIG = 0x2D570662     # original instruction word at HOOK
CAVE = 0x05000600          # zero padding after .text
F = dict(tick=0x01B08F20, get_transform=0x04C391D0, get_position=0x04C552F0,
         open=0x01B08C20, write=0x01B08C80, close=0x01B08C40)
CHEAT_NAME = "Live position - map feed"
SAFE_ARG = re.compile(r"^[A-Za-z0-9_./:-]+$")

ASM = """
.text
.org 0
cave:
  stp x29, x30, [sp, #-0x40]!
  mov x29, sp
  bl  f_tick
  and x8, x0, #0x7fffff
  mov x9, #0xa0000
  cmp x8, x9
  b.hs skip
  mov x0, x19
  mov x1, xzr
  bl  f_get_transform
  mov x1, xzr
  bl  f_get_position
  stp s0, s1, [sp, #0x18]
  str s2, [sp, #0x20]
  ldr w8, [x19, #0x138]
  str w8, [sp, #0x24]
  add x0, sp, #0x10
  adr x1, path
  mov w2, #2
  bl  f_open
  cbnz w0, skip
  mov w8, #1
  str w8, [sp, #0x30]
  ldr x0, [sp, #0x10]
  mov x1, xzr
  add x2, sp, #0x18
  mov x3, #16
  add x4, sp, #0x30
  bl  f_write
  ldr x0, [sp, #0x10]
  bl  f_close
skip:
  ldp x29, x30, [sp], #0x40
  ldp s2, s1, [x19, #0xb8]
  b   f_back
path:
  .asciz "%s"
  .balign 4
end:
"""


def find_objdump():
    env = os.environ.get("LLVM_OBJDUMP")
    if env:
        return env
    for name in ("llvm-objdump", "llvm-objdump-18", "llvm-objdump-17", "llvm-objdump-16", "llvm-objdump-15"):
        p = shutil.which(name)
        if p:
            return p
    if shutil.which("xcrun"):
        try:
            p = subprocess.check_output(["xcrun", "--find", "llvm-objdump"], stderr=subprocess.DEVNULL).decode().strip()
            if p:
                return p
        except subprocess.CalledProcessError:
            pass
    sys.exit("error: llvm-objdump not found. Install LLVM (or Xcode command line tools) or set LLVM_OBJDUMP.")


def assemble(path_str):
    """Assemble the cave; return its machine code with branch targets patched for CAVE/HOOK."""
    if not shutil.which("clang"):
        sys.exit("error: clang not found (needs the AArch64 target). Install Xcode command line tools or LLVM/clang.")
    objdump = find_objdump()
    syms = {('f_' + k): v - CAVE for k, v in F.items()}
    syms['f_back'] = HOOK + 4 - CAVE
    d = tempfile.mkdtemp()
    try:
        s, o = os.path.join(d, 'c.s'), os.path.join(d, 'c.o')
        with open(s, 'w') as fh:
            fh.write(ASM % path_str)
        subprocess.check_call(['clang', '-target', 'aarch64-linux-gnu', '-c', s, '-o', o])
        out = subprocess.check_output([objdump, '-d', '-r', '--no-show-raw-insn', o]).decode()
        elf = open(o, 'rb').read()
    finally:
        shutil.rmtree(d, ignore_errors=True)
    # find the .text section in the object file
    shoff = struct.unpack_from('<Q', elf, 0x28)[0]
    shentsize, shnum, shstrndx = struct.unpack_from('<HHH', elf, 0x3A)

    def sh(i):
        return struct.unpack_from('<IIQQQQIIQQ', elf, shoff + i * shentsize)
    stro = sh(shstrndx)[4]
    code = None
    for i in range(shnum):
        h = sh(i)
        nm = elf[stro + h[0]:elf.index(b'\0', stro + h[0])].decode()
        if nm == '.text':
            code = bytearray(elf[h[4]:h[4] + h[5]])
    if not code:
        sys.exit("error: assembled object has no .text section")
    # resolve external branch targets by hand using the relocations
    for line in out.splitlines():
        r = re.match(r'\s*([0-9a-f]+):\s+R_AARCH64_(CALL26|JUMP26)\s+(\S+)', line)
        if r:
            off = int(r.group(1), 16)
            tgt = syms[r.group(3)]
            w = struct.unpack_from('<I', code, off)[0]
            imm = ((tgt - off) // 4) & 0x3FFFFFF
            struct.pack_into('<I', code, off, (w & 0xFC000000) | imm)
    return bytes(code)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter,
                                 epilog="Only valid for build id 9C6124686BE4AFFA. See the top of this file for the required input format.")
    ap.add_argument("main_bin", help="flat image of your own extracted main for build 9C6124686BE4AFFA (see tools/extract_main.py)")
    ap.add_argument("--out", default="work/cheat.txt", help="cheat file to write (default: work/cheat.txt)")
    ap.add_argument("--cave-out", help="also write the raw cave machine code here")
    ap.add_argument("--mount", default="Save", help="Eden save mount name (default: Save)")
    ap.add_argument("--path", default="persistentDataPath/livepos.bin", help="file below the mount (default: persistentDataPath/livepos.bin)")
    ap.add_argument("--force", action="store_true", help="overwrite --out / --cave-out if they exist")
    a = ap.parse_args()
    for opt, val in (("--mount", a.mount), ("--path", a.path)):
        if not SAFE_ARG.match(val):
            sys.exit("error: %s may only contain letters, digits and _ . / : - (got %r)" % (opt, val))
    for out in (a.out, a.cave_out):
        if out and os.path.exists(out) and not a.force:
            sys.exit("error: %s already exists (use --force to overwrite)" % out)

    try:
        img = open(a.main_bin, 'rb').read()
    except OSError as e:
        sys.exit("error: cannot read %s: %s" % (a.main_bin, e.strerror))
    if len(img) < CAVE + 0x100 or len(img) <= HOOK + 4:
        sys.exit("error: image is too small; expected a flat image of the decompressed main (see tools/extract_main.py)")
    if struct.unpack_from('<I', img, HOOK)[0] != HOOK_ORIG:
        sys.exit("error: hook original instruction mismatch: this is not build 9C6124686BE4AFFA (or not a flat image).")

    code = assemble(a.mount + ':/' + a.path)
    if img[CAVE:CAVE + len(code)] != bytes(len(code)):
        sys.exit("error: code cave area is not empty: this is not build 9C6124686BE4AFFA.")
    words = struct.unpack('<%dI' % (len(code) // 4), code)
    hookw = 0x14000000 | (((CAVE - HOOK) // 4) & 0x3FFFFFF)
    lines = ['[%s]' % CHEAT_NAME, '14050000 %08X %08X' % (HOOK, HOOK_ORIG), '400F0000 00000000 00000000']
    lines += ['040F0000 %08X %08X' % (CAVE + 4 * i, w) for i, w in enumerate(words)]
    lines += ['040F0000 %08X %08X' % (HOOK, hookw), '20000000']
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, 'w') as fh:
        fh.write('\n'.join(lines) + '\n')
    if a.cave_out:
        os.makedirs(os.path.dirname(os.path.abspath(a.cave_out)), exist_ok=True)
        with open(a.cave_out, 'wb') as fh:
            fh.write(code)
    print('%d words; hook word %08X; wrote %s' % (len(words), hookw, a.out))


if __name__ == '__main__':
    main()
