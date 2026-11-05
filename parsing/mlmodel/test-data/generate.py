"""Regenerates the ML model fixtures with the reference writers.

    python3 -m venv /tmp/venv && /tmp/venv/bin/pip install gguf==0.8.0 safetensors==0.5.4 numpy
    /tmp/venv/bin/python -I parsing/mlmodel/test-data/generate.py parsing/mlmodel/test-data

`gguf` is llama.cpp's gguf-py; `safetensors` is Hugging Face's. Tests in
`parsing/mlmodel` and `plugins-shared` pin values written here, so update them together.
"""
import sys
from pathlib import Path

import numpy as np
import gguf
from gguf.quants import quantize
from safetensors.numpy import save_file

out = Path(sys.argv[1])

# --- GGUF ---
w = gguf.GGUFWriter(str(out / "tiny.gguf"), arch="llama")
w.add_name("Tiny Hexana Fixture")
w.add_file_type(gguf.LlamaFileType.MOSTLY_Q8_0)
w.add_context_length(128)
w.add_embedding_length(64)
w.add_block_count(1)
w.add_rope_freq_base(10000.0)
w.add_uint8("test.u8", 200)
w.add_int8("test.i8", -5)
w.add_uint16("test.u16", 65000)
w.add_int16("test.i16", -30000)
w.add_int32("test.i32", -2_000_000_000)
w.add_uint64("test.u64", 2**63 + 5)
w.add_int64("test.i64", -(2**40))
w.add_float64("test.f64", 2.5)
w.add_bool("test.bool", True)
w.add_array("test.ints", [1, 2, 3])
w.add_tokenizer_model("llama")
w.add_token_list(["<unk>", "<s>", "</s>", "hello", "world"])
w.add_token_scores([0.0, 0.0, 0.0, -1.5, -2.5])

rng = np.random.default_rng(7)
embd = rng.standard_normal((5, 64)).astype(np.float32)
w.add_tensor("token_embd.weight", quantize(embd, gguf.GGMLQuantizationType.Q8_0),
             raw_dtype=gguf.GGMLQuantizationType.Q8_0)
w.add_tensor("blk.0.attn_norm.weight", np.ones(64, dtype=np.float32))
w.add_tensor("blk.0.attn_q.weight", rng.standard_normal((64, 64)).astype(np.float16))
w.add_tensor("output_norm.weight", np.ones(64, dtype=np.float32))
w.write_header_to_file()
w.write_kv_data_to_file()
w.write_tensors_to_file()
w.close()

# --- safetensors ---
save_file(
    {
        "model.embed_tokens.weight": rng.standard_normal((5, 8)).astype(np.float16),
        "model.layers.0.mlp.up_proj.weight": rng.standard_normal((16, 8)).astype(np.float32),
        "model.layers.0.input_layernorm.weight": np.ones(8, dtype=np.float32),
        "lm_head.bias": np.zeros(5, dtype=np.int64),
    },
    str(out / "tiny.safetensors"),
    metadata={"format": "pt", "producer": "hexana-fixture"},
)
