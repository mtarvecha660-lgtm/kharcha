package app.kharcha.core

import org.junit.Assert.*
import org.junit.Test

class SplitMathTest {
    private val rohan = 125_000L // ₹1,250 in paise
    private val excluded = Categorizer.EXCLUDED

    @Test fun rohanExampleIsValid() {
        val parts = listOf(Part("Food", 60_000), Part("Travel", 40_000), Part("Movie", 25_000))
        assertEquals(0L, SplitMath.remaining(rohan, parts))
        assertNull(SplitMath.problem(rohan, parts))
        assertTrue(SplitMath.isValid(rohan, parts))
    }

    @Test fun remainingIsLive() {
        assertEquals(125_000L, SplitMath.remaining(rohan, emptyList()))
        assertEquals(65_000L, SplitMath.remaining(rohan, listOf(Part("Food", 60_000))))
        assertEquals(-5_000L, SplitMath.remaining(rohan, listOf(Part("Food", 130_000))))
    }

    @Test fun mustSumExactly() {
        assertEquals("₹250 still left to assign", SplitMath.problem(rohan, listOf(Part("Food", 60_000), Part("Travel", 40_000))))
        assertEquals("Parts are ₹0.50 more than the payment",
            SplitMath.problem(rohan, listOf(Part("Food", 60_050), Part("Travel", 40_000), Part("Movie", 25_000))))
    }

    @Test fun needsAtLeastTwoParts() {
        assertEquals("Add at least 2 parts", SplitMath.problem(rohan, listOf(Part("Food", rohan))))
        // Blank rows don't count as parts.
        assertEquals("Add at least 2 parts", SplitMath.problem(rohan, listOf(Part("Food", rohan), Part(null, 0))))
    }

    @Test fun everyPartNeedsCategoryAndPositiveAmount() {
        assertEquals("Pick a category for every part", SplitMath.problem(rohan, listOf(Part("Food", 100_000), Part(null, 25_000))))
        assertEquals("Every part needs an amount", SplitMath.problem(rohan, listOf(Part("Food", rohan), Part("Travel", 0))))
        assertEquals("Every part needs an amount", SplitMath.problem(rohan, listOf(Part("Food", 130_000), Part("Travel", -5_000))))
    }

    @Test fun putRestFillsBlankRow() {
        val parts = listOf(Part("Food", 60_000), Part("Travel", 40_000), Part(null, 0))
        val out = SplitMath.putRest(rohan, parts, "Movie")
        assertEquals(listOf(Part("Food", 60_000), Part("Travel", 40_000), Part("Movie", 25_000)), out)
        assertTrue(SplitMath.isValid(rohan, out))
    }

    @Test fun putRestAddsRowWhenNoBlank() {
        val out = SplitMath.putRest(rohan, listOf(Part("Food", 60_000), Part("Travel", 40_000)), "Movie")
        assertEquals(3, out.size)
        assertEquals(Part("Movie", 25_000), out[2])
    }

    @Test fun putRestGrowsSameCategory() {
        val out = SplitMath.putRest(rohan, listOf(Part("Food", 60_000), Part("Travel", 40_000)), "Food")
        assertEquals(listOf(Part("Food", 85_000), Part("Travel", 40_000)), out)
    }

    @Test fun putRestNoOpWhenNothingLeftOrOver() {
        val full = listOf(Part("Food", 100_000), Part("Travel", 25_000))
        assertSame(full, SplitMath.putRest(rohan, full, "Movie"))
        val over = listOf(Part("Food", 130_000), Part("Travel", 1))
        assertSame(over, SplitMath.putRest(rohan, over, "Movie"))
    }

    @Test fun splitPartsCountInsteadOfParent() {
        val parts = listOf(Part("Food", 60_000), Part("Travel", 40_000), Part("Movie", 25_000))
        val items = SplitMath.items("People", rohan, reversed = false, parts = parts, excluded = excluded)
        assertEquals(listOf(Item("Food", 60_000), Item("Travel", 40_000), Item("Movie", 25_000)), items)
        assertTrue(items.none { it.category == "People" })
        val totals = SplitMath.byCategory(items + Item("Food", 15_000))
        assertEquals("Food" to 75_000L, totals[0])
        assertEquals(totals.sumOf { it.second }, 140_000L)
    }

    @Test fun unsplitCountsParentAndExcludedPartsDontCount() {
        assertEquals(listOf(Item("People", rohan)), SplitMath.items("People", rohan, false, emptyList(), excluded))
        val withNotSpending = listOf(Part("Food", 100_000), Part(Categorizer.NOT_SPENDING, 25_000))
        assertEquals(listOf(Item("Food", 100_000)), SplitMath.items(null, rohan, false, withNotSpending, excluded))
        assertTrue(SplitMath.items("People", rohan, reversed = true, parts = withNotSpending, excluded = excluded).isEmpty())
        assertTrue(SplitMath.items(Categorizer.ATM, 50_000, false, emptyList(), excluded).isEmpty())
    }

    @Test fun cleanedDropsBlankRowsAndTrimsNotes() {
        val out = SplitMath.cleaned(listOf(Part("Food", 60_000, "  dinner "), Part(null, 0, ""), Part("Travel", 65_000, "  ")))
        assertEquals(listOf(Part("Food", 60_000, "dinner"), Part("Travel", 65_000, null)), out)
    }

    @Test fun parsesRupees() {
        assertEquals(125_000L, SplitMath.parsePaise("1,250"))
        assertEquals(25_050L, SplitMath.parsePaise("₹250.5"))
        assertEquals(1L, SplitMath.parsePaise("0.01"))
        assertNull(SplitMath.parsePaise(""))
        assertNull(SplitMath.parsePaise("abc"))
        assertNull(SplitMath.parsePaise("-5"))
        assertNull(SplitMath.parsePaise("1.234"))
        assertEquals("1250", SplitMath.rupeeText(125_000))
        assertEquals("250.50", SplitMath.rupeeText(25_050))
    }
}
