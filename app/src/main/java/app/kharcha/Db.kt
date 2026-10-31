package app.kharcha

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.kharcha.core.Categorizer
import app.kharcha.core.Item
import app.kharcha.core.Kind
import app.kharcha.core.Parsed
import app.kharcha.core.Part
import app.kharcha.core.Schema
import app.kharcha.core.SmsParser
import app.kharcha.core.SplitMath

data class Txn(
    val id: Long, val amountPaise: Long, val payee: String?, val payeeKey: String?, val category: String?,
    val note: String?, val bank: String?, val account: String?, val ref: String?, val ts: Long,
    val raw: String?, val source: String, val reversed: Boolean,
) {
    val title: String get() = payee ?: note?.takeIf { it.isNotBlank() } ?: if (source == "manual") "Cash" else (bank?.let { "$it payment" } ?: "Payment")
    val counts: Boolean get() = !reversed && category !in Categorizer.EXCLUDED
}

/** One saved part of a split transaction. */
data class SplitPart(val id: Long, val txnId: Long, val amountPaise: Long, val category: String?, val note: String?, val pos: Int) {
    fun toPart() = Part(category, amountPaise, note)
}

/** A user-editable category. [locked] = built-in non-spending bucket (ATM cash / Not spending): no rename or delete. */
data class Category(val id: Long, val name: String, val emoji: String?, val color: Int, val pos: Int,
                    val counts: Boolean, val locked: Boolean, val seed: String?) {
    val label: String get() = (emoji?.takeIf { it.isNotBlank() }?.let { "$it " } ?: "") + name
}

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "kharcha.db", null, Schema.VERSION) {
    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (sql in Schema.create()) db.execSQL(sql)
    }
    /** Additive migrations only (see Schema). SQLiteOpenHelper runs this inside a transaction: all or nothing. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (sql in Schema.upgrade(oldVersion)) db.execSQL(sql)
    }
    // No onDowngrade override: the framework refuses a downgrade instead of wiping data.

    private fun row(c: Cursor) = Txn(
        c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6),
        c.getString(7), c.getString(8), c.getLong(9), c.getString(10), c.getString(11), c.getInt(12) != 0,
    )
    private val COLS = "id,amount,payee,payee_key,category,note,bank,account,ref,ts,raw,source,reversed"

    private fun query(where: String, args: Array<String>, order: String = "ts DESC", limit: String? = null): List<Txn> {
        val out = ArrayList<Txn>()
        readableDatabase.rawQuery("SELECT $COLS FROM txn WHERE $where ORDER BY $order" + (limit?.let { " LIMIT $it" } ?: ""), args).use {
            while (it.moveToNext()) out.add(row(it))
        }
        return out
    }

    fun ruleFor(key: String?): String? {
        if (key == null) return null
        readableDatabase.rawQuery("SELECT category FROM rule WHERE payee_key=?", arrayOf(key)).use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    /** Parse + store one SMS. Returns the new txn id, or null if it was not a debit / was a duplicate. */
    @Synchronized
    fun ingestSms(sender: String, body: String, ts: Long): Long? {
        val p = SmsParser.parse(sender, body) ?: return null
        return if (p.kind == Kind.REVERSAL) { markReversal(p, ts); null } else insertDebit(p, body, ts)
    }

    private fun isDuplicate(p: Parsed, raw: String, ts: Long): Boolean {
        val db = readableDatabase
        if (p.ref != null) {
            db.rawQuery("SELECT 1 FROM txn WHERE ref=? AND amount=? LIMIT 1", arrayOf(p.ref, p.amountPaise.toString())).use { if (it.moveToFirst()) return true }
        }
        db.rawQuery("SELECT 1 FROM txn WHERE raw=? AND ABS(ts-?)<600000 LIMIT 1", arrayOf(raw, ts.toString())).use { if (it.moveToFirst()) return true }
        return false
    }

    private fun insertDebit(p: Parsed, raw: String, ts: Long): Long? {
        if (isDuplicate(p, raw, ts)) return null
        val cat = if (p.isAtm) Categorizer.ATM else (ruleFor(p.payeeKey)?.takeIf { categoryExists(it) } ?: currentNameForSeed(Categorizer.guess(p.payee)))
        val cv = ContentValues().apply {
            put("amount", p.amountPaise); put("payee", p.payee); put("payee_key", p.payeeKey); put("category", cat)
            put("bank", p.bank); put("account", p.account); put("ref", p.ref); put("ts", ts); put("raw", raw); put("source", "sms")
        }
        return writableDatabase.insert("txn", null, cv).takeIf { it > 0 }
    }

    private fun markReversal(p: Parsed, ts: Long) {
        val db = writableDatabase
        var id: Long? = null
        if (p.ref != null) db.rawQuery("SELECT id FROM txn WHERE ref=? AND reversed=0 ORDER BY ts DESC LIMIT 1", arrayOf(p.ref)).use { if (it.moveToFirst()) id = it.getLong(0) }
        if (id == null) db.rawQuery(
            "SELECT id FROM txn WHERE amount=? AND reversed=0 AND source='sms' AND ts BETWEEN ? AND ? ORDER BY ts DESC LIMIT 1",
            arrayOf(p.amountPaise.toString(), (ts - 5L * 86400000).toString(), ts.toString()),
        ).use { if (it.moveToFirst()) id = it.getLong(0) }
        id?.let { db.execSQL("UPDATE txn SET reversed=1 WHERE id=?", arrayOf(it)) }
    }

    fun addManual(amountPaise: Long, category: String?, note: String?, ts: Long = System.currentTimeMillis()): Long {
        val cv = ContentValues().apply {
            put("amount", amountPaise); put("category", category); put("note", note?.takeIf { it.isNotBlank() })
            put("ts", ts); put("source", "manual")
        }
        return writableDatabase.insert("txn", null, cv)
    }

    /** Tag a txn. Learns the payee → category rule and applies it to that payee's other untagged txns. */
    fun setCategory(t: Txn, category: String) {
        val db = writableDatabase
        db.execSQL("UPDATE txn SET category=? WHERE id=?", arrayOf(category, t.id))
        val key = t.payeeKey
        if (key != null && t.source == "sms" && category != Categorizer.ATM) {
            db.execSQL("INSERT OR REPLACE INTO rule(payee_key,category) VALUES(?,?)", arrayOf(key, category))
            db.execSQL("UPDATE txn SET category=? WHERE payee_key=? AND category IS NULL", arrayOf(category, key))
        }
    }

    fun setNote(id: Long, note: String?) = writableDatabase.execSQL("UPDATE txn SET note=? WHERE id=?", arrayOf(note?.takeIf { it.isNotBlank() }, id))
    fun setReversed(id: Long, rev: Boolean) = writableDatabase.execSQL("UPDATE txn SET reversed=? WHERE id=?", arrayOf(if (rev) 1 else 0, id))
    fun delete(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM split WHERE txn_id=?", arrayOf(id))
            db.execSQL("DELETE FROM txn WHERE id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun between(from: Long, to: Long): List<Txn> = query("ts>=? AND ts<?", arrayOf(from.toString(), to.toString()))
    /** Untagged = no category and not split (a split payment is tagged through its parts). */
    fun untaggedSince(from: Long): List<Txn> =
        query("category IS NULL AND reversed=0 AND ts>=? AND id NOT IN (SELECT txn_id FROM split)", arrayOf(from.toString()))

    // ---------- splits ----------
    fun splitsFor(ids: Collection<Long>): Map<Long, List<SplitPart>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, MutableList<SplitPart>>()
        for (chunk in ids.chunked(500)) {
            readableDatabase.rawQuery(
                "SELECT id,txn_id,amount,category,note,pos FROM split WHERE txn_id IN (${chunk.joinToString(",")}) ORDER BY txn_id, pos, id", null,
            ).use { c ->
                while (c.moveToNext()) out.getOrPut(c.getLong(1)) { ArrayList() }
                    .add(SplitPart(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getInt(5)))
            }
        }
        return out
    }
    fun splitsOf(txnId: Long): List<SplitPart> = splitsFor(listOf(txnId))[txnId] ?: emptyList()

    /** Replace the split of [t] with [parts]. Refuses (returns the reason) unless the parts are valid and sum to the total. */
    fun saveSplit(t: Txn, parts: List<Part>): String? {
        val clean = SplitMath.cleaned(parts)
        SplitMath.problem(t.amountPaise, clean)?.let { return it }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM split WHERE txn_id=?", arrayOf(t.id))
            clean.forEachIndexed { i, p ->
                db.execSQL("INSERT INTO split(txn_id,amount,category,note,pos) VALUES(?,?,?,?,?)", arrayOf(t.id, p.amountPaise, p.category, p.note, i))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return null
    }

    fun unsplit(txnId: Long) = writableDatabase.execSQL("DELETE FROM split WHERE txn_id=?", arrayOf(txnId))
    fun setPartCategory(partId: Long, category: String) = writableDatabase.execSQL("UPDATE split SET category=? WHERE id=?", arrayOf(category, partId))

    /** Everything in [txns] that counts as spending, with split payments broken into their parts. */
    fun items(txns: List<Txn>): List<Item> {
        val parts = splitsFor(txns.map { it.id })
        val excluded = excludedNames()
        return txns.flatMap { t -> SplitMath.items(t.category, t.amountPaise, t.reversed, parts[t.id]?.map { it.toPart() } ?: emptyList(), excluded) }
    }
    fun spent(txns: List<Txn>): Long = items(txns).sumOf { it.amountPaise }

    // ---------- categories ----------
    fun categories(): List<Category> {
        val out = ArrayList<Category>()
        readableDatabase.rawQuery("SELECT id,name,emoji,color,pos,counts,locked,seed FROM category ORDER BY counts DESC, pos, id", null).use { c ->
            while (c.moveToNext()) out.add(Category(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4),
                c.getInt(5) != 0, c.getInt(6) != 0, c.getString(7)))
        }
        return out
    }
    fun excludedNames(): Set<String> = Categorizer.EXCLUDED + categories().filter { !it.counts }.map { it.name }
    fun categoryExists(name: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM category WHERE name=?", arrayOf(name)).use { it.moveToFirst() }
    /** The auto-categoriser speaks in built-in names ("Food"); map that to whatever the user renamed it to. */
    fun currentNameForSeed(seed: String?): String? {
        if (seed == null) return null
        readableDatabase.rawQuery("SELECT name FROM category WHERE seed=? LIMIT 1", arrayOf(seed)).use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }
    /** How many payments and split parts use [name]. */
    fun usage(name: String): Int {
        var n = 0
        readableDatabase.rawQuery("SELECT (SELECT COUNT(*) FROM txn WHERE category=?1) + (SELECT COUNT(*) FROM split WHERE category=?1)", arrayOf(name)).use {
            if (it.moveToFirst()) n = it.getInt(0)
        }
        return n
    }

    /** Null on success, else a reason to show. */
    fun addCategory(name: String, emoji: String?, color: Int): String? {
        val n = name.trim()
        nameProblem(n, null)?.let { return it }
        val pos = readableDatabase.rawQuery("SELECT COALESCE(MAX(pos),0)+1 FROM category WHERE counts=1", null).use { it.moveToFirst(); it.getInt(0) }
        writableDatabase.execSQL("INSERT INTO category(name,emoji,color,pos,counts,locked,seed) VALUES(?,?,?,?,1,0,NULL)",
            arrayOf(n, emoji?.trim()?.takeIf { it.isNotEmpty() }, color, pos))
        CatStore.reload(this)
        return null
    }

    private fun nameProblem(n: String, except: Category?): String? {
        if (n.isEmpty()) return "Name can't be empty"
        if (n.length > 24) return "Keep it under 24 letters"
        if (n.equals("Untagged", true)) return "\"Untagged\" is reserved"
        val clash = categories().firstOrNull { it.name.equals(n, true) && it.id != except?.id }
        if (clash != null) return "\"${clash.name}\" already exists"
        return null
    }

    /** Rename / re-emoji / recolour. Renaming moves every payment, split part and payee rule with it. */
    fun updateCategory(c: Category, name: String, emoji: String?, color: Int): String? {
        val n = name.trim()
        if (c.locked && n != c.name) return "Built-in category can't be renamed"
        nameProblem(n, c)?.let { return it }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (n != c.name) for (sql in Schema.RENAME) db.execSQL(sql, arrayOf(n, c.name))
            db.execSQL("UPDATE category SET emoji=?, color=? WHERE id=?", arrayOf(emoji?.trim()?.takeIf { it.isNotEmpty() }, color, c.id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        CatStore.reload(this)
        return null
    }

    /** Delete [c], moving its payments, split parts and payee rules to [moveTo]. */
    fun deleteCategory(c: Category, moveTo: String): String? {
        if (c.locked) return "Built-in category can't be deleted"
        if (moveTo == c.name) return "Pick a different category"
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (sql in Schema.DELETE_MOVE) db.execSQL(sql, arrayOf(moveTo, c.name))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        CatStore.reload(this)
        return null
    }

    /** Save a new order for the spending categories (ids in display order). */
    fun reorder(ids: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEachIndexed { i, id -> db.execSQL("UPDATE category SET pos=? WHERE id=?", arrayOf(i, id)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        CatStore.reload(this)
    }
    fun all(): List<Txn> = query("1=1", arrayOf(), "ts ASC")
    fun get(id: Long): Txn? = query("id=?", arrayOf(id.toString())).firstOrNull()
    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM txn", null).use { it.moveToFirst(); it.getInt(0) }
}
