package app.kharcha.core

import java.math.BigDecimal

/** One row of a split: category + amount (paise) + optional note. Pure data so the math is unit-testable. */
data class Part(val category: String?, val amountPaise: Long, val note: String? = null)

/** Something that counts toward a category: a whole transaction, or one part of a split one. */
data class Item(val category: String?, val amountPaise: Long)

object SplitMath {
    const val MIN_PARTS = 2

    /** Total minus what the rows already hold. Negative means the rows add up to more than the payment. */
    fun remaining(totalPaise: Long, parts: List<Part>): Long = totalPaise - parts.sumOf { it.amountPaise }

    /** Why this split can't be saved yet, or null when it's valid. */
    fun problem(totalPaise: Long, parts: List<Part>): String? {
        val filled = parts.filter { it.amountPaise != 0L || it.category != null }
        if (filled.size < MIN_PARTS) return "Add at least $MIN_PARTS parts"
        if (filled.any { it.amountPaise <= 0L }) return "Every part needs an amount"
        if (filled.any { it.category == null }) return "Pick a category for every part"
        val rem = remaining(totalPaise, filled)
        if (rem > 0) return "₹" + rupeeText(rem) + " still left to assign"
        if (rem < 0) return "Parts are ₹" + rupeeText(-rem) + " more than the payment"
        return null
    }

    fun isValid(totalPaise: Long, parts: List<Part>) = problem(totalPaise, parts) == null

    /** Drop untouched blank rows before saving. */
    fun cleaned(parts: List<Part>): List<Part> =
        parts.filter { it.amountPaise != 0L || it.category != null }.map { it.copy(note = it.note?.trim()?.takeIf { n -> n.isNotEmpty() }) }

    /**
     * "Put rest in…": the remaining amount goes to [category]. An existing empty row (no amount yet) is filled first,
     * else a row already in that category grows, else a new row is added. Nothing changes when nothing remains.
     */
    fun putRest(totalPaise: Long, parts: List<Part>, category: String): List<Part> {
        val rem = remaining(totalPaise, parts)
        if (rem <= 0) return parts
        val out = parts.toMutableList()
        val sameCat = out.indexOfFirst { it.category == category && it.amountPaise > 0 }
        val blank = out.indexOfFirst { it.amountPaise == 0L && (it.category == null || it.category == category) }
        when {
            blank >= 0 -> out[blank] = out[blank].copy(category = category, amountPaise = rem)
            sameCat >= 0 -> out[sameCat] = out[sameCat].copy(amountPaise = out[sameCat].amountPaise + rem)
            else -> out.add(Part(category, rem))
        }
        return out
    }

    /**
     * What a transaction contributes to category totals: its parts when it is split, else itself.
     * Reversed (refunded) payments contribute nothing; parts in excluded categories (ATM cash, Not spending) don't count.
     */
    fun items(category: String?, amountPaise: Long, reversed: Boolean, parts: List<Part>, excluded: Set<String>): List<Item> {
        if (reversed) return emptyList()
        val raw = if (parts.isEmpty()) listOf(Item(category, amountPaise)) else parts.map { Item(it.category, it.amountPaise) }
        return raw.filter { it.category !in excluded }
    }

    /** Category → total, biggest first. Untagged items are grouped under null. */
    fun byCategory(items: List<Item>): List<Pair<String?, Long>> =
        items.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amountPaise } }.entries
            .sortedByDescending { it.value }.map { it.key to it.value }

    /** "1,250.50" style text → paise. Null for blank / invalid / negative. */
    fun parsePaise(s: String?): Long? {
        val t = s?.replace(",", "")?.replace("₹", "")?.trim() ?: return null
        if (t.isEmpty()) return null
        val v = t.toBigDecimalOrNull() ?: return null
        if (v.signum() < 0 || v.scale() > 2) return null
        return v.movePointRight(2).toLong()
    }

    fun rupeeText(paise: Long): String {
        val bd = BigDecimal.valueOf(paise).movePointLeft(2).stripTrailingZeros()
        return if (bd.scale() <= 0) bd.setScale(0).toPlainString() else bd.setScale(2).toPlainString()
    }
}
