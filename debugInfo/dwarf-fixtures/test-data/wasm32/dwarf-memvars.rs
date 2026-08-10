#[repr(C)]
pub struct Point {
    pub x: i32,
    pub y: i16,
    pub z: i8,
}

#[export_name = "make"]
pub extern "C" fn make(v: i32) -> i32 {
    let mut p = Point { x: v, y: 2, z: 3 };
    p.x += 1;
    p.x + p.y as i32 + p.z as i32
}
