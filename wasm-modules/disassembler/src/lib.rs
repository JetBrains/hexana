// Hexana disassembler WASM module.
//
// Pure-Rust disassembler backend compiled to wasm32-unknown-unknown. Drives
// x86 / x86_64 / ARM / ARM64 through yaxpeax and RISC-V through riscv-decode.
// Consumed from Kotlin Multiplatform via disassembly/capstone-wasm.
//
// Export contract (see README.md for the authoritative spec):
//   memory                                            — the linear memory
//   alloc(size: u32) -> u32                           — caller-input buffers
//   dealloc(ptr: u32, size: u32)                      — frees alloc()'d buffers
//   free_result(ptr: u32)                             — frees a decode_* result
//   decode_x86      (ptr, len, offset, base_va) -> u32
//   decode_x86_64   (ptr, len, offset, base_va) -> u32
//   decode_arm      (ptr, len, offset, base_va) -> u32
//   decode_arm64    (ptr, len, offset, base_va) -> u32
//   decode_riscv32  (ptr, len, offset, base_va) -> u32
//   decode_riscv64  (ptr, len, offset, base_va) -> u32

use core::slice;

use yaxpeax_arch::{Decoder, LengthedInstruction, U8Reader};

const KIND_VALID: u32 = 0;
const KIND_UNKNOWN: u32 = 1;
const HEADER_SIZE: usize = 48;

// -----------------------------------------------------------------------------
// Memory management exports
// -----------------------------------------------------------------------------

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

// -----------------------------------------------------------------------------
// Per-arch decode exports
// -----------------------------------------------------------------------------

