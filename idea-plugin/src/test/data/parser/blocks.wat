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

    (if $I1
      (then
        (nop))
      (else
        (nop))
    )
    if $I1
      nop
    else
      nop
    end

    if $L1
      block $L2
        loop $L3
          nop
        end
      end
    end
  )
)