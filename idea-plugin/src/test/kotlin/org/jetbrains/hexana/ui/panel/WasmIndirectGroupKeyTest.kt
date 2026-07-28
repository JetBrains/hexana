package org.jetbrains.hexana.ui.panel

import com.intellij.testFramework.UsefulTestCase

/**
 * Group keys for the indirect-candidates popup: display-only name parsing —
 * rows stay keyed by funcidx (a name is not a unique key).
 */
class WasmIndirectGroupKeyTest : UsefulTestCase() {

    fun testTraitImplsGroupByTraitMethod() {
        assertEquals(
            "Display::fmt",
            candidateGroupKey("<png::ChunkType as core::fmt::Display>::fmt::h1234567890abcdef"),
        )
        assertEquals(
            "Write::write_str",
            candidateGroupKey("<alloc::string::String as core::fmt::Write>::write_str::hdeadbeef00000000"),
        )
        // Generic arguments inside the impl type must not break the parse.
        assertEquals(
            "Debug::fmt",
            candidateGroupKey("<regex_automata::dfa::dense::DFA<T> as core::fmt::Debug>::fmt::h584dda0c09d5fbd8"),
        )
    }

    fun testPlainPathsGroupByLastTwoSegments() {
        assertEquals("Queue::new", candidateGroupKey("js_sys::futures::queue::Queue::new::hb55a3f22e7c4f0f5"))
        assertEquals("panicking::panic_fmt", candidateGroupKey("core::panicking::panic_fmt"))
    }

    fun testGenericInstantiationsAndDisambiguatorsCollapse() {
        // `[N]` disambiguators and per-T generic arguments group together.
        assertEquals(
            "ptr::drop_in_place",
            candidateGroupKey("core::ptr::drop_in_place<worker::error::Error>::hce20d9493f71f8e8[1]"),
        )
        assertEquals(
            "ptr::drop_in_place",
            candidateGroupKey("core::ptr::drop_in_place<regex_automata::util::pool::Pool<T>>::hf01c4d60701aaedf"),
        )
    }

    fun testUnnamedPlaceholdersGroupTogether() {
        assertEquals("(unnamed)", candidateGroupKey("\$func167"))
        assertEquals("(unnamed)", candidateGroupKey("(imported)"))
    }
}
