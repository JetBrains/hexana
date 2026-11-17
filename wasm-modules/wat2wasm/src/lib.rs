#![no_std]

extern crate alloc;

use alloc::format;
use alloc::vec::Vec;

use core::slice;
use core::str;

use wast::Wat;
use wast::parser::ParseBuffer;

// Provided by the host (GraalWasm)
unsafe extern "C" {
    fn host_print(ptr: *const u8, len: usize);
    fn host_error(ptr: *const u8, len: usize);
    #[allow(dead_code)]
    fn host_debug(ptr: *const u8, len: usize);
}

/// Allocate a buffer for input WAT text in guest memory.
///
/// Host side:
///   - call `alloc_input(len)`
///   - write `len` bytes of UTF-8 WAT into returned pointer
#[unsafe(no_mangle)]
pub extern "C" fn alloc_input(len: usize) -> *mut u8 {
    let mut buf = Vec::<u8>::with_capacity(len);
    let ptr = buf.as_mut_ptr();
    core::mem::forget(buf);
    ptr
}

/// Convert WAT (text) → WASM (binary) and send result to `host_print`.
///
/// On error, sends a UTF-8 error message to `host_error`.
#[unsafe(no_mangle)]
pub extern "C" fn wat2wasm(ptr: *const u8, len: usize) {
    let wat_bytes = unsafe { slice::from_raw_parts(ptr, len) };

    let wat_str = match str::from_utf8(wat_bytes) {
        Ok(s) => s,
        Err(_) => {
            let msg = "wat-error: invalid UTF-8";
            unsafe {
                host_error(msg.as_ptr(), msg.len());
            }
            return;
        }
    };

    // Parse WAT into a single Wat item (module or component)
    let buf = match ParseBuffer::new(wat_str) {
        Ok(b) => b,
        Err(e) => {
            let msg = format!("parse-buffer-error: {e}");
            unsafe {
                host_error(msg.as_ptr(), msg.len());
            }
            return;
        }
    };

    let wat = match wast::parser::parse::<Wat>(&buf) {
        Ok(w) => w,
        Err(e) => {
            let msg = format!("parse-error: {e}");
            unsafe {
                host_error(msg.as_ptr(), msg.len());
            }
            return;
        }
    };

    // Encode to binary WASM
    match wat {
        Wat::Module(mut module) => {
            let result = module.encode();
            match result {
            Err(e) => {
                let msg = format!("encode-error: {e}");
                unsafe {
                    host_error(msg.as_ptr(), msg.len());
                }
                return;
            }
            Ok(bytes) => {
                    unsafe {
                        host_print(bytes.as_ptr(), bytes.len());
                    }
                }
            }
        }

        // If your `wast` build includes the component model, this arm is valid.
        // If not, you can comment it out after seeing the compiler error.
        Wat::Component(mut component) => {
            let result = component.encode();
            match result {
                Err(e) =>  {
                    let msg = format!("component-encode-error: {e}");
                    unsafe{
                        host_error(msg.as_ptr(), msg.len());
                    }
                    return;
                }
                Ok(bytes) => {
                    unsafe {
                        host_print(bytes.as_ptr(), bytes.len());
                    }
                }
            }
        }
    }
}