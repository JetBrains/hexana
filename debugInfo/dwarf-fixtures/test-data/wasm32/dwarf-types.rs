#[repr(C)]
pub struct Point {
    pub x: i32,
    pub y: i16,
    pub z: i8,
}

#[repr(u32)]
pub enum Color {
    Red = 0,
    Green = 1,
    Blue = 2,
}

#[inline(always)]
fn add(a: i32, b: i32) -> i32 {
    a + b
}

#[export_name = "compute"]
pub extern "C" fn compute(p: *const Point, c: Color) -> i32 {
    let arr: [i32; 4] = [1, 2, 3, 4];
    let sum = add(unsafe { (*p).x }, c as i32);
    sum + arr[0] + arr[1] + arr[2] + arr[3]
}
