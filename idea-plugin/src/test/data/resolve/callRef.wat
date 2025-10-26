(module
  (func (;TARGET;)$f (param $x i64) (result i64)
    (call <caret>$f (local.get $x))
  )
)
