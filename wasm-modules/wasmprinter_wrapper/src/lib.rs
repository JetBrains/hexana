#![feature(alloc_error_handler)]

extern crate alloc;
use core::slice;
use alloc::vec::Vec;
use wasmprinter::{PrintFmtWrite};

unsafe extern "C" {
    fn host_print(ptr: *const u8, len: usize);
    fn host_error(ptr: *const u8, len: usize);
}

#[unsafe(no_mangle)]
pub extern "C" fn alloc_input(len: usize) -> *mut u8 {
    let mut buf = Vec::<u8>::with_capacity(len);
    let ptr = buf.as_mut_ptr();
    core::mem::forget(buf);
    ptr
}

pub fn print_bytes_internal(wasm: impl AsRef<[u8]>, fold_instructions: bool, name_unnamed: bool) -> Result<String, (String, String)> {
    let mut dst = String::new();
    let mut cfg = wasmprinter::Config::new();
    cfg.fold_instructions(fold_instructions)
        .name_unnamed(name_unnamed);
    match cfg.print(wasm.as_ref(), &mut PrintFmtWrite(&mut dst)) {
        Ok(_) => Ok(dst),
        Err(e) => Err((dst, format!("{e}"))),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn print_wat(ptr: *const u8, len: usize, flag_fold_instructions: u32,  flag_name_unnamed: u32) {
    let bytes = unsafe { slice::from_raw_parts(ptr, len) };

    match print_bytes_internal(bytes, flag_fold_instructions != 0, flag_name_unnamed != 0) {
        Ok(wat) => {
            let wat_bytes = wat.as_bytes();
            unsafe {
                host_print(wat_bytes.as_ptr(), wat_bytes.len());
            }
        }
        Err((wat, e)) => {
            let msg = format!("wasmprinter error: {e}");
            let msg_bytes = msg.as_bytes();
            let wat_bytes = wat.as_bytes();
            unsafe {
                host_print(wat_bytes.as_ptr(), wat_bytes.len());
                host_error(msg_bytes.as_ptr(), msg_bytes.len());
            }
        }
    }
}