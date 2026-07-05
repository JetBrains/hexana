# Test fixtures — attribution

These files are golden test fixtures copied verbatim from official Apache
testing repositories and are used here only to verify metadata decoding /
tab generation. They are licensed under the Apache License 2.0.

| File | Source repo |
|------|-------------|
| `alltypes_plain.parquet` | https://github.com/apache/parquet-testing (`data/`) |
| `generated_null_trivial.stream` | https://github.com/apache/arrow-testing (`data/arrow-ipc-stream/`) |
| `generated_null_trivial.arrow_file` | https://github.com/apache/arrow-testing (`data/arrow-ipc-file/`) |
| `generated_primitive.stream` | https://github.com/apache/arrow-testing (`data/arrow-ipc-stream/`) |
| `fuzz_arrow_bad_block_offset.arrow_file` | https://github.com/apache/arrow-testing (`data/arrow-ipc-file/` clusterfuzz corpus) — malformed, empty-state test |

See https://www.apache.org/licenses/LICENSE-2.0 for the license text.