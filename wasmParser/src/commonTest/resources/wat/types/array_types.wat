(module
  ;; Immutable i32 array
  (type $i32_arr (array i32))
  ;; Mutable f64 array
  (type $f64_arr (array (mut f64)))
  ;; Packed i8 array (byte array)
  (type $byte_arr (array (mut i8)))
  ;; Packed i16 array
  (type $short_arr (array i16))

  (func (export "f") (nop))
)