package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.dwarf.debugStrSection
import org.jetbrains.hexana.tests.IgnoreJsTarget
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.components.*
import org.jetbrains.hexana.wasm.opcodes.MemArg
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.sections.*
import kotlin.test.*
import org.jetbrains.hexana.wasm.sections.WasmCodeLocalRecord
import org.jetbrains.hexana.wasm.sections.WasmCodeRecord
import org.jetbrains.hexana.wasm.sections.WasmFixedI32Limits
import org.jetbrains.hexana.wasm.sections.WasmI32Type
import org.jetbrains.hexana.wasm.sections.WasmImportRecord
import org.jetbrains.hexana.wasm.sections.WasmOpenI32Limits
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.consumeString
import org.jetbrains.hexana.wasm.sections.WasmMemType
import kotlin.test.Test
import kotlin.test.Ignore
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WasmParserTests {

    @Test
    fun parseCustomSectionsBasic() = runTest {
        val buffer = resourceAsByteBuffer("maps/skiko.wasm")
        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule

        assertEquals(2, wasmModule.customSections.size)

        with (wasmModule) {
            val sourceMappingSection = getCustomSectionByName("sourceMappingURL")
            assertNotNull(sourceMappingSection)
            val payload = with (sourceMappingSection) {
                buffer.position(sourceMappingSection.contentOffset)
                buffer.consumeString()
            }
            assertEquals("skiko.wasm.map", payload)

            assertNull(getCustomSectionByName("non-existent-sourceMappingURL"))

            assertEquals(listOf(sourceMappingSection), getCustomSectionsByName("sourceMappingURL"))
            assertTrue(getCustomSectionsByName("non-existent-sourceMappingURL").isEmpty())

            val customSection = getCustomSectionByName("name")
            assertNotNull(customSection)
            with (customSection) {
                buffer.parseAsNameSection()
            }
        }
    }

    @Test
    fun parseCustomNameSectionKotlin() = runTest {
        val buffer = resourceAsByteBuffer("mpp-demo.wasm")
        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule

        assertEquals(2, wasmModule.customSections.size)

        with (wasmModule) {
            val customSection = getCustomSectionByName("name")
            assertNotNull(customSection)
            with (customSection) {
                val nameSection = buffer.parseAsNameSection()
                assertEquals("<compose-multiplatform-core.compose.mpp:demo>", nameSection.moduleSubsection?.moduleName)
                assertNotNull(nameSection.functionSubsection)
                assertEquals(63783, nameSection.functionSubsection.namemap.size)
                assertEquals("kotlinx.serialization.internal.TripleSerializer\$descriptor\$lambda.invoke", nameSection.functionSubsection.namemap[8390])

                assertNotNull(nameSection.localsSection)
                assertEquals(63783, nameSection.localsSection.namemap.size)
                assertEquals(mapOf(0 to "<this>", 1 to  "\$entryIterator"), nameSection.localsSection.namemap[913])

                assertNotNull(nameSection.typesSection)
                assertEquals(56187,nameSection.typesSection.namemap.size)
                assertEquals("<classVTable>",nameSection.typesSection.namemap[30])

                assertNotNull(nameSection.globalsNameSection)
                assertEquals(35865, nameSection.globalsNameSection.namemap.size)
                assertEquals("<classITable>", nameSection.globalsNameSection.namemap[1983])

                assertNotNull(nameSection.fieldsNameSection)
                assertEquals(28907, nameSection.fieldsNameSection.namemap.size)
                assertEquals("androidx.lifecycle.Event_ON_ANY_instance", nameSection.globalsNameSection.namemap[2007])
            }
        }
    }


    @Test
    fun parseSkikoSections() = runTest {
        val buffer = resourceAsByteBuffer("skiko.wasm")

        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as CoreWasmComponent

        assertTrue(wasmModule.customSections.isEmpty())
        assertEquals(2796, wasmModule.typesSection?.contentSize)
        assertEquals(4265, wasmModule.importSection!!.contentSize)
        assertEquals(14363, wasmModule.functionSection?.contentSize)
        assertEquals(7, wasmModule.tableSection?.contentSize)
        assertEquals(7, wasmModule.memorySection?.contentSize)
        assertEquals(14, wasmModule.globalSection?.contentSize)
        assertEquals(49677, wasmModule.exportSection?.contentSize)
        assertNull(wasmModule.startSection?.contentSize)
        assertEquals(19761, wasmModule.elementSection?.contentSize)
        assertEquals(6380629, wasmModule.codeSection!!.contentSize)
        assertEquals(1833784, wasmModule.dataSection?.contentSize)
        assertNull(wasmModule.dataCountSection)
        assertEquals(49677, wasmModule.exportSection?.contentSize)
        assertEquals(3, wasmModule.exceptionSection?.contentSize)

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


        with (wasmModule.importSection!!) {
            val importRecords = buffer.getImportRecords()
            assertContentEquals(expectedImports, importRecords.map { "${it.prefix}::${it.name}"})
        }

        with(wasmModule) {
            val funs = buffer.scanFunctions()
            assertEquals(14142, funs.size)
        }
    }

    @Test
    fun parseTryCatch() = runTest {
        val buffer = resourceAsByteBuffer("kotlin/try.wasm")
        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as CoreWasmComponent


        with (wasmModule.codeSection!!) {
            val code = buffer.scan()
        }
    }

    @Test
    fun parseLongImports() = runTest {
        val buffer = resourceAsByteBuffer("string.wasm")
        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as CoreWasmComponent


        with(wasmModule.importSection!!) {
            val importRecords = buffer.getImportRecords()

            assertEquals(4, importRecords.size)

            assertEquals(
                WasmImportRecord(
                34, 515,
                    "abcdefghijklmnopqrstuvwxyz0123456789_0_abcdefghijklmnopqrstuvwxyz0123456789_1_abcdefghijklmnopqrstuvwxyz0123456789_2_abcdefghijklmnopqrstuvwxyz0123456789_2_abcdefghijklmnopqrstuvwxyz0123456789_3_abcdefghijklmnopqrstuvwxyz0123456789_4_abcdefghijklmnopqrstuvwxyz0123456789_5_abcdefghijklmnopqrstuvwxyz0123456789_6_abcdefghijklmnopqrstuvwxyz0123456789_7_abcdefghijklmnopqrstuvwxyz0123456789_8_abcdefghijklmnopqrstuvwxyz0123456789_9_abcdefghijklmnopqrstuvwxyz0123456789_10_abcdefghijklmnopqrstuvwxyz0123456789_11_",
                    "x",
                    WasmTypeIndexExternalType(0)
                ), importRecords[0]
            )

            assertEquals(
                WasmImportRecord(
                549, 11,
                    "string",
                    "a",
                    WasmTypeIndexExternalType(0)
                ), importRecords[1]
            )

            assertEquals(
                WasmImportRecord(
                560, 6,
                    "a",
                    "b",
                    WasmTypeIndexExternalType(0)
                ), importRecords[2]
            )

            assertEquals(
                WasmImportRecord(
                    566, 12,
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
                    signature = WasmSectionFunctionTypeRecord(11, 6,listOf(WasmI32Type, WasmI32Type), listOf(WasmI32Type)),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0u),
                            OpCodes.Local.Get(1u),
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
                    signature = WasmSectionFunctionTypeRecord(11, 5,listOf(WasmI32Type), listOf(WasmI32Type)),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0u),
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
                    signature = WasmSectionFunctionTypeRecord(11, 6, listOf(WasmI32Type, WasmI32Type), listOf(WasmI32Type)),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0u),
                            OpCodes.Local.Get(1u),
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
                    signature = WasmSectionFunctionTypeRecord(11, 6, listOf(WasmI32Type, WasmI32Type), listOf(WasmI32Type)),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0u),
                            OpCodes.Local.Get(1u),
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I64.Extend_I32_S,
                            OpCodes.Flow.Call(0u),
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
                    signature = WasmSectionFunctionTypeRecord(11, 6, listOf(WasmI32Type, WasmI32Type), listOf(WasmI32Type)),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0u),
                            OpCodes.Local.Get(1u),
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
                    signature = WasmSectionFunctionTypeRecord(11, 6, listOf(WasmI32Type, WasmI32Type), listOf(WasmI32Type)),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0u),
                            OpCodes.Local.Get(1u),
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
                    signature = WasmSectionFunctionTypeRecord(11, 6,listOf(WasmI32Type, WasmI32Type), listOf(WasmI32Type)),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0u),
                            OpCodes.Local.Get(1u),
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
                    signature = WasmSectionFunctionTypeRecord(11, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.Flow.Call(0u),
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
                    signature = WasmSectionFunctionTypeRecord(11, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
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
                    signature = WasmSectionFunctionTypeRecord(11, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.Flow.Loop(WasmI32Type,listOf(
                                OpCodes.Global.Get(0u),
                                OpCodes.I32.Const(1),
                                OpCodes.I32.Add,
                                OpCodes.Global.Set(0u),
                                OpCodes.Global.Get(0u),
                                OpCodes.Flow.Call(0u),
                                OpCodes.Global.Get(0u),
                                OpCodes.I32.Const(10),
                                OpCodes.I32.LTS,
                                OpCodes.Flow.BrIf(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I32.Const(2),
                            OpCodes.I32.GEU,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(11, 5,listOf(WasmI32Type), listOf(WasmI32Type)),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0u),
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
                    signature = WasmSectionFunctionTypeRecord(11, 5,listOf(WasmI32Type), listOf(WasmI32Type)),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.Local.Get(0u),
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.Global.Set(0u),
                            OpCodes.Global.Get(0u),
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I32.Const(2),
                            OpCodes.I32.Eq,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(11, 4,listOf(), listOf(WasmI32Type)),
                    body = WasmCodeRecord(
                        0,
                        1,
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I32.Const(3),
                            OpCodes.I32.RemU,
                            OpCodes.Flow.Call(0u)
                        )
                    )
                )
            )
        )
    }

    @Test
            /**
             *   0x0 | 00 61 73 6d | version 1 (Module)
             *       | 01 00 00 00
             *   0x8 | 01 06       | type section
             *   0xa | 01          | 1 count
             * --- rec group 0 (implicit) ---
             *-->0xb | 60 01 7f 01 | [type 0] SubType { is_final: true, supertype_idx: None, composite_type: CompositeType { inner: Func(FuncType { params: [I32], results: [I32] }), shared: false, descriptor_idx: None, describes_idx: None } }
             *       | 7f
             *  0x10 | 03 02       | func section
             *  0x12 | 01          | 1 count
             *  0x13 | 00          | [func 0] type 0
             *  0x14 | 05 03       | memory section
             *  0x16 | 01          | 1 count
             *  0x17 | 00 01       | [memory 0] MemoryType { memory64: false, shared: false, initial: 1, maximum: None, page_size_log2: None }
             *  0x19 | 07 23       | export section
             *  0x1b | 02          | 2 count
             *  0x1c | 06 6d 65 6d | export Export { name: "memory", kind: Memory, index: 0 }
             *       | 6f 72 79 02
             *       | 00
             *  0x25 | 16 6c 6f 61 | export Export { name: "load_first_item_in_mem", kind: Func, index: 0 }
             *       | 64 5f 66 69
             *       | 72 73 74 5f
             *       | 69 74 65 6d
             *       | 5f 69 6e 5f
             *       | 6d 65 6d 00
             *       | 00
             *  0x3e | 0a 09       | code section
             *  0x40 | 01          | 1 count
             * ============== func 0 ====================
             *-->0x41 | 07          | size of function
             *  0x42 | 00          | 0 local blocks
             *  0x43 | 41 00       | i32_const value:0
             *  0x45 | 28 02 00    | i32_load memarg:MemArg { align: 2, max_align: 2, offset: 0, memory: 0 }
             *  0x48 | 0b          | end
             *
             */
    fun `code load`() = runTest {
        assertFunctions(
            "snippets/load.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionFunctionTypeRecord(0xb, 5,listOf(WasmI32Type), listOf(WasmI32Type)),
                    body = WasmCodeRecord(
                        0x41,
                        8,
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.I64.Const(10L),
                            OpCodes.I32.WrapI64,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.F32.Const(10.5f),
                            OpCodes.F64.PromoteF32,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.F32.Const(2.0f),
                            OpCodes.F32.Sqrt,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I32.Const(3),
                            OpCodes.I32.Add,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I32.Const(3),
                            OpCodes.I32.Mul,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        listOf(WasmCodeLocalRecord(1, WasmI32Type)),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.Local.Set(0u),
                            OpCodes.Local.Get(0u),
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        listOf(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I32.Const(2),
                            OpCodes.I32.Ne,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        listOf(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.I32.Const(20),
                            OpCodes.Flow.Drop,
                            OpCodes.Flow.Call(0u)
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `code gt`() = runTest {
        FunctionRecord(
            signature = WasmSectionFunctionTypeRecord(0, 0,listOf(), listOf()),
            body = WasmCodeRecord(
                0,
                1,
                listOf(),
                listOf(
                    OpCodes.I32.Const(10),
                    OpCodes.I32.Const(2),
                    OpCodes.I32.GTU,
                    OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        listOf(),
                        listOf(
                            OpCodes.F32.Const(2.700000047683716f),
                            OpCodes.F32.Nearest,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15,3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        listOf(),
                        listOf(
                            OpCodes.F32.Const(-2.700000047683716f),
                            OpCodes.F32.Floor,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        listOf(),
                        listOf(
                            OpCodes.F32.Const(-0.0f),
                            OpCodes.I32.ReinterpretF32,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        listOf(),
                        listOf(
                            OpCodes.F32.Const(2.700000047683716f),
                            OpCodes.F32.Ceil,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        listOf(),
                        listOf(
                            OpCodes.F32.Const(-2.700000047683716f),
                            OpCodes.F32.Trunc,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        listOf(),
                        listOf(
                            OpCodes.F32.Const(10f),
                            OpCodes.F32.Const(-2f),
                            OpCodes.F32.CopySign,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(11, 4,listOf(WasmI32Type), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        listOf(),
                        listOf(
                            OpCodes.Flow.Block(WasmI32Type,
                                listOf(
                                    OpCodes.Local.Get(0u),
                                    OpCodes.I32.Const(100),
                                    OpCodes.I32.Eq,
                                    OpCodes.Flow.If(WasmI32Type,listOf(
                                        OpCodes.Flow.Br(1u)
                                    )),
                                    OpCodes.Local.Get(0u),
                                    OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        listOf(),
                        listOf(
                            OpCodes.I32.Const(0),
                            OpCodes.Flow.If(WasmI32Type,listOf(
                                OpCodes.I32.Const(1),
                                OpCodes.Flow.Call(0u),
                                OpCodes.Flow.Else(WasmI32Type, listOf(
                                    OpCodes.I32.Const(0),
                                    OpCodes.Flow.Call(0u),
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.F64.Const(10.5),
                            OpCodes.F32.DemoteF64,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.F32.Const(10.0f),
                            OpCodes.F32.Neg,
                            OpCodes.Flow.Call(0u)
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
                    signature = WasmSectionFunctionTypeRecord(15, 3,listOf(), listOf()),
                    body = WasmCodeRecord(
                        0,
                        1,
                        emptyList(),
                        listOf(
                            OpCodes.I32.Const(10),
                            OpCodes.F32.ConvertI32S,
                            OpCodes.Flow.Call(0u)
                        )
                    )
                )
            )
        )
    }


    @Test
    fun loop() = runTest {
        assertFunctions(
            "loop.wasm", listOf(
                FunctionRecord(
                    signature = WasmSectionFunctionTypeRecord(
                    11, 5,
                    listOf(WasmI32Type),
                    listOf(WasmI32Type)
                    ),
                    body = WasmCodeRecord(
                        0,
                        1,
                        listOf(WasmCodeLocalRecord(1, WasmI32Type)),
                        listOf(
                            OpCodes.I32.Const(0),
                            OpCodes.Local.Set(1u),
                            OpCodes.Flow.Block(null,listOf(
                                OpCodes.Flow.Loop(WasmI32Type,listOf(
                                    OpCodes.I32.Const(1),
                                    OpCodes.Flow.If(WasmI32Type,listOf(
                                        OpCodes.Local.Get(1u),
                                        OpCodes.Local.Get(0u),
                                        OpCodes.I32.Eq,
                                        OpCodes.Flow.If(WasmI32Type,listOf(
                                            OpCodes.Flow.Br(3u)
                                        )),
                                        OpCodes.Local.Get(1u),
                                        OpCodes.I32.Const(1),
                                        OpCodes.I32.Add,
                                        OpCodes.Local.Set(1u),
                                        OpCodes.Flow.Br(1u)
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

    /**
     * > wasm-objdump -x string.wasm | grep -A 20 "Global
     * Global[3]:
     *  - global[0] i32 mutable=0 - init i32=44
     *  - global[1] i32 mutable=1 - init i32=32812
     *  - global[2] i32 mutable=0 - init i32=3281
     */
    @Test
    fun `HX-T-22`() = runTest {
        val buffer = resourceAsByteBuffer("string.wasm")
        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as CoreWasmComponent

        with(wasmModule.globalSection) {
            this ?: error("Failed to parse global section")
            val globals = buffer.scan()
            assertEquals(3, globals.size)
            assertEquals(globals[0].type.valueType, WasmI32Type)
            assertEquals(globals[0].type.mutable, false)
            assertEquals(globals[0].expression, listOf(OpCodes.I32.Const(44)))
            assertEquals(globals[1].type.valueType, WasmI32Type)
            assertEquals(globals[1].type.mutable, true)
            assertEquals(globals[1].expression, listOf(OpCodes.I32.Const(32812)))
            assertEquals(globals[2].type.valueType, WasmI32Type)
            assertEquals(globals[2].type.mutable, false)
            assertEquals(globals[2].expression, listOf(OpCodes.I32.Const(32812)))
        }
    }

    @Test
    fun `HX-1-Component-Wasm`() = runTest {
        // https://github.com/JetBrains/hexana/issues/1
        val buffer = resourceAsByteBuffer("component/add.component.wasm")
        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as WasmComponent
        assertEquals(0, wasmModule.unresolvedSections.size)
        assertEquals(9, wasmModule.sections.size)
        assertEquals(1, wasmModule.customSections.size)
        assertEquals("producers", wasmModule.customSections[0].name)
        val coreInstanceExpressions = wasmModule.coreInstanceSection.flatMap {
            with(it) {
                buffer.parseCoreInstances()
            }
        }
        assertEquals(1, coreInstanceExpressions.size)
        assertEquals(0u, (coreInstanceExpressions[0] as ModuleInstanceCoreExpression).moduleIndex)
        assertEquals(0, (coreInstanceExpressions[0] as ModuleInstanceCoreExpression).instantiateArgs.size)
        val instanceExpressions = wasmModule.instanceSection.flatMap {
            with(it) {
                buffer.parseInstances()
            }
        }
        assertEquals(1, instanceExpressions.size)
        val componentInstanceExpression = instanceExpressions[0] as ComponentInstanceExpression
        assertEquals(0u, componentInstanceExpression.index)
        assertEquals(1, componentInstanceExpression.args.size)
        val arg = componentInstanceExpression.args[0]
        assertEquals("import-func-add", arg.name)
        assertEquals(0u, arg.instanceIndex)

        val typeSection = wasmModule.typesSection
        assertNotNull(typeSection)
        val types = with(wasmModule.typesSection!!) {
            buffer.scanTypes()
        }

        assertEquals(1, types.size)
        val type = types[0]
        assertIs<ComponentFunctionType>(type)
        assertEquals(2, type.params.size)
        assertIs<ComponentPrimitiveValueType>(type.params[0].value)
        assertIs<ComponentPrimitiveU32Type>((type.params[0].value as ComponentPrimitiveValueType).type)
        assertEquals("x", type.params[0].label)
        assertIs<ComponentPrimitiveValueType>(type.params[1].value)
        assertIs<ComponentPrimitiveU32Type>((type.params[1].value as ComponentPrimitiveValueType).type)
        assertEquals("y", type.params[1].label)
        assertIs<ComponentPrimitiveValueType>(type.ret)
        assertIs<ComponentPrimitiveU32Type>((type.ret as ComponentPrimitiveValueType).type)

        val moduleSection = wasmModule.moduleSection
        assertNotNull(moduleSection)

        val componentSection = wasmModule.componentSection
        assertNotNull(componentSection)

        val aliasSection = wasmModule.aliasSection
        assertNotNull(aliasSection)

        val cannonSection = wasmModule.canonSection
        assertNotNull(cannonSection)

        val valueSection = wasmModule.valueSection
        assertEquals(0,valueSection.size)

        val coreTypeSection = wasmModule.coreTypesSection
        assertEquals(0, coreTypeSection.size)
    }

    @Test
    fun `HX-1-Component-Wasm 1`() = runTest {
        // https://github.com/JetBrains/hexana/issues/1
        val buffer = resourceAsByteBuffer("component/auth.wasm")
        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as WasmComponent
        assertEquals(0, wasmModule.unresolvedSections.size)
        assertEquals(73, wasmModule.sections.size)
        assertEquals(2, wasmModule.customSections.size)
        assertEquals("component-name", wasmModule.customSections[0].name)
        assertEquals("producers", wasmModule.customSections[1].name)
    }

    @Test
    fun `HX-1-Component-Wasm 2`() = runTest {
        val buffer = resourceAsByteBuffer("component/greeter.wasm")
        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as WasmComponent
        assertEquals(0, wasmModule.unresolvedSections.size)
        assertEquals(69, wasmModule.sections.size)
        assertEquals(2, wasmModule.customSections.size)
        assertEquals("component-name", wasmModule.customSections[0].name)
        assertEquals("producers", wasmModule.customSections[1].name)
    }

    @Test
    fun `HX-1-Component-Wasm greater_wasm`() = runTest {
        val buffer = resourceAsByteBuffer("component/greeter.wasm")
        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as WasmComponent
        assertEquals(0, wasmModule.unresolvedSections.size)
        assertEquals(69, wasmModule.sections.size)
        assertEquals(2, wasmModule.customSections.size)
        assertEquals("component-name", wasmModule.customSections[0].name)
        assertEquals("producers", wasmModule.customSections[1].name)
    }

    @Test
    fun `HX-1-Component-Wasm http-gateway_wasm`() = runTest {
        val buffer = resourceAsByteBuffer("component/http-gateway.wasm")
        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as WasmComponent
        assertEquals(0, wasmModule.unresolvedSections.size)
        assertEquals(131, wasmModule.sections.size)
        assertEquals(2, wasmModule.customSections.size)
        assertEquals("component-name", wasmModule.customSections[0].name)
        assertEquals("producers", wasmModule.customSections[1].name)
    }

    @Test
    fun `HX-1-Component-Wasm logger_wasm`() = runTest {
        val buffer = resourceAsByteBuffer("component/logger.wasm")
        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as WasmComponent
        assertEquals(0, wasmModule.unresolvedSections.size)
        assertEquals(67, wasmModule.sections.size)
        assertEquals(2, wasmModule.customSections.size)
        assertEquals("component-name", wasmModule.customSections[0].name)
        assertEquals("producers", wasmModule.customSections[1].name)
    }

    @Test
    fun `HX-1-Component-Wasm composed_wasm`() = runTest {
        val buffer = resourceAsByteBuffer("component/composed.wasm")
        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as WasmComponent
        assertEquals(0, wasmModule.unresolvedSections.size)
        assertEquals(41, wasmModule.sections.size)
        assertEquals(2, wasmModule.customSections.size)
        assertEquals("component-name", wasmModule.customSections[0].name)
        assertEquals("producers", wasmModule.customSections[1].name)


        val types = wasmModule.sections.filterIsInstance<WasmComponentTypesSection>().flatMap {
            with(it) {
                buffer.scanTypes()
            }
        }
    }

    /**
     * > wasm-objdump -x string.wasm | grep -A 20 "Memory"
     * Memory[1]:
     *  - memory[0] pages: initial=1
     */
    @Test
    fun `HX-T-23`() = runTest {
        val buffer = resourceAsByteBuffer("string.wasm")
        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as CoreWasmComponent


        with(wasmModule.memorySection) {
            this ?: error("Failed to parse global section")
            val memory = buffer.scan()
            assertEquals(1, memory.size)
            assertIs<WasmOpenI32Limits>(memory[0].limits)
            assertEquals(memory[0].limits.min, 1)
        }
    }


    @Test
    fun parseTagTypeImport() = runTest {
        val buffer = resourceAsByteBuffer("kotlin/hello.wasm")

        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as CoreWasmComponent

        with(wasmModule.importSection!!) {
            val importRecords = buffer.getImportRecords()
            assertEquals(690, importRecords.size)
        }
    }

    @Test
    fun parseThreadsSpecificImportTypes() = runTest {
        val buffer = resourceAsByteBuffer("threads/bpnode.wasm")
        val wasmParseResult = WasmParser().parse(buffer)

        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as CoreWasmComponent

        val importRecords =with(wasmModule.importSection!!) {
             buffer.getImportRecords()
        }
        assertEquals(888, importRecords.size)
        with(importRecords[0]){
            assertEquals("i", prefix)
            assertEquals("memory", name)
            assertEquals(WasmMemType(WasmFixedI32Limits(0x1000, 0x1000, true)), type)
        }
    }

    @Test
    fun parseDwarfStrSection() = runTest {
        val buffer = resourceAsByteBuffer("c/utf8-c.wasm")
        val wasmParseResult = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(wasmParseResult)

        val wasmModule = wasmParseResult.wasmModule as CoreWasmComponent

        val strSection = (wasmModule as BaseModule).debugStrSection()!!
        val entries = with(strSection){
            buffer.scanDwarfStr()
        }
        val strings = entries.map {
            with(it) {
                buffer.str()
            }
        }

        assertEquals("errmsgidx", strings[30])
        assertEquals("__syscall_setpriority", strings[1])
        assertEquals("__pthread_exit", strings[99])
    }

    @Test
    fun `#77 NIE on go-wasm module`() = runTest {
        val buffer = resourceAsByteBuffer("wasm-component-experiment-2/repl-api.wasm")
        val wasmParseResult = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(wasmParseResult)
        val wasmModule = wasmParseResult.wasmModule as WasmComponent
        val exports = wasmModule.exportSection.flatMap {
            with(it) {
                buffer.scan()
            }
        }
        assertEquals(9, exports.size)
        assertEquals("http-client", exports[1].exportName)
        assertEquals(ComponentSort.Type, exports[1].sortIndex.sort)
        assertEquals(2.toUInt(), exports[1].sortIndex.index)
    }
}


private fun assertFunction(actual: FunctionRecord, expected: FunctionRecord) {
    assertEquals(expected.signature, actual.signature)
    assertContentEquals(expected = expected.body.locals, actual = actual.body.locals, "locals don't match")
    assertContentEquals(expected = expected.body.opCodes, actual = actual.body.opCodes, "opCodes don't match")
}

private suspend fun assertFunctions(resourcePath: String, expected: List<FunctionRecord>) {
    val buffer = resourceAsByteBuffer(resourcePath)
    val wasmParseResult = WasmParser().parse(buffer)

    assertIs<WasmParseSuccess>(wasmParseResult)
    val wasmModule = wasmParseResult.wasmModule as CoreWasmComponent

    val functions = with(wasmModule) {
        buffer.scanFunctions()
    }

    functions.zip(expected).forEach { (function, expectedFunction) ->
        assertFunction(function, expectedFunction)
    }
}