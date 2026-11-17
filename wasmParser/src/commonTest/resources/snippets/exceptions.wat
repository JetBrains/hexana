;; Fixture for ExceptionFlowTest / tag-section parsing.
;;  $caught:  throw $t0 inside a try_table catching $t0 -> CaughtHere (tag)
;;  $catchall: throw $t1 inside a try_table with catch_all -> CaughtHere (catch_all)
;;  $escapes: throw $t0 with no handler -> Escapes
;;  $mixed: throw $t1 inside a try_table catching only $t0 -> Escapes
(module
  (type $e0 (func (param i32)))
  (type $e1 (func (param f64)))
  (tag $t0 (type $e0))
  (tag $t1 (type $e1))
  (func $caught
    block $b (result i32)
      try_table (catch $t0 $b)
        i32.const 7
        throw $t0
      end
      unreachable
    end
    drop)
  (func $catchall
    block $b
      try_table (catch_all $b)
        f64.const 1
        throw $t1
      end
    end)
  (func $escapes
    i32.const 9
    throw $t0)
  (func $mixed
    block $b (result i32)
      try_table (catch $t0 $b)
        f64.const 2
        throw $t1
      end
      unreachable
    end
    drop)
)
