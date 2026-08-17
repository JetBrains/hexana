package org.jetbrains.hexana.wat

import com.intellij.psi.PsiComment
import com.intellij.psi.SyntaxTraverser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class WatResolveTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String {
        val relativePath = "src/test/data/resolve"
        return Path.of(relativePath).toAbsolutePath().toString()
    }

    fun doTest(watText: String) {
        val file = myFixture.configureByText(WatFileType.INSTANCE, watText.trimIndent())
        val target = SyntaxTraverser.psiTraverser(file)
            .filter { it is PsiComment }
            .find { it.text == "(;TARGET;)" }
        assertNotNull("(;TARGET;) comment is missing", target)
        val expectedResolveOffset = target!!.textRange.endOffset

        val referenceAtCaret = file.findReferenceAt(myFixture.editor.caretModel.offset)
        assertNotNull("No reference at caret", referenceAtCaret)

        val resolveResult = referenceAtCaret!!.resolve()
        assertNotNull("Resolve failed", resolveResult)

        assertEquals("Unexpected resolve result offset",
            expectedResolveOffset,
            resolveResult!!.textOffset)
    }

    fun testCallRef() {
        doTest($$"""
            (module
              (func $f0)
              (func (;TARGET;)$f1 (param $x i64) (result i64)
                (call <caret>$f1 (local.get $x))
              )
              (func $f2)
            )
        """)
    }

    fun testCallRefUnfolded() {
        doTest($$"""
            (module
              (func $f0)
              (func (;TARGET;)$f1 (param $x i64) (result i64)
                call <caret>$f1)
              )
              (func $f2)
            )
        """)
    }

    fun testCallRefByIndex() {
        doTest($$"""
            (module
              (import "env" "f0" (func $f0))
              (func (;TARGET;)$f1 (param $x i64) (result i64)
                (call <caret>1 (local.get $x))
              )
              (func $f2)
            )
        """)
    }

    fun testCallRefByIndexUnfolded() {
        doTest($$"""
            (module
              (import "env" "f0" (func $f0))
              (func (;TARGET;)$f1 (param $x i64) (result i64)
                call <caret>1
              )
              (func $f2))
        """)
    }

    fun testCallRefToImport() {
        doTest($$"""
            (module
              (import "env" "f0" (func $f0))
              (import "env" "f1" (func (;TARGET;)$f1))
              (import "env" "f2" (func $f2))
              (func $4
                (call <caret>$f1)
              )
            )
        """)
    }

    fun testCallRefToImportByIndex() {
        doTest($$"""
            (module
              (import "env" "f0" (func $f0))
              (func) 
              (import "env" "f1" (func (;TARGET;)$f1))
              (import "env" "f2" (func $f2))
              (func 
                (call <caret>2)
              )
            )
        """)
    }

    fun testExportFunc() {
        doTest($$"""
            (module
              (export "f" (func <caret>$1))
              (func $0)
              (func (;TARGET;)$1)
              (func $2)
            )
        """)
    }

    fun testExportFuncByIndex() {
        doTest($$"""
            (module
              (export "f" (func <caret>1))
              (func $0)
              (func (;TARGET;)$f)
              (func $2)
            )
        """)
    }

    fun testTableRef() {
        doTest($$"""
            (module
              (table $t0 1 1 funcref)
              (table (;TARGET;)$t1 1 1 funcref)
              (table $t2 1 1 funcref)
              (elem <caret>$t1 (i32.const 0) $f)
            )
        """)
    }

    fun testTableRefByIndex() {
        doTest($$"""
            (module
              (table $t0 1 1 funcref)
              (table (;TARGET;)$t1 1 1 funcref)
              (table $t2 1 1 funcref)
              (elem <caret>1 (i32.const 0) $f)
            )
        """)
    }

    fun testFuncElem() {
        doTest($$"""
            (module
              (table $t0 3 3 funcref)
              (elem $t0 (i32.const 0) $f0 <caret>$f1 $f2)
              (func $f0)
              (func (;TARGET;)$f1)
              (func $f2)
            )
        """)
    }

    fun testFuncElemByIndex() {
        doTest($$"""
            (module
              (table $t0 3 3 funcref)
              (elem $t0 (i32.const 0) $f0 <caret>1 $f2)
              (func $f0)
              (func (;TARGET;)$f1)
              (func $f2)
            )
        """)
    }

    fun testTypeCallIndirect() {
        doTest($$"""
            (module
              (type (;TARGET;)$1 (func))
              (func $1
                (call_indirect (type <caret>$1))
              )
            )
        """)
    }

    fun testTypeCallIndirectByIndex() {
        doTest($$"""
            (module
              (type (;TARGET;)$t1 0 (func))
              (func $1
                (call_indirect (type <caret>0))
              )
            )
        """)
    }

    fun testTypeInFunc() {
        doTest($$"""
            (module
              (type (;TARGET;)$1 (func))
              (func $1 (type <caret>$1)
              )
            )
        """)
    }

    fun testTypeInFuncByIndex() {
        doTest($$"""
            (module
              (type (;TARGET;)$1 (func))
              (func $1 (type <caret>0)
              )
            )
        """)
    }

    fun testGlobalSet() {
        doTest($$"""
            (module
              (global (;TARGET;)$1 (mut i32) (i32.const 0))
              (func $1
                (global.set <caret>$1 (i32.const 1))
              )
            )
        """)
    }

    fun testGlobalSetUnfolded() {
        doTest($$"""
            (module
              (global (;TARGET;)$1 (mut i32) (i32.const 0))
              (func $1
                global.set <caret>$1
              )
            )
        """)
    }

    fun testGlobalSetByIndex() {
        doTest($$"""
            (module
              (global (;TARGET;)$g1 (mut i32) (i32.const 0))
              (func $1
                (global.set <caret>0 (i32.const 1))
              )
            )
        """)
    }

    fun testGlobalSetByIndexUnfolded() {
        doTest($$"""
            (module
              (global (;TARGET;)$g1 (mut i32) (i32.const 0))
              (func $1
                global.set <caret>0
              )
            )
        """)
    }

    fun testGlobalGet() {
        doTest($$"""
            (module
              (global (;TARGET;)$1 (mut i32) (i32.const 0))
              (func $1
                (global.get <caret>$1
              )
            )
        """)
    }

    fun testGlobalGetUnfolded() {
        doTest($$"""
            (module
              (global (;TARGET;)$1 (mut i32) (i32.const 0))
              (func $1
                global.get <caret>$1
              )
            )
        """)
    }

    fun testGlobalGetByIndex() {
        doTest($$"""
            (module
              (global (;TARGET;)$g1 (mut i32) (i32.const 0))
              (func $1
                (global.get <caret>0)
              )
            )
        """)
    }

    fun testGlobalGetByIndexUnfolded() {
        doTest($$"""
            (module
              (global (;TARGET;)$g1 (mut i32) (i32.const 0))
              (func $1
                global.get <caret>0
              )
            )
        """)
    }

    fun testTagThrow() {
        doTest($$"""
            (module
              (tag (;TARGET;)$1 (type $0) (param i32))
              (func $1
                (throw <caret>$1 (i32.const 0))
              )
            )
        """)
    }

    fun testTagThrowUnfolded() {
        doTest($$"""
            (module
              (tag (;TARGET;)$1 (type $0) (param i32))
              (func $1
                throw <caret>$1
              )
            )
        """)
    }

    fun testTagThrowIndex() {
        doTest($$"""
            (module
              (tag (;TARGET;)$tag1 (type $0) (param i32))
              (func $1
                (throw <caret>0 (i32.const 0))
              )
            )
        """)
    }

    fun testTagThrowIndexUnfolded() {
        doTest($$"""
            (module
              (tag (;TARGET;)$tag1 (type $0) (param i32))
              (func $1
                throw <caret>0
              )
            )
        """)
    }

    fun testTagCatch() {
        doTest($$"""
            (module
              (tag (;TARGET;)$1 (type $0) (param i32))
              (func $1
                (catch <caret>$1)
              )
            )
        """)
    }

    fun testTagCatchUnfolded() {
        doTest($$"""
            (module
              (tag (;TARGET;)$1 (type $0) (param i32))
              (func $1
                catch <caret>$1
              )
            )
        """)
    }

    fun testTagCatchByIndex() {
        doTest($$"""
            (module
              (tag (;TARGET;)$tag1 (type $0) (param i32))
              (func $1
                (catch <caret>0)
              )
            )
        """)
    }

    fun testTagCatchByIndexUnfolded() {
        doTest($$"""
            (module
              (tag (;TARGET;)$tag1 (type $0) (param i32))
              (func $1
                catch <caret>0
              )
            )
        """)
    }

    fun testLocalSet() {
        doTest($$"""
            (module
              (func $1 
                (local $0 i32)
                (local (;TARGET;)$1 i32)
                (local.set <caret>$1 (i32.const 10))
              )
            )
        """)
    }

    fun testLocalSetUnfolded() {
        doTest($$"""
            (module
              (func $1 
                (local $0 i32)
                (local (;TARGET;)$1 i32)
                local.set <caret>$1
              )
            )
        """)
    }

    fun testLocalSetByIndex() {
        doTest($$"""
            (module
              (func $1 (param $0 i32)
                (local $l0 i32)
                (local (;TARGET;)$l1 i32)
                (local.set <caret>2 (i32.const 10))
              )
            )            
        """)
    }

    fun testLocalSetByIndexUnfolded() {
        doTest($$"""
            (module
              (func $1 (param $0 i32)
                (local $l0 i32)
                (local (;TARGET;)$l1 i32)
                local.set <caret>2
              )
            )
        """)
    }

    fun testLocalGet() {
        doTest($$"""
            (module
              (func $1 
                (local $0 i32)
                (local (;TARGET;)$1 i32)
                (local.get <caret>$1 (i32.const 10))
              )
            )
        """)
    }

    fun testLocalGetUnfolded() {
        doTest($$"""
            (module
              (func $1 
                (local $0 i32)
                (local (;TARGET;)$1 i32)
                local.get <caret>$1
              )
            )
        """)
    }

    fun testLocalGetByIndex() {
        doTest($$"""
            (module
              (func $1 (param $0 i32)
                (local $l0 i32)
                (local (;TARGET;)$l1 i32)
                (local.get <caret>2 (i32.const 10))
              )
            )
        """)
    }

    fun testLocalGetByIndexUnfolded() {
        doTest($$"""
            (module
              (func $1 (param $0 i32)
                (local $l0 i32)
                (local (;TARGET;)$l1 i32)
                local.get <caret>2
              )
            )
        """)
    }

    fun testLocalTee() {
        doTest($$"""
            (module
              (func $1 
                (local $0 i32)
                (local (;TARGET;)$1 i32)
                (local.tee <caret>$1 (i32.const 10))
              )
            )
        """)
    }

    fun testLocalTeeUnfolded() {
        doTest($$"""
            (module
              (func $1 
                (local $0 i32)
                (local (;TARGET;)$1 i32)
                local.tee <caret>$1
              )
            )
        """)
    }

    fun testLocalTeeByIndex() {
        doTest($$"""
            (module
              (func $1 (param $0 i32)
                (local $l0 i32)
                (local (;TARGET;)$l1 i32)
                (local.tee <caret>2 (i32.const 10))
              )
            )
        """)
    }

    fun testLocalTeeByIndexUnfolded() {
        doTest($$"""
            (module
              (func $1 (param $0 i32)
                (local $l0 i32)
                (local (;TARGET;)$l1 i32)
                local.tee <caret>2
              )
            )
        """)
    }

    fun testParam() {
        doTest($$"""
            (module
              (func $1 (param $0 i32) (param (;TARGET;)$1 i32)
                (local $2 i32)
                (local.set <caret>$1 (i32.const 10))
              )
            )
        """)
    }

    fun testParamByIndex() {
        doTest($$"""
            (module
              (func $1 (param $0 i32) (param (;TARGET;)$1 i32)
                (local $2 i32)
                (local.set <caret>1 (i32.const 10))
              )
            )
        """)
    }

    fun testBlock() {
        doTest($$"""
            (module
              (func $1
                (block (;TARGET;)$L1
                  (loop $L2
                    (if $L3
                      (br <caret>$L1)
                    )
                  )
                )
              )
            )
        """)
    }

    fun testBlockUnfolded() {
        doTest($$"""
            (module
              (func $1
                block (;TARGET;)$L1
                  loop $L2
                    (if $L3
                      (br <caret>$L1)
                    )
                  end
                end
              )
            )
        """)
    }

    fun testBlockByIndex() {
        doTest($$"""
            (module
              (func $1
                (block (;TARGET;)$L1
                  (loop $L2
                    (if $L3
                      (br <caret>2)
                    )
                  )
                )
              )
            )
        """)
    }

    fun testBlockByIndexUnfolded() {
        doTest($$"""
            (module
              (func $1
                block (;TARGET;)$L1
                  loop $L2
                    (if $L3
                      (br <caret>2)
                    )
                  end
                end
              )
            )
        """)
    }

    fun testLoop() {
        doTest($$"""
            (module
              (func $1
                (loop (;TARGET;)$L1
                  (block $L2
                    (if $L3
                      (br <caret>$L1)
                    )
                  )
                )
              )
            )  
        """)
    }

    fun testLoopUnfolded() {
        doTest($$"""
            (module
              (func $1
                loop (;TARGET;)$L1
                  block $L2
                    (if $L3
                      (br <caret>$L1)
                    )
                  end
                end
              )
            )
        """)
    }

    fun testLoopByIndex() {
        doTest($$"""
            (module
              (func $1
                (loop (;TARGET;)$L1
                  (block $L2
                    (if $L3
                      (br <caret>2)
                    )
                  )
                )
              )
            )
        """)
    }

    fun testLoopByIndexUnfolded() {
        doTest($$"""
            (module
              (func $1
                loop (;TARGET;)$L1
                  block $L2
                    (if $L3
                      (br <caret>2)
                    )
                  end
                end
              )
            )
        """)
    }

    fun testIf() {
        doTest($$"""
            (module
              (func $1
                (if (;TARGET;)$L1
                  (block $L2
                    (loop $L3
                      (br <caret>$L1)
                    )
                  )
                )
              )
            )
        """)
    }

    fun testIfUnfolded() {
        doTest($$"""
            (module
              (func $1
                if (;TARGET;)$L1
                  block $L2
                    loop $L3
                      (br <caret>$L1)
                    end
                  end
                end
              )
            )
        """)
    }

    fun testIfByIndex() {
        doTest($$"""
            (module
              (func $1
                (if (;TARGET;)$L1
                  (block $L2
                    (loop $L3
                      (br <caret>2)
                    )
                  )
                )
              )
            )
        """)
    }

    fun testIfByIndexUnfolded() {
        doTest($$"""
            (module
              (func $1
                if (;TARGET;)$L1
                  block $L2
                    loop $L3
                      (br <caret>2)
                    end
                  end
                end
              )
            )
        """)
    }

    fun testBr() {
        doTest($$"""
            (module
              (func $1
                (if (;TARGET;)$L1
                  (block $L2
                    (loop $L3
                      (br <caret>$L1)
                    )
                  )
                )
              )
            )
        """)
    }

    fun testBrUnfolded() {
        doTest($$"""
            (module
              (func $1
                if (;TARGET;)$L1
                  block $L2
                    loop $L3
                      br <caret>$L1
                    end
                  end
                end
              )
            )
        """)
    }

    fun testBrByIndex() {
        doTest($$"""
            (module
              (func $1
                (if (;TARGET;)$L1
                  (block $L2
                    (loop $L3
                      (br <caret>2)
                    )
                  )
                )
              )
            )
        """)
    }

    fun testBrByIndexUnfolded() {
        doTest($$"""
            (module
              (func $1
                if (;TARGET;)$L1
                  block $L2
                    loop $L3
                      br <caret>2
                    end
                  end
                end
              )
            )
        """)
    }

    fun testBrIf() {
        doTest($$"""
            (module
              (func $1
                (if (;TARGET;)$L1
                  (block $L2
                    (loop $L3
                      (br_if <caret>$L1)
                    )
                  )
                )
              )
            )
        """)
    }

    fun testBrIfUnfolded() {
        doTest($$"""
            (module
              (func $1
                if (;TARGET;)$L1
                  block $L2
                    loop $L3
                      br_if <caret>$L1
                    end
                  end
                end
              )
            )
        """)
    }

    fun testBrIfByIndex() {
        doTest($$"""
            (module
              (func $1
                (if (;TARGET;)$L1
                  (block $L2
                    (loop $L3
                      (br_if <caret>2)
                    )
                  )
                )
              )
            )
        """)
    }

    fun testBrIfByIndexUnfolded() {
        doTest($$"""
            (module
              (func $1
                if (;TARGET;)$L1
                  block $L2
                    loop $L3
                      br_if <caret>2
                    end
                  end
                end
              )
            )
        """)
    }

    fun testBrTable() {
        doTest($$"""
            (module
              (func $1
                (if (;TARGET;)$L1
                  (block $L2
                    (loop $L3
                      (br_table $b3 $b2 <caret>$L1 (local.get 0))
                    )
                  )
                )
              )
            )
        """)
    }

    fun testBrTableUnfolded() {
        doTest($$"""
            (module
              (func $1
                if (;TARGET;)$L1
                  block $L2
                    loop $L3
                      br_table $b3 $b2 <caret>$L1
                    end
                  end
                end
              )
            )
        """)
    }

    fun testBrTableByIndex() {
        doTest($$"""
            (module
              (func $1
                (if (;TARGET;)$L1
                  (block $L2
                    (loop $L3
                      (br_table 0 1 <caret>2 (local.get 0))
                    )
                  )
                )
              )
            )
        """)
    }

    fun testBrTableByIndexUnfolded() {
        doTest($$"""
            (module
              (func $1
                if (;TARGET;)$L1
                  block $L2
                    loop $L3
                      br_table 0 1 <caret>2
                    end
                  end
                end
              )
            )
        """)
    }

    fun testBrOnNull() {
        doTest($$"""
            (module
              (func $1
                (block (;TARGET;)$L1
                  (if $L2
                    (loop $L3
                      (br_on_null <caret>$L1))
                    )
                  )
                )
              )
            )  
        """)
    }

    fun testBrOnNullUnfolded() {
        doTest($$"""
            (module
              (func $1
                block (;TARGET;)$L1
                  if $L2
                    loop $L3
                      br_on_null <caret>$L1
                    end
                  end
                end
              )
            )
        """)
    }

    fun testBrOnNullByIndex() {
        doTest($$"""
            (module
              (func $1
                (block (;TARGET;)$L1
                  (if $L2
                    (loop $L3
                      (br_on_null <caret>2)
                    )
                  )
                )
              )
            )
        """)
    }

    fun testBrOnNullByIndexUnfolded() {
        doTest($$"""
            (module
              (func $1
                block (;TARGET;)$L1
                  if $L2
                    loop $L3
                      br_on_null <caret>2
                    end
                  end
                end
              )
            )
        """)
    }

    fun testBrOnNonNull() {
        doTest($$"""
            (module
              (func $1
                (block (;TARGET;)$L1
                  (if $L2
                    (loop $L3
                      (br_on_non_null <caret>$L1)
                    )
                  )
                )
              )
            )
        """)
    }

    fun testBrOnNonNullUnfolded() {
        doTest($$"""
            (module
              (func $1
                block (;TARGET;)$L1
                  if $L2
                    loop $L3
                      br_on_non_null <caret>$L1
                    end
                  end
                end
              )
            )
        """)
    }

    fun testBrOnNonNullByIndex() {
        doTest($$"""
            (module
              (func $1
                (block (;TARGET;)$L1
                  (if $L2
                    (loop $L3
                      (br_on_non_null <caret>2)
                    )
                  )
                )
              )
            )
        """)
    }

    fun testBrOnNonNullByIndexUnfolded() {
        doTest($$"""
            (module
              (func $1
                block (;TARGET;)$L1
                  if $L2
                    loop $L3
                      br_on_non_null <caret>2
                    end
                  end
                end
              )
            )
        """)
    }

    fun testBrOnCast() {
        doTest($$"""
            (module
              (func $1
                (block (;TARGET;)$L1
                  (if $L2
                    (loop $L3
                      (br_on_cast <caret>$L1 (ref $child_struct))
                    )
                  )
                )
              )
            )
        """)
    }

    fun testBrOnCastUnfolded() {
        doTest($$"""
            (module
              (func $1
                block (;TARGET;)$L1
                  if $L2
                    loop $L3
                      br_on_cast <caret>$L1 (ref $child_struct) (ref null $child_struct)
                    end
                  end
                end
              )
            )
        """)
    }

    fun testBrOnCastByIndex() {
        doTest($$"""
            (module
              (func $1
                (block (;TARGET;)$L1
                  (if $L2
                    (loop $L3
                      (br_on_cast <caret>2 (ref $child_struct))
                    )
                  )
                )
              )
            )
        """)
    }

    fun testBrOnCastByIndexUnfolded() {
        doTest($$"""
            (module
              (func $1
                block (;TARGET;)$L1
                  if $L2
                    loop $L3
                      br_on_cast <caret>2 (ref $child_struct) anyref
                    end
                  end
                end
              )
            )
        """)
    }

    fun testBrOnCastFail() {
        doTest($$"""
            (module
              (func $1
                (block (;TARGET;)$L1
                  (if $L2
                    (loop $L3
                      (br_on_cast_fail <caret>$L1 (ref $child_struct))
                    )
                  )
                )
              )
            )
        """)
    }

    fun testBrOnCastFailUnfolded() {
        doTest($$"""
            (module
              (func $1
                block (;TARGET;)$L1
                  if $L2
                    loop $L3
                      br_on_cast_fail <caret>$L1 (ref $child_struct) anyref
                    end
                  end
                end
              )
            )
        """)
    }

    fun testBrOnCastFailByIndex() {
        doTest($$"""
            (module
              (func $1
                (block (;TARGET;)$L1
                  (if $L2
                    (loop $L3
                      (br_on_cast_fail <caret>2 (ref $child_struct))
                    )
                  )
                )
              )
            )
        """)
    }

    fun testBrOnCastFailByIndexUnfolded() {
        doTest($$"""
            (module
              (func $1
                block (;TARGET;)$L1
                  if $L2
                    loop $L3
                      br_on_cast_fail <caret>2 (ref $child_struct) anyref
                    end
                  end
                end
              )
            )
        """)
    }

    fun testTryTableLabel() {
        doTest($$"""
            (module
              (func $1
                (try_table (;TARGET;)$t
                  (br <caret>$t)
                )
              )
            )
        """)
    }

    fun testTryTableLabelUnfolded() {
        doTest($$"""
            (module
              (func $1
                try_table (;TARGET;)$t
                  br <caret>$t
                end
              )
            )
        """)
    }

    fun testTryTableLabelByIndexUnfolded() {
        doTest($$"""
            (module
              (func $1
                block $outer
                  try_table (;TARGET;)$inner
                    br <caret>0
                  end
                end
              )
            )
        """)
    }

    /**
     * The regression that motivated adding `try_table` to the parser's block map: without it the
     * `try_table` is not a label owner, so index 1 skips past the enclosing block.
     */
    fun testBrThroughTryTableByIndexUnfolded() {
        doTest($$"""
            (module
              (func $1
                block (;TARGET;)$outer
                  try_table $inner
                    br <caret>1
                  end
                  nop
                end
              )
            )
        """)
    }

    fun testTryTableCatchTag() {
        doTest($$"""
            (module
              (tag (;TARGET;)$e (param i32))
              (func $1
                (try_table $t (catch <caret>$e $t)
                  (nop)
                )
              )
            )
        """)
    }

    /** In `(catch $tag $label)` the second operand is a label, not another tag. */
    fun testTryTableCatchLabel() {
        doTest($$"""
            (module
              (tag $e (param i32))
              (func $1
                (try_table (;TARGET;)$t (catch $e <caret>$t)
                  (nop)
                )
              )
            )
        """)
    }

    fun testTryTableCatchRefLabel() {
        doTest($$"""
            (module
              (tag $e (param i32))
              (func $1
                (try_table (;TARGET;)$t (catch_ref $e <caret>$t)
                  (nop)
                )
              )
            )
        """)
    }

    fun testTryTableCatchAllLabel() {
        doTest($$"""
            (module
              (func $1
                (try_table (;TARGET;)$t (catch_all <caret>$t)
                  (nop)
                )
              )
            )
        """)
    }

    fun testTryTableCatchAllRefLabel() {
        doTest($$"""
            (module
              (func $1
                (try_table (;TARGET;)$t (catch_all_ref <caret>$t)
                  (nop)
                )
              )
            )
        """)
    }

    fun testLegacyTryLabel() {
        doTest($$"""
            (module
              (func $1
                try (;TARGET;)$t
                  nop
                catch_all
                  br <caret>$t
                end
              )
            )
        """)
    }

    fun testLegacyTryRethrow() {
        doTest($$"""
            (module
              (func $1
                try (;TARGET;)$t
                  nop
                catch_all
                  rethrow <caret>$t
                end
              )
            )
        """)
    }

    /** `delegate`'s label is resolved outside the try, so it can only name an enclosing block. */
    fun testLegacyTryDelegate() {
        doTest($$"""
            (module
              (func $1
                block (;TARGET;)$outer
                  try $inner
                    nop
                  delegate <caret>$outer
                end
              )
            )
        """)
    }

    fun testLegacyTryDelegateByIndex() {
        doTest($$"""
            (module
              (func $1
                block (;TARGET;)$outer
                  try $inner
                    nop
                  delegate <caret>0
                end
              )
            )
        """)
    }

    fun testSuspendTag() {
        doTest($$"""
            (module
              (tag (;TARGET;)$yield (param i32))
              (func $1
                suspend <caret>$yield
              )
            )
        """)
    }

    fun testContNewType() {
        doTest($$"""
            (module
              (type $ft (func))
              (type (;TARGET;)$ct (cont $ft))
              (func $1
                cont.new <caret>$ct
              )
            )
        """)
    }

    fun testContBindType() {
        doTest($$"""
            (module
              (type $ft (func))
              (type (;TARGET;)$ct (cont $ft))
              (func $1
                cont.bind <caret>$ct $ct
              )
            )
        """)
    }

    fun testResumeType() {
        doTest($$"""
            (module
              (type $ft (func))
              (type (;TARGET;)$ct (cont $ft))
              (func $1
                resume <caret>$ct
              )
            )
        """)
    }

    fun testResumeThrowType() {
        doTest($$"""
            (module
              (tag $e)
              (type $ft (func))
              (type (;TARGET;)$ct (cont $ft))
              (func $1
                resume_throw <caret>$ct $e
              )
            )
        """)
    }

    fun testResumeThrowTag() {
        doTest($$"""
            (module
              (tag (;TARGET;)$e)
              (type $ft (func))
              (type $ct (cont $ft))
              (func $1
                resume_throw $ct <caret>$e
              )
            )
        """)
    }

    fun testSwitchTag() {
        doTest($$"""
            (module
              (tag (;TARGET;)$e)
              (type $ft (func))
              (type $ct (cont $ft))
              (func $1
                switch $ct <caret>$e
              )
            )
        """)
    }

    fun testStart() {
        doTest($$"""
            (module
              (func $f0)
              (func (;TARGET;)$f1)
              (func $f2)
              (start <caret>$f1)
            )
        """)
    }

    fun testStartByIndex() {
        doTest($$"""
            (module
              (func $f0)
              (func (;TARGET;)$f1)
              (func $f2)
              (start <caret>1)
            )
        """)
    }
}
