(module
  (func $f
    (local $var i32)

    (local.get $var)
    local.get $var

    (local.set $var (i32.const 1))
    local.set $var

    (local.tee $var (i32.const 1))
    local.tee $var

    (call $1)
    call $1

    (global.get $1)
    global.get $1

    (global.set $1 (i32.const 1))
    global.set $1

    (throw $1 (i32.const 0))
    throw $1

    (catch <caret>$1)
    catch $1

    (br $L1)
    br $L1

    (br_if $L1)
    br_if $L1

    (br_table $L1 $L2 $L3 (local.get 0))
    br_table $L1 $L2 $L3

    (br_on_null $L1)
    br_on_null $L1

    (br_on_non_null $L1)
    br_on_non_null $L1

    (br_on_cast $L1 (ref $t1) (ref null $t2) (local.get 0))
    br_on_cast $L1 (ref $t1) (ref null $t2)

    (br_on_cast $L1 anyref eqref (local.get 0))
    br_on_cast $L1 anyref eqref

    (br_on_cast $L1 i31ref structref (local.get 0))
    br_on_cast $L1 i31ref structref

    (br_on_cast $L1 arrayref funcref (local.get 0))
    br_on_cast $L1 arrayref funcref

    (br_on_cast $L1 exnref externref (local.get 0))
    br_on_cast $L1 exnref externref

    (br_on_cast $L1 nullref nullfuncref (local.get 0))
    br_on_cast $L1 nullref nullfuncref

    (br_on_cast $L1 nullexnref nullexternref (local.get 0))
    br_on_cast $L1 nullexnref nullexternref
  )
)
