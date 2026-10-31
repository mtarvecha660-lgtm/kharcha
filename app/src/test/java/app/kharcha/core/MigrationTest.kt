package app.kharcha.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the exact SQL the app runs (Schema) against a real SQLite: build a v1.0.0 database with data,
 * upgrade it to v2, and check every row survived and the new tables work.
 */
class MigrationTest {
    private lateinit var db: Connection

    @Before fun open() { db = DriverManager.getConnection("jdbc:sqlite::memory:") }
    @After fun close() { db.close() }

    private fun exec(sql: String, vararg args: Any?) = db.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }; st.executeUpdate()
    }
    private fun rows(sql: String, vararg args: Any?): List<List<Any?>> = db.prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeQuery().use { rs ->
            val out = ArrayList<List<Any?>>()
            val n = rs.metaData.columnCount
            while (rs.next()) out.add((1..n).map { rs.getObject(it) })
            out
        }
    }
    private fun one(sql: String, vararg args: Any?): Any? = rows(sql, *args).first().first()

    private fun inTx(block: () -> Unit) {
        db.autoCommit = false
        try { block(); db.commit() } catch (e: Exception) { db.rollback(); throw e } finally { db.autoCommit = true }
    }

    /** A v1.0.0 database as a real phone would have it. */
    private fun v1WithData() {
        Schema.V1.forEach { exec(it) }
        val ins = "INSERT INTO txn(amount,payee,payee_key,category,note,bank,account,ref,ts,raw,source,reversed) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
        exec(ins, 125000, "ROHAN MEHTA", "rohan mehta", "People", null, "HDFC", "1234", "430412345671", 1_793_436_865_940L, "Sent Rs.1250.00 ...", "sms", 0)
        exec(ins, 15000, "SWIGGY", "swiggy", "Food", "lunch", "SBI", "1234", "430498765431", 1_793_436_869_210L, "Dear UPI user ...", "sms", 0)
        exec(ins, 9900, "RAPIDO", "rapido", null, null, "ICICI", "123", "430411112221", 1_793_436_873_750L, "ICICI Bank ...", "sms", 1)
        exec(ins, 8000, null, null, "Groceries", "", null, null, null, 1_793_436_900_000L, null, "manual", 0)
        exec(ins, 200000, "ATM cash", null, Categorizer.ATM, null, "SBI", "1234", null, 1_793_436_950_000L, "ATM WDL", "sms", 0)
        exec(ins, 49900, "STEAM", "steam", "Gaming", null, "HDFC", "1234", "1", 1_793_436_960_000L, "x", "sms", 0) // odd legacy name
        exec("INSERT INTO rule(payee_key,category) VALUES('rohan mehta','People'),('swiggy','Food'),('chai point','Chai')")
    }

    private fun snapshot(table: String) = rows("SELECT * FROM $table ORDER BY 1")

    @Test fun upgradeKeepsEveryRowAndColumn() {
        v1WithData()
        val txnBefore = snapshot("txn"); val ruleBefore = snapshot("rule")
        inTx { Schema.upgrade(1).forEach { exec(it) } }
        assertEquals(txnBefore, snapshot("txn"))
        assertEquals(ruleBefore, snapshot("rule"))
        assertEquals(6L, (one("SELECT COUNT(*) FROM txn") as Number).toLong())
    }

    @Test fun upgradeSeedsDefaultsAndKeepsLegacyNames() {
        v1WithData()
        inTx { Schema.upgrade(1).forEach { exec(it) } }
        val names = rows("SELECT name FROM category ORDER BY counts DESC, pos, id").map { it[0] as String }
        assertEquals(Schema.SEEDS.filter { it.counts }.map { it.name }, names.take(10))
        assertTrue("legacy txn category kept", "Gaming" in names)
        assertTrue("legacy rule category kept", "Chai" in names)
        assertEquals(listOf(Categorizer.ATM, Categorizer.NOT_SPENDING), rows("SELECT name FROM category WHERE counts=0 ORDER BY pos").map { it[0] })
        assertEquals(1L, (one("SELECT locked FROM category WHERE name=?", Categorizer.ATM) as Number).toLong())
        assertEquals("🍔", one("SELECT emoji FROM category WHERE name='Food'"))
        assertEquals(14, names.size)
        assertEquals(0L, (one("SELECT COUNT(*) FROM split") as Number).toLong())
    }

    @Test fun freshInstallMatchesUpgradedSchema() {
        Schema.create().forEach { exec(it) }
        val fresh = rows("SELECT type,name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name")
        close(); open()
        Schema.V1.forEach { exec(it) }
        Schema.upgrade(1).forEach { exec(it) }
        assertEquals(fresh, rows("SELECT type,name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name"))
        assertEquals(12L, (one("SELECT COUNT(*) FROM category") as Number).toLong())
    }

    @Test fun upgradeFromCurrentVersionIsNoOp() {
        assertTrue(Schema.upgrade(Schema.VERSION).isEmpty())
    }

    @Test fun splitAndRenameAndDeleteAfterUpgrade() {
        v1WithData()
        inTx { Schema.upgrade(1).forEach { exec(it) } }
        // Rohan ₹1,250 = Food 600 + Travel 400 + Movie 250
        exec("INSERT INTO category(name,emoji,color,pos,counts,locked,seed) VALUES('Movie','🍿',-1,11,1,0,NULL)")
        val rohanId = one("SELECT id FROM txn WHERE payee='ROHAN MEHTA'")
        listOf("Food" to 60000, "Travel" to 40000, "Movie" to 25000).forEachIndexed { i, (c, a) ->
            exec("INSERT INTO split(txn_id,amount,category,note,pos) VALUES(?,?,?,?,?)", rohanId, a, c, null, i)
        }
        assertEquals(125000L, (one("SELECT SUM(amount) FROM split WHERE txn_id=?", rohanId) as Number).toLong())

        // Rename Food → Khana: payments, split parts, rules and the seed link all follow.
        inTx { Schema.RENAME.forEach { exec(it, "Khana", "Food") } }
        assertEquals(0L, (one("SELECT COUNT(*) FROM txn WHERE category='Food'") as Number).toLong())
        assertEquals("Khana", one("SELECT category FROM txn WHERE payee='SWIGGY'"))
        assertEquals("Khana", one("SELECT category FROM split WHERE amount=60000"))
        assertEquals("Khana", one("SELECT category FROM rule WHERE payee_key='swiggy'"))
        assertEquals("Khana", one("SELECT name FROM category WHERE seed='Food'"))

        // Delete Movie (in use by a split part) → moved to Other.
        inTx { Schema.DELETE_MOVE.forEach { exec(it, "Other", "Movie") } }
        assertEquals(0L, (one("SELECT COUNT(*) FROM category WHERE name='Movie'") as Number).toLong())
        assertEquals("Other", one("SELECT category FROM split WHERE amount=25000"))
        // Nothing lost.
        assertEquals(6L, (one("SELECT COUNT(*) FROM txn") as Number).toLong())
        assertEquals(3L, (one("SELECT COUNT(*) FROM split") as Number).toLong())
    }

    @Test fun categoryNamesAreCaseInsensitivelyUnique() {
        Schema.create().forEach { exec(it) }
        try {
            exec("INSERT INTO category(name,emoji,color,pos) VALUES('food','x',0,99)")
            fail("duplicate allowed")
        } catch (e: java.sql.SQLException) { /* expected */ }
    }
}
