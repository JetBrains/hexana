package org.jetbrains.hexana.ui

import com.intellij.testFramework.UsefulTestCase

/**
 * The hex search bar's status. Highlights are capped at [MAX_SEARCH_HIGHLIGHTS], but the
 * selection moves to the next match however far it is, so the selected match can be past them.
 */
class HexSearchStatusTest : UsefulTestCase() {

    fun testNoQueryAndNoMatches() {
        assertEquals("", searchStatusText(queryEmpty = true, highlighted = 0, currentIndex = -1))
        assertEquals("0 results", searchStatusText(queryEmpty = false, highlighted = 0, currentIndex = -1))
    }

    fun testSelectedMatchAmongAllMatches() {
        assertEquals("3 of 7", searchStatusText(queryEmpty = false, highlighted = 7, currentIndex = 2))
    }

    fun testCappedMatchesAreNotPresentedAsTheTotal() {
        assertEquals(
            "5 of $MAX_SEARCH_HIGHLIGHTS+",
            searchStatusText(queryEmpty = false, highlighted = MAX_SEARCH_HIGHLIGHTS, currentIndex = 4),
        )
    }

    fun testSelectedMatchPastTheCapIsNotReportedAsZero() {
        assertEquals(
            "past the first $MAX_SEARCH_HIGHLIGHTS",
            searchStatusText(queryEmpty = false, highlighted = MAX_SEARCH_HIGHLIGHTS, currentIndex = -1),
        )
    }
}
