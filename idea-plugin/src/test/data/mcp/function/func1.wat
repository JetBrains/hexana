(module
  (type (func (param i32)))      ;; typeidx = 0
  (type (func (param i64)))      ;; typeidx = 1

  (import "env" "ext" (func (type 1)))  ;; funcidx = 0

  (func (type 0)                ;; funcidx = 1
    local.get 0
  )
)