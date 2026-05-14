// Hexana disassembler WASM module.
//
// Pure-Rust disassembler backend compiled to wasm32-unknown-unknown. Currently
// supports x86_64 via yaxpeax-x86; more architectures (arm, arm64, riscv) will
// be added behind their own export entry points.
//
// The WASM exports defined here are consumed by the Kotlin Multiplatform module
// at disassembly/capstone-wasm via the platform-specific CapstoneWasmModule
// actuals (Chicory on JVM, host WebAssembly on js/wasmJs).
//
// Export contract (see README.md for the authoritative spec):
//   memory                                            — the linear memory
//   alloc(size: u32) -> u32                           — caller-input buffers
//   dealloc(ptr: u32, size: u32)                      — frees alloc()'d buffers
//   decode_x86_64(ptr, len, offset, base_va) -> u32   — decode one instruction
//   free_result(ptr: u32)                             — frees a decode result

use core::slice;

use yaxpeax_arch::{Decoder, LengthedInstruction, U8Reader};
use yaxpeax_x86::amd64::InstDecoder;

const KIND_VALID: u32 = 0;
const KIND_UNKNOWN: u32 = 1;

// Result-record layout. All offsets are bytes from the start of the chunk
// returned by decode_*. Multi-byte fields are little-endian (WASM convention).
//
//   off 0    u32 total_size           (size to pass to free_result; same chunk frees mnemonic/operands)
//   off 4    u32 kind                 (0 = valid, 1 = unknown)
//   off 8    u32 size                 (instruction size in bytes; for Unknown, the coalesced run length)
//   off 12   u32 is_block_terminator  (0 / 1; meaningless for Unknown)
//   off 16   u64 address              (virtual address)
//   off 24   u32 branch_target_count
//   off 28   u32 branch_targets_offset   (bytes from chunk start to u64[] of targets)
//   off 32   u32 mnemonic_len
//   off 36   u32 mnemonic_offset      (bytes from chunk start to UTF-8 mnemonic; 0 for Unknown)
//   off 40   u32 operands_len
//   off 44   u32 operands_offset      (bytes from chunk start to UTF-8 operands; 0 for Unknown)
//   off 48   ...trailing data: branch_targets (u64[]), mnemonic UTF-8, operands UTF-8

const HEADER_SIZE: usize = 48;

