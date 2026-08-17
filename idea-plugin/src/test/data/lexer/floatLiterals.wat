;; Textual float literals. These begin with a letter, so they reach the keyword path in
;; the lexer, but they are numbers rather than keywords.
inf
nan
nan:0x400000
nan:0x1
nan:0xfF_1
nan:canonical
nan:arithmetic

;; Signs are separate tokens, as for ordinary numbers.
-inf
+inf
-nan
+nan
-nan:0x400000

;; Words that only look like float literals stay unknown.
info
nano
infinity
nan:
nan:0x
nan:zzz
nan:0xzz
nanx

;; In context, as emitted by wasmprinter.
(f32.const inf)
(f64.const -inf)
(f32.const nan:0x400000)

;; Exponents must not consume the following character. A trailing "e" or "e-" is malformed,
;; but the close parenthesis has to survive so brace matching keeps working while typing.
(f32.const 1e)
(f32.const 1e-)
(f32.const 0x1p)
1e5
1e-5
1e+5
0x1p-2
0x1.fp3
1.5e10
