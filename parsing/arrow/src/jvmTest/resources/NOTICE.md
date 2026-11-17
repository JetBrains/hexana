Test fixtures in this directory are sourced from the Apache Arrow Testing repository:
https://github.com/apache/arrow-testing

Licensed under the Apache License, Version 2.0.
http://www.apache.org/licenses/LICENSE-2.0

Files:
  generated_null_trivial.stream         — Arrow IPC stream, trivial null schema
  generated_primitive_no_batches.stream — Arrow IPC stream, 30-field primitive schema, 0 batches
  generated_primitive.stream            — Arrow IPC stream, 30-field primitive schema, 2 batches of 17 rows
                                          (used for ArrowValueDecoderTest golden-provenance tests)
  generated_null_trivial.arrow_file     — Arrow IPC file format, trivial null schema
  fuzz_tail_only_magic.arrow_file       — clusterfuzz-testcase-arrow-ipc-file-fuzz-4983476816314368
                                          (malformed: ARROW1 at tail only) — robustness fixture
  generated_datetime.stream   (4568 B)  — Arrow IPC stream, 15 temporal fields: Date32/64, Time32/64,
                                          Timestamp[s/ms/us/ns] with and without timezone; 7 rows
  generated_interval.stream   (1976 B)  — Arrow IPC stream, 6 fields: Duration[s/ms/us/ns],
                                          Interval[YEAR_MONTH], Interval[DAY_TIME]; 7 rows
  generated_decimal.stream  (253920 B)  — Arrow IPC stream, 36 Decimal128 columns, precision 3..38 scale 2,
                                          7 rows (used for ArrowTemporalDecoderTest decimal golden tests)
  generated_decimal256.stream (360464 B) — Arrow IPC stream, 33 Decimal256 columns, precision 37..69 scale 5,
                                          7 rows (values exceed int64; tests hex-fallback representation)

Source paths in apache/arrow-testing:
  data/arrow-ipc-stream/integration/1.0.0-littleendian/
  data/arrow-ipc-file/ (clusterfuzz corpus)