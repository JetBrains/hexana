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
            let branch_target = branch_target_x86_64(&insn, address, size);
            let targets: &[u64] = match &branch_target {
                Some(t) => core::slice::from_ref(t),
                None => &[],
            };
            build_valid(address, size, is_terminator, targets, &rendered)
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
            let branch_target = branch_target_x86(&insn, address, size);
            let targets: &[u64] = match &branch_target {
                Some(t) => core::slice::from_ref(t),
                None => &[],
            };
            build_valid(address, size, is_terminator, targets, &rendered)
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
            let branch_target = branch_target_arm(&insn, address, size);
            let targets: &[u64] = match &branch_target {
                Some(t) => core::slice::from_ref(t),
                None => &[],
            };
            build_valid(address, size, is_terminator, targets, &rendered)
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
            let branch_target = branch_target_arm64(&insn, address);
            let targets: &[u64] = match &branch_target {
                Some(t) => core::slice::from_ref(t),
                None => &[],
            };
            build_valid(address, size, is_terminator, targets, &rendered)
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
            let branch_target = branch_target_riscv(&insn, address, size);
            let targets: &[u64] = match &branch_target {
                Some(t) => core::slice::from_ref(t),
                None => &[],
            };
            build_valid(address, size, is_terminator, targets, &rendered)
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
        | JO | JNO | JB | JNB | JZ | JNZ | JA | JNA | JS | JNS | JP | JNP | JL | JGE | JLE | JG
        | LOOP | LOOPZ | LOOPNZ | JRCXZ
    )
}

fn is_terminator_x86(opcode: yaxpeax_x86::protected_mode::Opcode) -> bool {
    use yaxpeax_x86::protected_mode::Opcode::*;
    matches!(
        opcode,
        JMP | JMPF | RETURN | RETF | IRET | IRETD | IRETQ | UD2 | HLT
        | JO | JNO | JB | JNB | JZ | JNZ | JA | JNA | JS | JNS | JP | JNP | JL | JGE | JLE | JG
        | LOOP | LOOPZ | LOOPNZ | JECXZ
    )
}

fn is_terminator_arm(insn: &yaxpeax_arm::armv7::Instruction) -> bool {
    use yaxpeax_arm::armv7::Opcode::*;
    // Unconditional B/BX/BXJ/ERET are block-ending jumps.
    // Conditional B/BX are also block terminators (the block splits into two successors).
    // BL and BLX are calls — they return to the following instruction, so NOT terminators.
    matches!(insn.opcode, B | BX | BXJ | ERET)
}

fn is_terminator_arm64(insn: &yaxpeax_arm::armv8::a64::Instruction) -> bool {
    use yaxpeax_arm::armv8::a64::Opcode::*;
    matches!(
        insn.opcode,
        B | BR | RET | ERET | HVC | SMC | BRK | DRPS
        | Bcc(_) | CBZ | CBNZ | TBZ | TBNZ
    )
}

fn is_terminator_riscv(insn: &riscv_decode::Instruction) -> bool {
    use riscv_decode::Instruction as I;
    match insn {
        I::Jal(j) => j.rd() == 0,    // unconditional jump (rd=zero => no link)
        I::Jalr(j) => j.rd() == 0,   // return / jump register
        I::Beq(_) | I::Bne(_) | I::Blt(_) | I::Bge(_) | I::Bltu(_) | I::Bgeu(_) => true,
        I::Ecall => true,
        I::Ebreak => true,
        _ => false,
    }
}

// -----------------------------------------------------------------------------
// Per-arch branch-target extractors
// Returns Some(absolute_target_va) for direct branches; None for indirect or non-branches.
// -----------------------------------------------------------------------------

fn branch_target_x86_64(
    insn: &yaxpeax_x86::long_mode::Instruction,
    address: u64,
    size: u32,
) -> Option<u64> {
    use yaxpeax_x86::long_mode::Opcode::*;
    use yaxpeax_x86::long_mode::Operand;
    let opcode = insn.opcode();
    let is_direct_branch = matches!(
        opcode,
        JMP
        | JO | JNO | JB | JNB | JZ | JNZ | JA | JNA | JS | JNS | JP | JNP | JL | JGE | JLE | JG
        | LOOP | LOOPZ | LOOPNZ | JRCXZ
    );
    if !is_direct_branch {
        return None;
    }
    let next_pc = address.wrapping_add(size as u64);
    match insn.operand(0) {
        Operand::ImmediateI8 { imm } => Some(next_pc.wrapping_add(imm as i64 as u64)),
        Operand::ImmediateI32 { imm } => Some(next_pc.wrapping_add(imm as i64 as u64)),
        _ => None,
    }
}

