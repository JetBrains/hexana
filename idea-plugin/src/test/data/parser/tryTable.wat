(module
  (tag $e (param i32))

  (func $folded
    (try_table $t (result i32)
      (catch $e $t)
      (catch_ref $e $t)
      (catch_all $t)
      (catch_all_ref $t)
      (i32.const 0)
    )
    (drop)
  )

  (func $unfolded
    try_table $t2 (catch $e $t2)
      nop
    end
  )

  ;; The regression case: a try_table nested inside a block. Its `end` must close the
  ;; try_table, leaving the block to be closed by the block's own `end`, and the `br $outer`
  ;; must still resolve to the enclosing block rather than to the try_table.
  (func $nested
    block $outer
      try_table $inner (catch_all $inner)
        br $outer
      end
      nop
    end
  )

  (func $throwing
    (throw $e (i32.const 1))
  )

  (func $rethrowing
    try_table $t3
      throw_ref
    end
  )
)
