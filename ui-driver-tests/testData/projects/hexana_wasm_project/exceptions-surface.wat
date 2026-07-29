;; Screenshot fixture for the Hexana 0.15 Exceptions tab.
;; One exported entry propagates a precise tag; the other has a dynamic host risk.
(module
  (type $tag_payload (func (param i32)))
  (type $host_call (func))
  (import "host" "load_data" (func $host_load_data (type $host_call)))
  (tag $parse_error (type $tag_payload))
  (func $throw_parse_error
    i32.const 7
    throw $parse_error)
  (func $parse_request (export "parse_request")
    call $throw_parse_error)
  (func $load_host_data (export "load_host_data")
    call $host_load_data)
)
