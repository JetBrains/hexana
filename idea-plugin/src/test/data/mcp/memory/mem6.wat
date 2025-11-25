(module
  (memory 1)
  ;; 16-byte binary header: 0xDE 0xAD ...
  (data (i32.const 0)
    "\DE\AD\BE\EF\00\01\02\03\AA\BB\CC\DD\EF\FE\CA\FE"
  )
)