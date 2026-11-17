(module
  ;; Subtyping: $child extends $parent
  (type $parent (sub (struct (field i32))))
  (type $child (sub $parent (struct (field i32) (field f64))))
  ;; Final subtype (no further subtyping allowed) — default
  (type $final_child (sub final $child (struct (field i32) (field f64) (field i64))))

  (func (export "f") (nop))
)