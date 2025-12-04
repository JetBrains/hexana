(module
  (func $f
    (block $B1
      (nop))
    block $B1
      nop
    end

    (loop $L1
      (nop))
    loop $L1
      nop
    end
  )
)