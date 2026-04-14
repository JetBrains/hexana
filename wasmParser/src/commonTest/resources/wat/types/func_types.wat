(module
  ;; type 0: () -> ()
  (type $void (func))
  ;; type 1: (i32) -> i32
  (type $identity (func (param i32) (result i32)))
  ;; type 2: (i32, i64) -> f32
  (type $multi_param (func (param i32 i64) (result f32)))
  ;; type 3: () -> (i32, i64)  — multi-value return
  (type $multi_return (func (result i32 i64)))
  ;; type 4: (f32, f64, i32, i64) -> (f32, f64)
  (type $complex (func (param f32 f64 i32 i64) (result f32 f64)))

  (func (export "f") (type $void))
)