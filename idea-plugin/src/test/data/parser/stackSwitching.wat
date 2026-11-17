(module
  (type $ft (func (param i32) (result i32)))
  (type $ct (cont $ft))
  (type $nc (nocont))

  (tag $yield (param i32))

  (global $g (ref null $ct) (ref.null nocont))
  (global $g2 contref (ref.null cont))
  (global $g3 nullcontref (ref.null nocont))

  (func $generator (param i32) (result i32)
    (suspend $yield (local.get 0))
  )

  (func $folded (result i32)
    (resume $ct (on $yield $done) (cont.new $ct (ref.func $generator)))
    (unreachable)
  )

  (func $unfolded (result i32)
    ref.func $generator
    cont.new $ct
    cont.bind $ct $ct
    resume $ct (on $yield switch)
  )

  (func $switching
    ref.func $generator
    cont.new $ct
    switch $ct $yield
    drop
  )

  (func $throwing
    ref.func $generator
    cont.new $ct
    resume_throw $ct $yield
    drop
  )

  (func $throwingRef
    ref.func $generator
    cont.new $ct
    resume_throw_ref $ct
    drop
  )

  ;; `resume`'s `on` clauses target labels, so a nested block's label must still resolve.
  (func $nested
    block $done
      ref.func $generator
      cont.new $ct
      resume $ct (on $yield $done)
      drop
    end
  )
)