fn branch_target_x86(
    insn: &yaxpeax_x86::protected_mode::Instruction,
    address: u64,
    size: u32,
) -> Option<u64> {
    use yaxpeax_x86::protected_mode::Opcode::*;
    use yaxpeax_x86::protected_mode::Operand;
    let opcode = insn.opcode();
    let is_direct_branch = matches!(
        opcode,
        JMP
        | JO | JNO | JB | JNB | JZ | JNZ | JA | JNA | JS | JNS | JP | JNP | JL | JGE | JLE | JG
        | LOOP | LOOPZ | LOOPNZ | JECXZ
    );
    if !is_direct_branch {
        return None;
    }
    let next_pc = address.wrapping_add(size as u64);
    match insn.operand(0) {
        Operand::ImmediateI8 { imm } => Some(next_pc.wrapping_add(imm as i64 as u64)),
        Operand::ImmediateI32 { imm } => Some(next_pc.wrapping_add(imm as i64 as u64)),
        _ => None,
    }
}

fn branch_target_arm(
    insn: &yaxpeax_arm::armv7::Instruction,
    address: u64,
    _size: u32,
) -> Option<u64> {
    use yaxpeax_arm::armv7::Opcode::*;
    use yaxpeax_arm::armv7::Operand;
    if !matches!(insn.opcode, B | BX | BXJ | ERET) {
        return None;
    }
    // BranchOffset(imm24) for B; BX/BXJ/ERET use a register operand — no static target.
    // yaxpeax stores the 24-bit field with +2 already added (pipeline correction in word units),
    // so: target = address + (off << 2).  Display confirms: shows "$+/-<off << 2>".
    if let Operand::BranchOffset(off) = insn.operands[0] {
        let target = address.wrapping_add((off as i64 as u64).wrapping_mul(4));
        Some(target)
    } else {
        None
    }
}

fn branch_target_arm64(
    insn: &yaxpeax_arm::armv8::a64::Instruction,
    address: u64,
) -> Option<u64> {
    use yaxpeax_arm::armv8::a64::Opcode::*;
    use yaxpeax_arm::armv8::a64::Operand;
    // For ARM64: PCOffset is already the signed byte displacement; target = address + offset.
    let pc_offset_operand = match insn.opcode {
        B | Bcc(_) => Some(insn.operands[0]),
        CBZ | CBNZ => Some(insn.operands[1]),
        TBZ | TBNZ => Some(insn.operands[2]),
        _ => None,
    };
    if let Some(Operand::PCOffset(off)) = pc_offset_operand {
        Some(address.wrapping_add(off as u64))
    } else {
        None
    }
}

