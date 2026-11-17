(module
  (func $f)
  (memory $mem 1)
  (table $tab 5 funcref)
  (global $g i32 (i32.const 1))

  (export "f"    (func $f))
  (export "mem"  (memory $mem))
  (export "tab"  (table $tab))
  (export "g"    (global $g))
)