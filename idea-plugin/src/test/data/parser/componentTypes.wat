(component
  (type $prims (tuple bool s8 u8 s16 u16 s32 u32 s64 u64 float32 float64 char string))

  (type $rec (record (field "x" u32) (field "name" string)))
  (type $var (variant (case "none") (case "some" u32)))
  (type $en (enum "red" "green" "blue"))
  (type $fl (flags "read" "write"))
  (type $lst (list u8))
  (type $mp (map string u32))
  (type $opt (option string))
  (type $res (resource (rep i32) (dtor (func $i "cleanup"))))
  (type $own (own $res))
  (type $borrow (borrow $res))
  (type $str (stream u8))
  (type $fut (future string))
  (type $err error-context)

  (type $f (func (param "a" u32) (result (option string))))

  (core module $m
    (memory (export "mem") 1)
    (func (export "alloc") (param i32 i32 i32 i32) (result i32) (i32.const 0))
    (func (export "run") (param i32) (result i32) (local.get 0))
    (func (export "cleanup"))
    (func (export "cb") (param i32 i32 i32) (result i32) (i32.const 0))
  )
  (core instance $i (instantiate $m))

  (alias core export $i "mem" (core memory $mem))

  (func $lifted (type $f)
    (canon lift (core func $i "run")
      (memory $mem)
      (realloc (func $i "alloc"))
      (post-return (func $i "cleanup"))
      (callback (func $i "cb"))
      string-encoding=utf8
      async
      gc
    )
  )

  (core func $lowered
    (canon lower (func $lifted) (memory $mem) string-encoding=utf16 cancellable)
  )
  (core func $lowered2
    (canon lower (func $lifted) (memory $mem) string-encoding=latin1+utf16)
  )

  (core func $new (canon resource.new $res))
  (core func $drop (canon resource.drop $own))
  (core func $rep (canon resource.rep $res))
)
