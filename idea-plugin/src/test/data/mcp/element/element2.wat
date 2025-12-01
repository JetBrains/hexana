(module
  (table 3 funcref)
  (table 3 funcref) ;; table 1 must exist

  (func $f0)
  (func $f1)

  ;; passive+table.init is the new representation
  (elem funcref
    (ref.func $f0)
    (ref.func $f1))

  ;; active table=1 initialization done using table.init
  (func
    (i32.const 0)  ;; dest table index = 1
    (i32.const 0)  ;; src elem index = 0
    (i32.const 2)  ;; number of elements
    table.init 0
  )
)