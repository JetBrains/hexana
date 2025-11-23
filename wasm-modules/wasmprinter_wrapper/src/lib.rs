#![feature(alloc_error_handler)]

extern crate alloc;
use core::slice;
use alloc::vec::Vec;

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

#[unsafe(no_mangle)]
pub extern "C" fn print_wat(ptr: *const u8, len: usize) {
    let bytes = unsafe { slice::from_raw_parts(ptr, len) };

    match wasmprinter::print_bytes(bytes) {
        Ok(wat) => {
            let wat_bytes = wat.as_bytes();
            unsafe {
                host_print(wat_bytes.as_ptr(), wat_bytes.len());
            }
        }
        Err(e) => {
            let msg = format!("wasmprinter error: {e}");
            let msg_bytes = msg.as_bytes();
            unsafe {
                host_error(msg_bytes.as_ptr(), msg_bytes.len());
            }
        }
    }
}