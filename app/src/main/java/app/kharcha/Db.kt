package app.kharcha

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.kharcha.core.Categorizer
import app.kharcha.core.Kind
import app.kharcha.core.Parsed
import app.kharcha.core.SmsParser

data class Txn(
    val id: Long, val amountPaise: Long, val payee: String?, val payeeKey: String?, val category: String?,
    val note: String?, val bank: String?, val account: String?, val ref: String?, val ts: Long,
    val raw: String?, val source: String, val reversed: Boolean,
) {
    val title: String get() = payee ?: note?.takeIf { it.isNotBlank() } ?: if (source == "manual") "Cash" else (bank?.let { "$it payment" } ?: "Payment")
    val counts: Boolean get() = !reversed && category !in Categorizer.EXCLUDED
}

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "kharcha.db", null, 1) {
    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE txn(id INTEGER PRIMARY KEY AUTOINCREMENT, amount INTEGER NOT NULL, payee TEXT,
            payee_key TEXT, category TEXT, note TEXT, bank TEXT, account TEXT, ref TEXT, ts INTEGER NOT NULL,
            raw TEXT, source TEXT NOT NULL, reversed INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX txn_ts ON txn(ts)")
        db.execSQL("CREATE INDEX txn_ref ON txn(ref)")
        db.execSQL("CREATE TABLE rule(payee_key TEXT PRIMARY KEY, category TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

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
        val cat = if (p.isAtm) Categorizer.ATM else (ruleFor(p.payeeKey) ?: Categorizer.guess(p.payee))
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
    fun delete(id: Long) = writableDatabase.execSQL("DELETE FROM txn WHERE id=?", arrayOf(id))

    fun between(from: Long, to: Long): List<Txn> = query("ts>=? AND ts<?", arrayOf(from.toString(), to.toString()))
    fun untaggedSince(from: Long): List<Txn> = query("category IS NULL AND reversed=0 AND ts>=?", arrayOf(from.toString()))
    fun all(): List<Txn> = query("1=1", arrayOf(), "ts ASC")
    fun get(id: Long): Txn? = query("id=?", arrayOf(id.toString())).firstOrNull()
    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM txn", null).use { it.moveToFirst(); it.getInt(0) }
}
