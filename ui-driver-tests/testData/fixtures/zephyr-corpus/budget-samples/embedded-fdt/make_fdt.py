#!/usr/bin/env python3
"""
Build a minimal valid FDT blob and embed it into an ELF as a .fdt section,
then compile a tiny C wrapper that references the blob so it lands in .rodata.

FDT structure:
  - Header (version 17)
  - Memory reservation block (one zero-terminator entry)
  - Structure block: root node { #address-cells=<1>, #size-cells=<1>,
      child "memory@40000000" { device_type="memory", reg=<0x40000000 0x8000000> }
    }
  - Strings block

Output: image.elf with the FDT blob embedded in .rodata
Also writes fdt-offset.txt with the byte offset of the FDT magic in image.elf.
"""

import struct
import subprocess
import sys
import os
import tempfile

DIR = os.path.dirname(os.path.abspath(__file__))

# ---------------------------------------------------------------------------
# FDT constants
# ---------------------------------------------------------------------------
FDT_MAGIC       = 0xd00dfeed
FDT_BEGIN_NODE  = 0x00000001
FDT_END_NODE    = 0x00000002
FDT_PROP        = 0x00000003
FDT_NOP         = 0x00000004
FDT_END         = 0x00000009

def be32(v):
    return struct.pack('>I', v)

def align4(data: bytes) -> bytes:
    while len(data) % 4:
        data += b'\x00'
    return data

def fdt_string(s: str) -> bytes:
    return s.encode() + b'\x00'

def fdt_begin_node(name: str) -> bytes:
    return be32(FDT_BEGIN_NODE) + align4(name.encode() + b'\x00')

def fdt_end_node() -> bytes:
    return be32(FDT_END_NODE)

def fdt_prop(name_off: int, data: bytes) -> bytes:
    return be32(FDT_PROP) + be32(len(data)) + be32(name_off) + align4(data)

def fdt_end() -> bytes:
    return be32(FDT_END)

def build_fdt() -> bytes:
    # Strings block
    strings = b''
    def add_string(s):
        nonlocal strings
        off = len(strings)
        strings += fdt_string(s)
        return off

    off_address_cells = add_string('#address-cells')
    off_size_cells    = add_string('#size-cells')
    off_device_type   = add_string('device_type')
    off_reg           = add_string('reg')

    # Structure block
    struct_block  = b''
    # Root node
    struct_block += fdt_begin_node('')
    struct_block += fdt_prop(off_address_cells, be32(1))
    struct_block += fdt_prop(off_size_cells,    be32(1))
    # memory@40000000 child node
    struct_block += fdt_begin_node('memory@40000000')
    struct_block += fdt_prop(off_device_type, fdt_string('memory'))
    # reg = <0x40000000 0x8000000>
    struct_block += fdt_prop(off_reg, be32(0x40000000) + be32(0x8000000))
    struct_block += fdt_end_node()
    struct_block += fdt_end_node()
    struct_block += fdt_end()

    # Memory reservation block: one zero-terminator entry (address=0, size=0)
    mem_rsv = struct.pack('>QQ', 0, 0)

    # FDT version 17 header (10 fields * 4 bytes = 40 bytes)
    hdr_size = 40
    off_mem_rsv = hdr_size
    off_struct  = off_mem_rsv + len(mem_rsv)
    off_strings = off_struct + len(struct_block)
    total_size  = off_strings + len(strings)

    header = struct.pack('>IIIIIIIIII',
        FDT_MAGIC,      # magic
        total_size,     # totalsize
        off_struct,     # off_dt_struct
        off_strings,    # off_dt_strings
        off_mem_rsv,    # off_mem_rsvmap
        17,             # version
        16,             # last_comp_version
        0,              # boot_cpuid_phys
        len(strings),   # size_dt_strings
        len(struct_block), # size_dt_struct
    )

    blob = header + mem_rsv + struct_block + strings
    assert len(blob) == total_size
    return blob


def build_elf(fdt_blob: bytes):
    clang = '/Users/user/.swiftly/bin/clang'
    lld   = '/Users/user/.swiftly/bin/ld.lld'

    # Write the blob bytes as a C array into .rodata
    c_lines = ['/* Auto-generated: FDT blob embedded in .rodata */\n',
               '#include <stddef.h>\n',
               f'__attribute__((section(".rodata")))\n',
               f'const unsigned char fdt_blob[{len(fdt_blob)}] = {{\n']
    for i, b in enumerate(fdt_blob):
        if i % 16 == 0:
            c_lines.append('    ')
        c_lines.append(f'0x{b:02x},')
        if i % 16 == 15:
            c_lines.append('\n')
    c_lines.append('\n};\n')
    c_lines.append('const size_t fdt_blob_size = sizeof(fdt_blob);\n')
    c_lines.append('\nvoid _start(void) { (void)fdt_blob; while(1){} }\n')

    c_src = ''.join(c_lines)

    with tempfile.TemporaryDirectory() as tmpdir:
        c_path = os.path.join(tmpdir, 'fdt_embed.c')
        o_path = os.path.join(tmpdir, 'fdt_embed.o')
        with open(c_path, 'w') as f:
            f.write(c_src)

        # Simple linker script: FLASH at 0x08000000
        ld_script = os.path.join(tmpdir, 'fdt.ld')
        with open(ld_script, 'w') as f:
            f.write('MEMORY { FLASH (rx) : ORIGIN = 0x08000000, LENGTH = 1M }\n')
            f.write('SECTIONS {\n')
            f.write('  .text : { *(.text .text.*) } > FLASH\n')
            f.write('  .rodata : { *(.rodata .rodata.*) } > FLASH\n')
            f.write('}\n')

        subprocess.run(
            [clang, '--target=armv7em-none-eabi', '-nostdlib', '-ffreestanding',
             '-Os', '-c', c_path, '-o', o_path],
            check=True
        )
        elf_path = os.path.join(DIR, 'image.elf')
        subprocess.run(
            [lld, '-T', ld_script, o_path, '-o', elf_path],
            check=True
        )
        return elf_path


def find_magic_offset(elf_path: str, blob: bytes) -> int:
    magic = struct.pack('>I', FDT_MAGIC)
    with open(elf_path, 'rb') as f:
        data = f.read()
    offset = data.find(magic)
    # Verify it's actually our blob
    assert data[offset:offset+len(blob)] == blob, 'blob not found at magic offset'
    return offset


if __name__ == '__main__':
    print('Building FDT blob...')
    blob = build_fdt()
    print(f'  blob size: {len(blob)} bytes')
    print(f'  magic: 0x{struct.unpack(">I", blob[:4])[0]:08x}')

    print('Compiling and linking image.elf...')
    elf_path = build_elf(blob)

    print('Locating FDT magic in ELF...')
    offset = find_magic_offset(elf_path, blob)
    print(f'  FDT magic at byte offset: {offset} (0x{offset:x})')

    out_txt = os.path.join(DIR, 'fdt-offset.txt')
    with open(out_txt, 'w') as f:
        f.write(f'fdt_magic_offset_in_elf: {offset}\n')
        f.write(f'fdt_magic_offset_hex: 0x{offset:x}\n')
        f.write(f'fdt_blob_size: {len(blob)}\n')
        f.write('memory_node: memory@40000000\n')
        f.write('memory_origin: 0x40000000\n')
        f.write('memory_size: 0x8000000\n')
        f.write('memory_size_dec: 134217728\n')
        f.write('note: blob embedded in .rodata section of image.elf\n')

    print(f'Written: {out_txt}')
    print('Done.')
