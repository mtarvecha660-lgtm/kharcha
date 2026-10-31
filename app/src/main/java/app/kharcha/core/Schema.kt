package app.kharcha.core

/**
 * Database schema and migrations as plain SQL, so the exact statements the app runs can be unit-tested
 * against a real SQLite on the JVM. Version 1 = Kharcha 1.0.0. Version 2 = 1.1.0 (editable categories + splits).
 * Migrations only ever ADD tables; no table is dropped or rebuilt, so existing data cannot be lost.
 */
object Schema {
    const val VERSION = 2

    val V1 = listOf(
        """CREATE TABLE txn(id INTEGER PRIMARY KEY AUTOINCREMENT, amount INTEGER NOT NULL, payee TEXT,
            payee_key TEXT, category TEXT, note TEXT, bank TEXT, account TEXT, ref TEXT, ts INTEGER NOT NULL,
            raw TEXT, source TEXT NOT NULL, reversed INTEGER NOT NULL DEFAULT 0)""",
        "CREATE INDEX txn_ts ON txn(ts)",
        "CREATE INDEX txn_ref ON txn(ref)",
        "CREATE TABLE rule(payee_key TEXT PRIMARY KEY, category TEXT NOT NULL)",
    )

    /** A default category: name, emoji, colour (ARGB), and whether it counts as spending. */
    data class Seed(val name: String, val emoji: String, val color: Int, val counts: Boolean = true)

    val SEEDS = listOf(
        Seed("Food", "🍔", 0xFFE07A4F.toInt()), Seed("Groceries", "🛒", 0xFF8DA65A.toInt()),
        Seed("Travel", "🛺", 0xFF4F86A8.toInt()), Seed("Shopping", "🛍️", 0xFFB5658F.toInt()),
        Seed("Bills", "💡", 0xFFD9A441.toInt()), Seed("Fun", "🎬", 0xFF8C6BB1.toInt()),
        Seed("Health", "💊", 0xFF5AA39A.toInt()), Seed("Study", "📚", 0xFF6B7FB8.toInt()),
        Seed("People", "👥", 0xFFB98B5E.toInt()), Seed("Other", "📦", 0xFF9A9087.toInt()),
        Seed(Categorizer.ATM, "🏧", 0xFF7D8C99.toInt(), counts = false),
        Seed(Categorizer.NOT_SPENDING, "↩️", 0xFFA9A29B.toInt(), counts = false),
    )

    private fun q(s: String) = "'" + s.replace("'", "''") + "'"

    /** v1 → v2. Additive only. Categories already used by old rows but not in the defaults are kept as custom ones. */
    val MIGRATE_1_2: List<String> = listOf(
        // seed = the built-in name this row started as, so the auto-categoriser keeps working after a rename.
        // locked = built-in "not spending" buckets (ATM cash, Not spending) that can't be renamed or deleted.
        """CREATE TABLE category(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            emoji TEXT, color INTEGER NOT NULL, pos INTEGER NOT NULL, counts INTEGER NOT NULL DEFAULT 1,
            locked INTEGER NOT NULL DEFAULT 0, seed TEXT)""",
        """CREATE TABLE split(id INTEGER PRIMARY KEY AUTOINCREMENT, txn_id INTEGER NOT NULL, amount INTEGER NOT NULL,
            category TEXT, note TEXT, pos INTEGER NOT NULL DEFAULT 0)""",
        "CREATE INDEX split_txn ON split(txn_id)",
    ) + SEEDS.mapIndexed { i, s ->
        val pos = if (s.counts) i else 100 + i
        "INSERT OR IGNORE INTO category(name,emoji,color,pos,counts,locked,seed) VALUES(" +
            "${q(s.name)},${q(s.emoji)},${s.color},$pos,${if (s.counts) 1 else 0},${if (s.counts) 0 else 1},${q(s.name)})"
    } + listOf(
        """INSERT OR IGNORE INTO category(name,emoji,color,pos,counts,locked,seed)
            SELECT c, '🏷️', ${0xFF9A9087.toInt()}, 50, 1, 0, NULL FROM
            (SELECT DISTINCT category AS c FROM txn WHERE category IS NOT NULL AND TRIM(category) <> ''
             UNION SELECT DISTINCT category FROM rule WHERE TRIM(category) <> '')""",
    )

    /** Statements to bring a database at [from] up to [VERSION]. */
    fun upgrade(from: Int): List<String> {
        val out = ArrayList<String>()
        if (from < 2) out += MIGRATE_1_2
        return out
    }

    /** Fresh install: the v1 tables plus every migration, so new and upgraded phones end up identical. */
    fun create(): List<String> = V1 + upgrade(1)

    // ---- category edits (all name-keyed rows move together) ----
    /** Args: new name, old name — run in one transaction. */
    val RENAME = listOf(
        "UPDATE category SET name=?1 WHERE name=?2",
        "UPDATE txn SET category=?1 WHERE category=?2",
        "UPDATE split SET category=?1 WHERE category=?2",
        "UPDATE rule SET category=?1 WHERE category=?2",
    )
    /** Args: target, deleted name. Moves everything to [target] then removes the category. */
    val DELETE_MOVE = listOf(
        "UPDATE txn SET category=?1 WHERE category=?2",
        "UPDATE split SET category=?1 WHERE category=?2",
        "UPDATE rule SET category=?1 WHERE category=?2",
        "DELETE FROM category WHERE name=?2",
    )
}
