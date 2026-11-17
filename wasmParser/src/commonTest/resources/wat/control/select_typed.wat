(module
  ;; Typed select (select (result t)) — spec 3.0
  (func (export "f") (param i32) (result externref)
    (select (result externref)
      (ref.null extern)
      (ref.null extern)
      (local.get 0)
    )
  )
)