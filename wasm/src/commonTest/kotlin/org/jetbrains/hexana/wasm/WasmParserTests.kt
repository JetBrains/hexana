package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.IgnoreJsTarget
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.opcodes.MemArg
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.sections.TypeId
import org.jetbrains.hexana.wasm.sections.WasmCodeLocalRecord
import org.jetbrains.hexana.wasm.sections.WasmCodeRecord
import org.jetbrains.hexana.wasm.sections.WasmImportRecord
import org.jetbrains.hexana.wasm.sections.WasmSectionTypesRecord
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WasmParserTests {

    @Test
    fun parseSkikoSections() = runTest {
        val buffer = resourceAsByteBuffer("skiko.wasm")
        val wasmModule = WasmParser().parse(buffer)

        assertNotNull(wasmModule)

        assertNull(wasmModule.customSection)
        assertEquals(2796, wasmModule.typesSection?.size)
        assertEquals(4265, wasmModule.importSection!!.size)
        assertEquals(14363, wasmModule.functionSection?.size)
        assertEquals(7, wasmModule.tableSection?.size)
        assertEquals(7, wasmModule.memorySection?.size)
        assertEquals(14, wasmModule.globalSection?.size)
        assertEquals(49677, wasmModule.exportSection?.size)
        assertNull(wasmModule.startSection?.size)
        assertEquals(19761, wasmModule.elementSection?.size)
        assertEquals(6380629, wasmModule.codeSection!!.size)
        assertEquals(1833784, wasmModule.dataSection?.size)
        assertNull(wasmModule.dataCountSection)
        assertEquals(49677, wasmModule.exportSection?.size)
        assertEquals(3, wasmModule.exceptionSection?.size)

        val expectedImports = listOf(
            "env::abort", "env::emscripten_asm_const_int", "env::glGetString", "env::glGetStringi",
            "env::glGetIntegerv", "env::glUniform1fv", "env::glUniform1f", "env::glTexSubImage2D",
            "env::glTexParameteriv", "env::glTexParameteri", "env::glTexParameterfv", "env::glTexParameterf",
            "env::glTexImage2D", "env::glStencilOpSeparate", "env::glStencilOp", "env::glStencilMaskSeparate",
            "env::glStencilMask", "env::glStencilFuncSeparate", "env::glStencilFunc", "env::glShaderSource",
            "env::glScissor", "env::glReadPixels", "env::glPixelStorei", "env::glLinkProgram", "env::glLineWidth",
            "env::glIsTexture", "env::glGetUniformLocation", "env::glGetShaderiv", "env::glGetShaderInfoLog",
            "env::glGetProgramiv", "env::glGetProgramInfoLog", "env::glGetFloatv", "env::glGetError",
            "env::glGetBufferParameteriv", "env::glGenTextures", "env::glGenBuffers", "env::glFrontFace", "env::glFlush",
            "env::glFinish", "env::glEnableVertexAttribArray", "env::glEnable", "env::glDrawElements", "env::glDrawArrays",
            "env::glDisableVertexAttribArray", "env::glDisable", "env::glDepthMask", "env::glDeleteTextures",
            "env::glDeleteShader", "env::glDeleteProgram", "env::glDeleteBuffers", "env::glCullFace", "env::glCreateShader",
            "env::glCreateProgram", "env::glCopyTexSubImage2D", "env::glCompressedTexSubImage2D",
            "env::glCompressedTexImage2D", "env::glCompileShader", "env::glColorMask", "env::glClearStencil", "env::glClearColor",
            "env::glClear", "env::glBufferSubData",
            "env::glBufferData", "env::glBlendFunc", "env::glBlendEquation", "env::glBlendColor", "env::glBindTexture",
            "env::glBindBuffer", "env::glBindAttribLocation", "env::glAttachShader", "env::glActiveTexture", "env::glUniform2fv",
            "env::glUniform2f", "env::glUniform1iv", "env::glUniform1i", "env::glUniform2i", "env::glUniform2iv", "env::glUniform3f",
            "env::glUniform3fv", "env::glUniform3i", "env::glUniform3iv", "env::glUniform4f", "env::glUniform4fv",
            "env::glViewport", "env::glVertexAttribPointer", "env::glVertexAttrib4fv", "env::glVertexAttrib3fv",
            "env::glVertexAttrib2fv", "env::glVertexAttrib1f", "env::glUseProgram", "env::glUniformMatrix4fv",
            "env::glUniformMatrix3fv", "env::glUniformMatrix2fv", "env::glUniform4iv", "env::glUniform4i",
            "env::glGenVertexArraysOES", "env::glDeleteVertexArraysOES", "env::glBindVertexArrayOES", "env::glGenVertexArrays",
            "env::glDeleteVertexArrays", "env::glBindVertexArray", "env::glDrawElementsInstanced", "env::glDrawArraysInstanced",
            "env::glDrawElementsInstancedBaseVertexBaseInstanceWEBGL", "env::glDrawArraysInstancedBaseInstanceWEBGL",
            "env::glReadBuffer", "env::glDrawBuffers", "env::glMultiDrawElementsInstancedBaseVertexBaseInstanceWEBGL",
            "env::glMultiDrawArraysInstancedBaseInstanceWEBGL", "env::glVertexAttribIPointer", "env::glVertexAttribDivisor",
            "env::glTexStorage2D", "env::glDrawRangeElements", "env::glGenRenderbuffers", "env::glGenFramebuffers",
            "env::glFramebufferTexture2D", "env::glFramebufferRenderbuffer", "env::glDeleteRenderbuffers", "env::glDeleteFramebuffers",
            "env::glCheckFramebufferStatus", "env::glBindRenderbuffer", "env::glBindFramebuffer", "env::glRenderbufferStorage",
            "env::glGetRenderbufferParameteriv", "env::glGetFramebufferAttachmentParameteriv", "env::glGenerateMipmap", "env::glRenderbufferStorageMultisample",
            "env::glBlitFramebuffer", "env::glDeleteSync", "env::glCopyBufferSubData", "env::glIsSync",
            "env::glFenceSync", "env::glSamplerParameteriv", "env::glSamplerParameteri", "env::glSamplerParameterf",
            "env::glGenSamplers", "env::glDeleteSamplers", "env::glBindSampler", "env::glInvalidateSubFramebuffer",
            "env::glInvalidateFramebuffer", "env::glGetShaderPrecisionFormat", "env::exit", "env::_embind_register_void",
            "env::_embind_register_bool", "env::_embind_register_integer", "env::_embind_register_float", "env::_embind_register_std_string",
            "env::_embind_register_std_wstring", "env::_embind_register_emval", "env::_embind_register_memory_view",
            "wasi_snapshot_preview1::fd_close", "env::emscripten_memcpy_js", "env::emscripten_date_now",
            "env::_emscripten_get_now_is_monotonic", "env::emscripten_get_now", "env::__syscall_openat", "env::__syscall_fcntl64",
            "env::__syscall_ioctl", "wasi_snapshot_preview1::fd_write", "wasi_snapshot_preview1::fd_read", "env::__syscall_fstat64",
            "env::__syscall_stat64", "env::__syscall_newfstatat", "env::__syscall_lstat64", "wasi_snapshot_preview1::environ_sizes_get",
            "wasi_snapshot_preview1::environ_get", "env::emscripten_resize_heap", "env::strftime_l", "env::glClientWaitSync",
            "env::glWaitSync", "env::_embind_register_bigint", "wasi_snapshot_preview1::fd_seek", "env::_munmap_js",
            "env::_mmap_js", "wasi_snapshot_preview1::fd_pread",
        )


        with (wasmModule.importSection) {
            val importRecords = buffer.getImportRecords()
            assertContentEquals(expectedImports, importRecords.map { "${it.prefix}::${it.name}"})
        }
    }

    @Test
    fun parseLongImports() = runTest {
        val buffer = resourceAsByteBuffer("string.wasm")
        val wasmModule = WasmParser().parse(buffer)
        assertNotNull(wasmModule)

        with(wasmModule.importSection!!) {
            val importRecords = buffer.getImportRecords()

            assertEquals(4, importRecords.size)

            assertEquals(
                WasmImportRecord(
                    "abcdefghijklmnopqrstuvwxyz0123456789_0_abcdefghijklmnopqrstuvwxyz0123456789_1_abcdefghijklmnopqrstuvwxyz0123456789_2_abcdefghijklmnopqrstuvwxyz0123456789_2_abcdefghijklmnopqrstuvwxyz0123456789_3_abcdefghijklmnopqrstuvwxyz0123456789_4_abcdefghijklmnopqrstuvwxyz0123456789_5_abcdefghijklmnopqrstuvwxyz0123456789_6_abcdefghijklmnopqrstuvwxyz0123456789_7_abcdefghijklmnopqrstuvwxyz0123456789_8_abcdefghijklmnopqrstuvwxyz0123456789_9_abcdefghijklmnopqrstuvwxyz0123456789_10_abcdefghijklmnopqrstuvwxyz0123456789_11_",
                    "x",
                    WasmTypeIndexExternalType(0)
                ), importRecords[0]
            )

            assertEquals(
                WasmImportRecord(
                    "string",
                    "a",
                    WasmTypeIndexExternalType(0)
                ), importRecords[1]
            )

            assertEquals(
                WasmImportRecord(
                    "a",
                    "b",
                    WasmTypeIndexExternalType(0)
                ), importRecords[2]
            )

            assertEquals(
                WasmImportRecord(
                    "env",
                    "abort",
                    WasmTypeIndexExternalType(3)
                ), importRecords[3]
            )
        }
    }

    @Test
    fun `code leftshift`() = runTest {
        assertFunctions(
            "snippets/leftshift.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(TypeId.TYPE_I32, TypeId.TYPE_I32), listOf(TypeId.TYPE_I32)),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0),
                            OpCodes.Local.Get(1),
                            OpCodes.I32.Shl,
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code popcnt`() = runTest {
        assertFunctions(
            "snippets/popcnt.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(TypeId.TYPE_I32), listOf(TypeId.TYPE_I32)),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0),
                            OpCodes.I32.PopCnt,
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code or`() = runTest {
        assertFunctions(
            "snippets/or.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(TypeId.TYPE_I32, TypeId.TYPE_I32), listOf(TypeId.TYPE_I32)),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0),
                            OpCodes.Local.Get(1),
                            OpCodes.I32.Or,
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code xor`() = runTest {
        assertFunctions(
            "snippets/xor.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(TypeId.TYPE_I32, TypeId.TYPE_I32), listOf(TypeId.TYPE_I32)),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0),
                            OpCodes.Local.Get(1),
                            OpCodes.I32.Xor,
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code extend`() = runTest {
        assertFunctions(
            "snippets/extend.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I64.Extent_I32_S,
                            OpCodes.Flow.Call(0),
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code and`() = runTest {
        assertFunctions(
            "snippets/and.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(TypeId.TYPE_I32, TypeId.TYPE_I32), listOf(TypeId.TYPE_I32)),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0),
                            OpCodes.Local.Get(1),
                            OpCodes.I32.And,
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code leftrotate`() = runTest {
        assertFunctions(
            "snippets/leftrotate.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(TypeId.TYPE_I32, TypeId.TYPE_I32), listOf(TypeId.TYPE_I32)),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0),
                            OpCodes.Local.Get(1),
                            OpCodes.I32.Rotl,
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code rightrotate`() = runTest {
        assertFunctions(
            "snippets/rightrotate.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(TypeId.TYPE_I32, TypeId.TYPE_I32), listOf(TypeId.TYPE_I32)),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0),
                            OpCodes.Local.Get(1),
                            OpCodes.I32.Rotr,
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code call`() = runTest {
        assertFunctions(
            "snippets/call.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.Flow.Call(0),
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code unreachable`() = runTest {
        assertFunctions(
            "snippets/unreachable.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.Flow.Unreachable,
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code nop`() = runTest {
        assertFunctions(
            "snippets/nop.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.Flow.Nop,
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code br`() = runTest {
        assertFunctions(
            "snippets/br.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.Flow.Loop(listOf(
                                OpCodes.Global.Get(0),
                                OpCodes.I32.Const(1),
                                OpCodes.I32.Add,
                                OpCodes.Global.Set(0),
                                OpCodes.Global.Get(0),
                                OpCodes.Flow.Call(0),
                                OpCodes.Global.Get(0),
                                OpCodes.I32.Const(10),
                                OpCodes.I32.LTS,
                                OpCodes.Flow.BrIf(0)
                            ))
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code geu`() = runTest {
        assertFunctions(
            "snippets/geu.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I32.Const(2),
                            OpCodes.I32.GEU,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code clz`() = runTest {
        assertFunctions(
            "snippets/clz.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(TypeId.TYPE_I32), listOf(TypeId.TYPE_I32)),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0),
                            OpCodes.I32.Clz
                        )
                    )
                )

            )
        )
    }

    @Test
    fun `code ctz`() = runTest {
        assertFunctions(
            "snippets/ctz.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(TypeId.TYPE_I32), listOf(TypeId.TYPE_I32)),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0),
                            OpCodes.I32.Ctz
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code const`() = runTest {
        assertFunctions(
            "snippets/const.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code globalset`() = runTest {
        assertFunctions(
            "snippets/globalset.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.Global.Set(0),
                            OpCodes.Global.Get(0),
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code equal`() = runTest {
        assertFunctions(
            "snippets/equal.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I32.Const(2),
                            OpCodes.I32.Eq,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )

            )
        )
    }

    @Test
    fun `code return`() = runTest {
        assertFunctions(
            "snippets/return.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf(TypeId.TYPE_I32)),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I32.Const(90),
                            OpCodes.Flow.Return
                        )
                    )
                )

            )
        )
    }

    @Test
    fun `code remainder`() = runTest {
        assertFunctions(
            "snippets/remainder.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I32.Const(3),
                            OpCodes.I32.RemU,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code load`() = runTest {
        assertFunctions(
            "snippets/load.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(TypeId.TYPE_I32), listOf(TypeId.TYPE_I32)),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(0),
                            OpCodes.I32.Load(MemArg(2, 0)),
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code wrap`() = runTest {
        assertFunctions(
            "snippets/wrap.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.I64.Const(10L),
                            OpCodes.I32.WrapI64,
                            OpCodes.Flow.Call(0)
                    ))
                )
            )
        )
    }

    @Test
    fun `code promote`() = runTest {
        assertFunctions(
            "snippets/promote.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.F32.Const(10.5f),
                            OpCodes.F64.PromoteF32,
                            OpCodes.Flow.Call(0)
                        ))
                )
            )
        )
    }

    @Test
    fun `code sqrt`() = runTest {
        assertFunctions(
            "snippets/sqrt.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.F32.Const(2.0f),
                            OpCodes.F32.Sqrt,
                            OpCodes.Flow.Call(0)
                        ))
                )
            )
        )
    }


    @Test
    fun `code addition`() = runTest {
        assertFunctions(
            "snippets/addition.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I32.Const(3),
                            OpCodes.I32.Add,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code multiplication`() = runTest {
        assertFunctions(
            "snippets/multiplication.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I32.Const(3),
                            OpCodes.I32.Mul,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )

            )
        )
    }

    @Test
    fun `code local`() = runTest {
        assertFunctions(
            "snippets/local.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        listOf(WasmCodeLocalRecord(1, 127)),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.Local.Set(0),
                            OpCodes.Local.Get(0),
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code ne`() = runTest {

        assertFunctions(
            "snippets/ne.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        listOf(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I32.Const(2),
                            OpCodes.I32.Ne,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )

            )
        )
    }

    @Test
    fun `code drop`() = runTest {
        assertFunctions(
            "snippets/drop.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        listOf(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I32.Const(20),
                            OpCodes.Flow.Drop,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code gt`() = runTest {
        FunctionRecord(
            signature = WasmSectionTypesRecord(listOf(), listOf()),
            body = WasmCodeRecord(
                1u,
                listOf(),
                listOf(
                    OpCodes.I32.Const(10),
                    OpCodes.I32.Const(2),
                    OpCodes.I32.GTU,
                    OpCodes.Flow.Call(0)
                )
            )
        )
    }

    @Test
    @IgnoreJsTarget
    fun `code nearest`() = runTest {
        assertFunctions(
            "snippets/nearest.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        listOf(),
                        listOf(
                            OpCodes.F32.Const(2.700000047683716f),
                            OpCodes.F32.Nearest,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }

    @Test
    @IgnoreJsTarget
    fun `code floor`() = runTest {
        assertFunctions(
            "snippets/floor.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        listOf(),
                        listOf(
                            OpCodes.F32.Const(-2.700000047683716f),
                            OpCodes.F32.Floor,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }

    @Test
    @IgnoreJsTarget
    fun `code reinterpret`() = runTest {
        assertFunctions(
            "snippets/reinterpret.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        listOf(),
                        listOf(
                            OpCodes.F32.Const(-0.0f),
                            OpCodes.I32.ReinterpretF32,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }


    @Test
    @IgnoreJsTarget
    fun `code ceil`() = runTest {
        assertFunctions(
            "snippets/ceil.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        listOf(),
                        listOf(
                            OpCodes.F32.Const(2.700000047683716f),
                            OpCodes.F32.Ceil,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }

    @Test
    @IgnoreJsTarget
    fun `code truncf2f`() = runTest {
        assertFunctions(
            "snippets/truncf2f.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        listOf(),
                        listOf(
                            OpCodes.F32.Const(-2.700000047683716f),
                            OpCodes.F32.Trunc,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code copy`() = runTest {
        assertFunctions(
            "snippets/copy.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        listOf(),
                        listOf(
                            OpCodes.F32.Const(10f),
                            OpCodes.F32.Const(-2f),
                            OpCodes.F32.CopySign,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code block`() = runTest {
        assertFunctions(
            "snippets/block.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(TypeId.TYPE_I32), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        listOf(),
                        listOf(
                            OpCodes.Flow.Block(
                                listOf(
                                    OpCodes.Local.Get(0),
                                    OpCodes.I32.Const(100),
                                    OpCodes.I32.Eq,
                                    OpCodes.Flow.If(listOf(
                                        OpCodes.Flow.Br(1)
                                    )),
                                    OpCodes.Local.Get(0),
                                    OpCodes.Flow.Call(0)
                                ),
                            )
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code ifelse`() = runTest {
        assertFunctions(
            "snippets/ifelse.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        listOf(),
                        listOf(
                            OpCodes.I32.Const(0),
                            OpCodes.Flow.If(listOf(
                                OpCodes.I32.Const(1),
                                OpCodes.Flow.Call(0),
                                OpCodes.Flow.Else(listOf(
                                    OpCodes.I32.Const(0),
                                    OpCodes.Flow.Call(0),
                                ))
                            )),
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code demote`() = runTest {
        assertFunctions(
            "snippets/demote.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.F64.Const(10.5),
                            OpCodes.F32.DemoteF64,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code negate`() = runTest {
        assertFunctions(
            "snippets/negate.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.F32.Const(10.0f),
                            OpCodes.F32.Neg,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code convert`() = runTest {
        assertFunctions(
            "snippets/convert.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(listOf(), listOf()),
                    body = WasmCodeRecord(
                        1u,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.F32.ConvertI32S,
                            OpCodes.Flow.Call(0)
                        )
                    )
                )
            )
        )
    }


    @Test
    fun `loop`() = runTest {
        assertFunctions(
            "loop.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionTypesRecord(
                    listOf(TypeId.TYPE_I32),
                    listOf(TypeId.TYPE_I32)
                    ),
                    body = WasmCodeRecord(
                        1u,
                        listOf(WasmCodeLocalRecord(1, 127)),
                        listOf(
                            OpCodes.I32.Const(0),
                            OpCodes.Local.Set(1),
                            OpCodes.Flow.Block(listOf(
                                OpCodes.Flow.Loop(listOf(
                                    OpCodes.I32.Const(1),
                                    OpCodes.Flow.If(listOf(
                                        OpCodes.Local.Get(1),
                                        OpCodes.Local.Get(0),
                                        OpCodes.I32.Eq,
                                        OpCodes.Flow.If(listOf(
                                            OpCodes.Flow.Br(3)
                                        )),
                                        OpCodes.Local.Get(1),
                                        OpCodes.I32.Const(1),
                                        OpCodes.I32.Add,
                                        OpCodes.Local.Set(1),
                                        OpCodes.Flow.Br(1)
                                    ))
                                )
                            ))),
                            OpCodes.I32.Const(-1),
                            OpCodes.Flow.Return,
                        )
                    )
                )
            )
        )
    }

}


private fun assertFunction(actual: FunctionRecord, expected: FunctionRecord) {
    assertEquals(expected.signature, actual.signature)
    assertContentEquals(expected = expected.body.locals, actual = actual.body.locals, "locals don't match")
    assertContentEquals(expected = expected.body.opCodes, actual = actual.body.opCodes, "opCodes don't match")
}

private suspend fun assertFunctions(resourcePath: String, expected: List<FunctionRecord>) {
    val buffer = resourceAsByteBuffer(resourcePath)
    val wasmModule = WasmParser().parse(buffer)

    val functions = with(wasmModule!!) {
        buffer.scanFunctions()
    }

    functions.zip(expected).forEach { (function, expectedFunction) ->
        assertFunction(function, expectedFunction)
    }
}