fn branch_target_riscv(
    insn: &riscv_decode::Instruction,
    address: u64,
    size: u32,
) -> Option<u64> {
    use riscv_decode::Instruction as I;
    match insn {
        I::Jal(j) if j.rd() == 0 => {
            // JAL with rd=zero: unconditional jump. JType::imm() gives raw u32 with sign at bit 20.
            let raw = j.imm();
            let signed_off = if raw & 0x0010_0000 != 0 {
                (raw | 0xffe0_0000) as i32
            } else {
                raw as i32
            };
            Some(address.wrapping_add(signed_off as i64 as u64))
        }
        I::Beq(b) | I::Bne(b) | I::Blt(b) | I::Bge(b) | I::Bltu(b) | I::Bgeu(b) => {
            // BType::imm() gives raw u32 with sign at bit 12.
            let raw = b.imm();
            let signed_off = if raw & 0x0000_1000 != 0 {
                (raw | 0xffff_e000) as i32
            } else {
                raw as i32
            };
            Some(address.wrapping_add(signed_off as i64 as u64))
        }
        I::Jalr(j) if j.rd() == 0 => {
            // JALR is indirect (base register + imm); no static target.
            let _ = size;
            None
        }
        _ => None,
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

// -----------------------------------------------------------------------------
// Unit tests — compiled only for the native host target (not wasm32).
// Run with: cargo test   (in wasm-modules/disassembler/)
// -----------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use yaxpeax_arch::{Decoder, U8Reader};

    // -------------------------------------------------------------------------
    // Helpers: decode one instruction from a byte slice via each arch decoder.
    // -------------------------------------------------------------------------

    fn decode_x86_64_insn(bytes: &[u8]) -> yaxpeax_x86::long_mode::Instruction {
        let decoder = yaxpeax_x86::long_mode::InstDecoder::default();
        decoder.decode(&mut U8Reader::new(bytes)).expect("x86_64 decode failed")
    }

    fn decode_x86_insn(bytes: &[u8]) -> yaxpeax_x86::protected_mode::Instruction {
        let decoder = yaxpeax_x86::protected_mode::InstDecoder::default();
        decoder.decode(&mut U8Reader::new(bytes)).expect("x86 decode failed")
    }

    fn decode_arm_insn(bytes: &[u8]) -> yaxpeax_arm::armv7::Instruction {
        let decoder = yaxpeax_arm::armv7::InstDecoder::armv7();
        decoder.decode(&mut U8Reader::new(bytes)).expect("arm decode failed")
    }

    fn decode_arm64_insn(bytes: &[u8]) -> yaxpeax_arm::armv8::a64::Instruction {
        let decoder = yaxpeax_arm::armv8::a64::InstDecoder::default();
        decoder.decode(&mut U8Reader::new(bytes)).expect("arm64 decode failed")
    }

    fn decode_riscv_insn(word: u32) -> riscv_decode::Instruction {
        riscv_decode::decode(word).expect("riscv decode failed")
    }

    // =========================================================================
    // x86_64 — branch-target extraction and terminator predicate
    // =========================================================================

    #[test]
    fn x86_64_jmp_short_branch_target() {
        // EB 05  →  jmp +5  (short jump, imm8 displacement)
        // next_pc = 0x1000 + 2 = 0x1002; target = 0x1002 + 5 = 0x1007
        let insn = decode_x86_64_insn(&[0xEB, 0x05]);
        use yaxpeax_arch::LengthedInstruction;
        let size = insn.len().to_const() as u32;
        assert_eq!(size, 2);
        assert_eq!(branch_target_x86_64(&insn, 0x1000, size), Some(0x1007));
        assert!(is_terminator_x86_64(insn.opcode()));
    }

    #[test]
    fn x86_64_je_short_branch_target() {
        // 74 02  →  je +2  (short conditional jump, imm8)
        // next_pc = 0x2000 + 2 = 0x2002; target = 0x2002 + 2 = 0x2004
        let insn = decode_x86_64_insn(&[0x74, 0x02]);
        use yaxpeax_arch::LengthedInstruction;
        let size = insn.len().to_const() as u32;
        assert_eq!(size, 2);
        assert_eq!(branch_target_x86_64(&insn, 0x2000, size), Some(0x2004));
        assert!(is_terminator_x86_64(insn.opcode()));
    }

    #[test]
    fn x86_64_jmp_near_zero_displacement() {
        // E9 00 00 00 00  →  jmp +0  (near jump, imm32 = 0)
        // next_pc = 0 + 5 = 5; target = 5 + 0 = 5
        let insn = decode_x86_64_insn(&[0xE9, 0x00, 0x00, 0x00, 0x00]);
        use yaxpeax_arch::LengthedInstruction;
        let size = insn.len().to_const() as u32;
        assert_eq!(size, 5);
        assert_eq!(branch_target_x86_64(&insn, 0, size), Some(5));
        assert!(is_terminator_x86_64(insn.opcode()));
    }

    #[test]
    fn x86_64_jne_is_terminator() {
        // 75 00  →  jne +0
        let insn = decode_x86_64_insn(&[0x75, 0x00]);
        assert!(is_terminator_x86_64(insn.opcode()));
    }

    #[test]
    fn x86_64_loop_is_terminator() {
        // E2 00  →  loop +0
        let insn = decode_x86_64_insn(&[0xE2, 0x00]);
        assert!(is_terminator_x86_64(insn.opcode()));
    }

    #[test]
    fn x86_64_loopz_is_terminator() {
        // E1 00  →  loope +0
        let insn = decode_x86_64_insn(&[0xE1, 0x00]);
        assert!(is_terminator_x86_64(insn.opcode()));
    }

    #[test]
    fn x86_64_loopnz_is_terminator() {
        // E0 00  →  loopne +0
        let insn = decode_x86_64_insn(&[0xE0, 0x00]);
        assert!(is_terminator_x86_64(insn.opcode()));
    }

    #[test]
    fn x86_64_jrcxz_is_terminator() {
        // E3 00  →  jrcxz +0
        let insn = decode_x86_64_insn(&[0xE3, 0x00]);
        assert!(is_terminator_x86_64(insn.opcode()));
    }

    #[test]
    fn x86_64_indirect_jmp_no_target() {
        // FF E0  →  jmp rax  (indirect — no static branch target)
        let insn = decode_x86_64_insn(&[0xFF, 0xE0]);
        use yaxpeax_arch::LengthedInstruction;
        let size = insn.len().to_const() as u32;
        assert_eq!(branch_target_x86_64(&insn, 0, size), None);
        assert!(is_terminator_x86_64(insn.opcode()));
    }

    // =========================================================================
    // x86 (32-bit protected mode) — branch-target extraction and terminators
    // =========================================================================

    #[test]
    fn x86_jmp_short_branch_target() {
        // EB 05  →  jmp +5
        // next_pc = 0x100 + 2 = 0x102; target = 0x102 + 5 = 0x107
        let insn = decode_x86_insn(&[0xEB, 0x05]);
        use yaxpeax_arch::LengthedInstruction;
        let size = insn.len().to_const() as u32;
        assert_eq!(size, 2);
        assert_eq!(branch_target_x86(&insn, 0x100, size), Some(0x107));
        assert!(is_terminator_x86(insn.opcode()));
    }

    #[test]
    fn x86_je_is_terminator_and_has_target() {
        // 74 0A  →  je +10
        // next_pc = 0 + 2 = 2; target = 2 + 10 = 12
        let insn = decode_x86_insn(&[0x74, 0x0A]);
        use yaxpeax_arch::LengthedInstruction;
        let size = insn.len().to_const() as u32;
        assert_eq!(branch_target_x86(&insn, 0, size), Some(12));
        assert!(is_terminator_x86(insn.opcode()));
    }

    #[test]
    fn x86_jecxz_is_terminator() {
        // E3 00  →  jecxz +0
        let insn = decode_x86_insn(&[0xE3, 0x00]);
        assert!(is_terminator_x86(insn.opcode()));
    }

    // =========================================================================
    // ARM32 — branch-target extraction, BL/BLX NOT terminators
    // =========================================================================

    #[test]
    fn arm32_b_branch_target() {
        // 00 00 00 EA  →  b #+8  (AL, imm24=0; yaxpeax adds 2 pipeline correction)
        // imm24 after +2 = 2; target = address + (2 * 4) = 0 + 8 = 8
        let insn = decode_arm_insn(&[0x00, 0x00, 0x00, 0xEA]);
        use yaxpeax_arch::LengthedInstruction;
        let size = insn.len().to_const() as u32;
        assert_eq!(size, 4);
        assert_eq!(branch_target_arm(&insn, 0, size), Some(8));
        assert!(is_terminator_arm(&insn));
    }

    #[test]
    fn arm32_b_with_nonzero_offset() {
        // FA FF FF EA  →  b #-16  (AL, imm24=0xFFFFFA; +2 gives 0xFFFFFC = -4; -4 * 4 = -16)
        // target = 0x1000 + (-4 * 4) = 0x1000 - 16 = 0xFF0
        let insn = decode_arm_insn(&[0xFA, 0xFF, 0xFF, 0xEA]);
        use yaxpeax_arch::LengthedInstruction;
        let size = insn.len().to_const() as u32;
        let target = branch_target_arm(&insn, 0x1000, size);
        assert!(target.is_some());
        assert_eq!(target.unwrap(), 0xFF0u64);
        assert!(is_terminator_arm(&insn));
    }

    #[test]
    fn arm32_bl_is_not_terminator() {
        // 00 00 00 EB  →  bl #+8  (call — must NOT be a terminator)
        let insn = decode_arm_insn(&[0x00, 0x00, 0x00, 0xEB]);
        assert!(!is_terminator_arm(&insn));
    }

    #[test]
    fn arm32_blx_is_not_terminator() {
        // BLX register form: FF FF FF FA  →  blx (Thumb interwork call)
        // Use BLX (immediate) encoding: FA = cond 1111 (unconditional), opcode 101, H=0
        // 0xFA000000 is BLX (immediate, Thumb); it's a call so NOT a terminator.
        let insn = decode_arm_insn(&[0x00, 0x00, 0x00, 0xFA]);
        assert!(!is_terminator_arm(&insn));
    }

    // =========================================================================
    // ARM64 — branch-target extraction and terminator predicate
    // =========================================================================

    #[test]
    fn arm64_b_unconditional_branch_target() {
        // 40 00 00 14  →  b #0x100  (imm26=0x40=64; PCOffset = 64*4 = 256 = 0x100)
        // target = base + PCOffset = 0 + 0x100 = 0x100
        let insn = decode_arm64_insn(&[0x40, 0x00, 0x00, 0x14]);
        assert_eq!(branch_target_arm64(&insn, 0), Some(0x100));
        assert!(is_terminator_arm64(&insn));
    }

    #[test]
    fn arm64_bcc_branch_target() {
        // 60 00 00 54  →  b.eq #+12  (Bcc EQ, imm19=3; PCOffset = 3*4 = 12)
        // target = 0 + 12 = 12
        let insn = decode_arm64_insn(&[0x60, 0x00, 0x00, 0x54]);
        assert_eq!(branch_target_arm64(&insn, 0), Some(12));
        assert!(is_terminator_arm64(&insn));
    }

    #[test]
    fn arm64_cbnz_branch_target() {
        // 40 00 00 B5  →  cbnz x0, #+8  (imm19=2; PCOffset = 2*4 = 8)
        // target = 0 + 8 = 8
        let insn = decode_arm64_insn(&[0x40, 0x00, 0x00, 0xB5]);
        assert_eq!(branch_target_arm64(&insn, 0), Some(8));
        assert!(is_terminator_arm64(&insn));
    }

    #[test]
    fn arm64_tbz_is_terminator() {
        // 00 00 00 36  →  tbz w0, #0, #+0  (TBZ with zero offset; just checks terminator flag)
        let insn = decode_arm64_insn(&[0x00, 0x00, 0x00, 0x36]);
        assert!(is_terminator_arm64(&insn));
    }

    #[test]
    fn arm64_cbz_is_terminator() {
        // 00 00 00 34  →  cbz w0, #+0
        let insn = decode_arm64_insn(&[0x00, 0x00, 0x00, 0x34]);
        assert!(is_terminator_arm64(&insn));
    }

    #[test]
    fn arm64_indirect_br_no_target() {
        // 00 00 1F D6  →  br x0  (indirect branch via register — no static target)
        let insn = decode_arm64_insn(&[0x00, 0x00, 0x1F, 0xD6]);
        assert_eq!(branch_target_arm64(&insn, 0), None);
        assert!(is_terminator_arm64(&insn));
    }

    // =========================================================================
    // RISC-V — branch-target extraction and terminator predicate
    // =========================================================================

    #[test]
    fn riscv_jal_branch_target() {
        // 6F 00 80 00  →  jal x0, +8  (JAL rd=0, J-imm = 8)
        // signed_off = 8; target = 0 + 8 = 8
        let word = u32::from_le_bytes([0x6F, 0x00, 0x80, 0x00]);
        let insn = decode_riscv_insn(word);
        assert_eq!(branch_target_riscv(&insn, 0, 4), Some(8));
        assert!(is_terminator_riscv(&insn));
    }

    #[test]
    fn riscv_beq_branch_target() {
        // 63 82 20 00  →  beq x1, x2, +4  (B-imm = 4)
        // signed_off = 4; target = 0x1000 + 4 = 0x1004
        let word = u32::from_le_bytes([0x63, 0x82, 0x20, 0x00]);
        let insn = decode_riscv_insn(word);
        assert_eq!(branch_target_riscv(&insn, 0x1000, 4), Some(0x1004));
        assert!(is_terminator_riscv(&insn));
    }

    #[test]
    fn riscv_bne_is_terminator() {
        // 63 11 20 00  →  bne x0, x2, +2  (just checks terminator; minimal valid BNE encoding)
        // BNE: opcode=0x63, funct3=001=0x1, rs1=0, rs2=2(bits24:20), imm=...
        // Use a known-good encoding: bne with imm=8 would be: funct3=1, rs1=0, rs2=0
        // Simplest: 0x00001063 = bne x0, x0, +0
        let word = 0x00001063u32;
        let insn = decode_riscv_insn(word);
        assert!(is_terminator_riscv(&insn));
    }

    #[test]
    fn riscv_jal_nonzero_rd_is_not_terminator() {
        // JAL with rd != 0 is a call (link register set), not a terminator.
        // 0x008000EF  →  jal x1, +8  (rd=1 = ra)
        let word = 0x008000EFu32;
        let insn = decode_riscv_insn(word);
        assert!(!is_terminator_riscv(&insn));
        assert_eq!(branch_target_riscv(&insn, 0, 4), None);
    }

    #[test]
    fn riscv_jalr_indirect_no_target() {
        // 67 80 00 00  →  jalr x0, x1, 0  (JALR rd=0: indirect return; no static target)
        let word = u32::from_le_bytes([0x67, 0x80, 0x00, 0x00]);
        let insn = decode_riscv_insn(word);
        assert_eq!(branch_target_riscv(&insn, 0, 4), None);
        assert!(is_terminator_riscv(&insn));
    }
}