#[unsafe(no_mangle)]
pub unsafe extern "C" fn decode_x86_64(
    bytes_ptr: u32,
    bytes_len: u32,
    offset: u32,
    base_va: u64,
) -> u32 {
    let Some((view, address)) = (unsafe { resolve_window(bytes_ptr, bytes_len, offset, base_va) }) else {
        return 0;
    };
    let decoder = yaxpeax_x86::long_mode::InstDecoder::default();
    let mut reader = U8Reader::new(view);
    match decoder.decode(&mut reader) {
        Ok(insn) => {
            let size = insn.len().to_const() as u32;
            let rendered = format!("{}", insn);
            let is_terminator = is_terminator_x86_64(insn.opcode());
            build_valid(address, size, is_terminator, &[], &rendered)
        }
        Err(_) => build_unknown(address, view),
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn decode_x86(
    bytes_ptr: u32,
    bytes_len: u32,
    offset: u32,
    base_va: u64,
) -> u32 {
    let Some((view, address)) = (unsafe { resolve_window(bytes_ptr, bytes_len, offset, base_va) }) else {
        return 0;
    };
    let decoder = yaxpeax_x86::protected_mode::InstDecoder::default();
    let mut reader = U8Reader::new(view);
    match decoder.decode(&mut reader) {
        Ok(insn) => {
            let size = insn.len().to_const() as u32;
            let rendered = format!("{}", insn);
            let is_terminator = is_terminator_x86(insn.opcode());
            build_valid(address, size, is_terminator, &[], &rendered)
        }
        Err(_) => build_unknown(address, view),
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn decode_arm(
    bytes_ptr: u32,
    bytes_len: u32,
    offset: u32,
    base_va: u64,
) -> u32 {
    let Some((view, address)) = (unsafe { resolve_window(bytes_ptr, bytes_len, offset, base_va) }) else {
        return 0;
    };
    // ARM (A32) mode only; Thumb support is a follow-up commit.
    let decoder = yaxpeax_arm::armv7::InstDecoder::armv7();
    let mut reader = U8Reader::new(view);
    match decoder.decode(&mut reader) {
        Ok(insn) => {
            let size = insn.len().to_const() as u32;
            let rendered = format!("{}", insn);
            let is_terminator = is_terminator_arm(&insn);
            build_valid(address, size, is_terminator, &[], &rendered)
        }
        Err(_) => build_unknown(address, view),
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn decode_arm64(
    bytes_ptr: u32,
    bytes_len: u32,
    offset: u32,
    base_va: u64,
) -> u32 {
    let Some((view, address)) = (unsafe { resolve_window(bytes_ptr, bytes_len, offset, base_va) }) else {
        return 0;
    };
    let decoder = yaxpeax_arm::armv8::a64::InstDecoder::default();
    let mut reader = U8Reader::new(view);
    match decoder.decode(&mut reader) {
        Ok(insn) => {
            let size = insn.len().to_const() as u32;
            let rendered = format!("{}", insn);
            let is_terminator = is_terminator_arm64(&insn);
            build_valid(address, size, is_terminator, &[], &rendered)
        }
        Err(_) => build_unknown(address, view),
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn decode_riscv32(
    bytes_ptr: u32,
    bytes_len: u32,
    offset: u32,
    base_va: u64,
) -> u32 {
    unsafe { decode_riscv_inner(bytes_ptr, bytes_len, offset, base_va) }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn decode_riscv64(
    bytes_ptr: u32,
    bytes_len: u32,
    offset: u32,
    base_va: u64,
) -> u32 {
    unsafe { decode_riscv_inner(bytes_ptr, bytes_len, offset, base_va) }
}

unsafe fn decode_riscv_inner(
    bytes_ptr: u32,
    bytes_len: u32,
    offset: u32,
    base_va: u64,
) -> u32 {
    let Some((view, address)) = (unsafe { resolve_window(bytes_ptr, bytes_len, offset, base_va) }) else {
        return 0;
    };
    // RISC-V is variable-length: 2 bytes (RVC) when low 2 bits != 0b11, else 4 bytes.
    if view.len() < 2 {
        return build_unknown(address, view);
    }
    let low2 = view[0] & 0b11;
    let (size, decoded) = if low2 == 0b11 {
        if view.len() < 4 {
            return build_unknown(address, view);
        }
        let word = u32::from_le_bytes([view[0], view[1], view[2], view[3]]);
        (4u32, riscv_decode::decode(word))
    } else {
        let half = u16::from_le_bytes([view[0], view[1]]);
        (2u32, riscv_decode::decode(half as u32))
    };
    match decoded {
        Ok(insn) => {
            let rendered = format!("{:?}", insn);
            let is_terminator = is_terminator_riscv(&insn);
            build_valid(address, size, is_terminator, &[], &rendered)
        }
        Err(_) => build_unknown(address, &view[..size as usize]),
    }
}

// -----------------------------------------------------------------------------
// Shared helpers
// -----------------------------------------------------------------------------

unsafe fn resolve_window<'a>(
    bytes_ptr: u32,
    bytes_len: u32,
    offset: u32,
    base_va: u64,
) -> Option<(&'a [u8], u64)> {
    if offset >= bytes_len {
        return None;
    }
    let bytes = unsafe {
        slice::from_raw_parts(bytes_ptr as *const u8, bytes_len as usize)
    };
    Some((&bytes[offset as usize..], base_va.wrapping_add(offset as u64)))
}

fn build_valid(
    address: u64,
    size: u32,
    is_terminator: bool,
    branch_targets: &[u64],
    rendered: &str,
) -> u32 {
    let (mnemonic, operands) = match rendered.find(char::is_whitespace) {
        Some(i) => (&rendered[..i], rendered[i..].trim_start()),
        None => (rendered, ""),
    };
    write_result(
        address,
        KIND_VALID,
        size,
        is_terminator as u32,
        branch_targets,
        mnemonic.as_bytes(),
        operands.as_bytes(),
    )
}

fn build_unknown(address: u64, view: &[u8]) -> u32 {
    let size = view.len().min(1) as u32;
    write_result(address, KIND_UNKNOWN, size, 0, &[], &[], &[])
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

// -----------------------------------------------------------------------------
// Per-arch terminator predicates
// -----------------------------------------------------------------------------

fn is_terminator_x86_64(opcode: yaxpeax_x86::long_mode::Opcode) -> bool {
    use yaxpeax_x86::long_mode::Opcode::*;
    matches!(
        opcode,
        JMP | JMPF | RETURN | RETF | IRET | IRETD | IRETQ | UD2 | HLT
    )
}

fn is_terminator_x86(opcode: yaxpeax_x86::protected_mode::Opcode) -> bool {
    use yaxpeax_x86::protected_mode::Opcode::*;
    matches!(
        opcode,
        JMP | JMPF | RETURN | RETF | IRET | IRETD | IRETQ | UD2 | HLT
    )
}

fn is_terminator_arm(insn: &yaxpeax_arm::armv7::Instruction) -> bool {
    use yaxpeax_arm::armv7::Opcode::*;
    // Only unconditional control-flow leaves; conditional B/BX still fall through.
    let unconditional = insn.condition == yaxpeax_arm::armv7::ConditionCode::AL;
    unconditional && matches!(insn.opcode, B | BX | BLX | BL | BXJ | ERET)
}

fn is_terminator_arm64(insn: &yaxpeax_arm::armv8::a64::Instruction) -> bool {
    use yaxpeax_arm::armv8::a64::Opcode::*;
    matches!(
        insn.opcode,
        B | BR | RET | ERET | HVC | SMC | BRK | DRPS
    )
}

fn is_terminator_riscv(insn: &riscv_decode::Instruction) -> bool {
    use riscv_decode::Instruction as I;
    match insn {
        I::Jal(j) => j.rd() == 0,    // unconditional jump (rd=zero => no link)
        I::Jalr(j) => j.rd() == 0,   // return / jump register
        I::Ecall => true,
        I::Ebreak => true,
        _ => false,
    }
}

// -----------------------------------------------------------------------------
// Tiny helpers
// -----------------------------------------------------------------------------

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