#[unsafe(no_mangle)]
pub extern "C" fn alloc(size: u32) -> u32 {
    let mut v = Vec::<u8>::with_capacity(size as usize);
    let ptr = v.as_mut_ptr();
    unsafe {
        v.set_len(size as usize);
    }
    core::mem::forget(v);
    ptr as u32
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn dealloc(ptr: u32, size: u32) {
    if ptr == 0 {
        return;
    }
    unsafe {
        drop(Vec::from_raw_parts(ptr as *mut u8, size as usize, size as usize));
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn free_result(ptr: u32) {
    if ptr == 0 {
        return;
    }
    let total_size = unsafe { read_u32(ptr, 0) } as usize;
    unsafe {
        drop(Vec::from_raw_parts(ptr as *mut u8, total_size, total_size));
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn decode_x86_64(
    bytes_ptr: u32,
    bytes_len: u32,
    offset: u32,
    base_va: u64,
) -> u32 {
    if offset >= bytes_len {
        return 0;
    }
    let bytes = unsafe {
        slice::from_raw_parts(bytes_ptr as *const u8, bytes_len as usize)
    };
    let view = &bytes[offset as usize..];
    let address = base_va.wrapping_add(offset as u64);

    let decoder = InstDecoder::default();
    let mut reader = U8Reader::new(view);
    match decoder.decode(&mut reader) {
        Ok(insn) => build_valid_x86_64(insn, address),
        Err(_) => build_unknown(address, view),
    }
}

fn build_valid_x86_64(insn: yaxpeax_x86::amd64::Instruction, address: u64) -> u32 {
    let size = insn.len().to_const() as u32;

    // yaxpeax's Display impl yields "mnemonic op0, op1, …". We split on the
    // first whitespace to separate the textual mnemonic from operands. The
    // syntax is yaxpeax's default rendering for now (Intel-ish); AT&T support
    // and explicit AssemblySyntax routing arrive in a later commit.
    let rendered = format!("{}", insn);
    let (mnemonic, operands) = match rendered.find(char::is_whitespace) {
        Some(i) => (&rendered[..i], rendered[i..].trim_start()),
        None => (rendered.as_str(), ""),
    };

    let opcode = insn.opcode();
    let is_terminator = matches!(
        opcode,
        yaxpeax_x86::amd64::Opcode::JMP
            | yaxpeax_x86::amd64::Opcode::JMPF
            | yaxpeax_x86::amd64::Opcode::RETURN
            | yaxpeax_x86::amd64::Opcode::RETF
            | yaxpeax_x86::amd64::Opcode::IRET
            | yaxpeax_x86::amd64::Opcode::IRETD
            | yaxpeax_x86::amd64::Opcode::IRETQ
            | yaxpeax_x86::amd64::Opcode::UD2
            | yaxpeax_x86::amd64::Opcode::HLT
    );

    let branch_targets: Vec<u64> = Vec::new();

    let mnemonic_bytes = mnemonic.as_bytes();
    let operands_bytes = operands.as_bytes();
    write_result(
        address,
        KIND_VALID,
        size,
        is_terminator as u32,
        &branch_targets,
        mnemonic_bytes,
        operands_bytes,
    )
}

fn build_unknown(address: u64, view: &[u8]) -> u32 {
    let size = view.len().min(1) as u32;
    let empty: Vec<u64> = Vec::new();
    write_result(address, KIND_UNKNOWN, size, 0, &empty, &[], &[])
}

fn write_result(
    address: u64,
    kind: u32,
    size: u32,
    is_terminator: u32,
    branch_targets: &[u64],
    mnemonic: &[u8],
    operands: &[u8],
) -> u32 {
    let branch_targets_bytes = branch_targets.len() * 8;
    let total = HEADER_SIZE + branch_targets_bytes + mnemonic.len() + operands.len();

    let mut buf = vec![0u8; total];
    let branch_targets_offset = HEADER_SIZE as u32;
    let mnemonic_offset = (HEADER_SIZE + branch_targets_bytes) as u32;
    let operands_offset = mnemonic_offset + mnemonic.len() as u32;

    write_u32(&mut buf, 0, total as u32);
    write_u32(&mut buf, 4, kind);
    write_u32(&mut buf, 8, size);
    write_u32(&mut buf, 12, is_terminator);
    write_u64(&mut buf, 16, address);
    write_u32(&mut buf, 24, branch_targets.len() as u32);
    write_u32(&mut buf, 28, if branch_targets.is_empty() { 0 } else { branch_targets_offset });
    write_u32(&mut buf, 32, mnemonic.len() as u32);
    write_u32(&mut buf, 36, if mnemonic.is_empty() { 0 } else { mnemonic_offset });
    write_u32(&mut buf, 40, operands.len() as u32);
    write_u32(&mut buf, 44, if operands.is_empty() { 0 } else { operands_offset });

    for (i, &t) in branch_targets.iter().enumerate() {
        write_u64(&mut buf, HEADER_SIZE + i * 8, t);
    }
    buf[mnemonic_offset as usize..mnemonic_offset as usize + mnemonic.len()]
        .copy_from_slice(mnemonic);
    buf[operands_offset as usize..operands_offset as usize + operands.len()]
        .copy_from_slice(operands);

    let ptr = buf.as_mut_ptr();
    core::mem::forget(buf);
    ptr as u32
}

#[inline]
fn write_u32(buf: &mut [u8], at: usize, v: u32) {
    buf[at..at + 4].copy_from_slice(&v.to_le_bytes());
}

#[inline]
fn write_u64(buf: &mut [u8], at: usize, v: u64) {
    buf[at..at + 8].copy_from_slice(&v.to_le_bytes());
}

#[inline]
unsafe fn read_u32(ptr: u32, off: usize) -> u32 {
    let p = (ptr as usize + off) as *const u8;
    let mut bytes = [0u8; 4];
    unsafe {
        core::ptr::copy_nonoverlapping(p, bytes.as_mut_ptr(), 4);
    }
    u32::from_le_bytes(bytes)
}
