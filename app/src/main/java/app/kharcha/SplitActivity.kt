package app.kharcha

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.kharcha.core.Part
import app.kharcha.core.SplitMath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Split one payment into several categories, e.g. ₹1,250 to Rohan = Food 600 + Travel 400 + Movie 250.
 * Rows must add up to the payment; "Put rest in…" fills the remainder in one tap.
 */
class SplitActivity : Activity() {
    companion object { const val EXTRA_TXN = "txn" }

    private class Row(var category: String?, var amount: String, var note: String)

    private lateinit var db: Db
    private lateinit var txn: Txn
    private val rows = ArrayList<Row>()
    private var wasSplit = false
    private lateinit var list: LinearLayout
    private lateinit var remainingText: TextView
    private lateinit var remainingCard: LinearLayout
    private lateinit var restChip: TextView
    private lateinit var saveBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.get(this)
        CatStore.reload(db)
        txn = db.get(intent.getLongExtra(EXTRA_TXN, -1)) ?: run { finish(); return }
        val existing = db.splitsOf(txn.id)
        wasSplit = existing.isNotEmpty()
        if (wasSplit) existing.forEach { rows.add(Row(it.category, SplitMath.rupeeText(it.amountPaise), it.note ?: "")) }
        else { rows.add(Row(null, "", "")); rows.add(Row(null, "", "")) }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        root.addView(label(if (wasSplit) "Edit split" else "Split payment", 26f, C.INK, true).apply { setPadding(dp(20), dp(24), dp(20), dp(2)) })
        root.addView(label(rupees(txn.amountPaise) + " · " + txn.title + " · " +
            SimpleDateFormat("EEE d MMM", Locale("en", "IN")).format(Date(txn.ts)), 14f, C.MUTED).apply { setPadding(dp(20), 0, dp(20), dp(6)) })

        remainingCard = card().apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        remainingText = label("", 18f, C.INK, true)
        remainingCard.addView(remainingText, lp(0, WRAP, 1f))
        restChip = chip("Put rest in…") { putRest() }
        remainingCard.addView(restChip)
        root.addView(remainingCard)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(12)) }
        val scrollBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollBody.addView(list)
        scrollBody.addView(button("+ Add part", primary = false) { syncFromViews(); rows.add(Row(null, "", "")); renderRows(focusLast = true) },
            lp(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4) })
        scrollBody.addView(label("Each part counts toward its own category in totals and charts. You can edit or undo this later.",
            12f, C.MUTED).apply { setPadding(dp(24), dp(14), dp(24), dp(8)); gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(ScrollView(this).apply { addView(scrollBody) }, lp(MATCH, 0, 1f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        if (wasSplit) bar.addView(button("Undo split", primary = false) { confirmUnsplit() }, lp(0, WRAP, 1f).apply { rightMargin = dp(8) })
        else bar.addView(button("Cancel", primary = false) { finish() }, lp(0, WRAP, 1f).apply { rightMargin = dp(8) })
        saveBtn = button("Save split") { save() }
        bar.addView(saveBtn, lp(0, WRAP, 1f))
        root.addView(bar)
        setContentView(root)
        renderRows()
    }

    override fun onResume() {
        super.onResume()
        // Back from the Categories screen: names may have changed.
        if (::list.isInitialized) {
            syncFromViews()
            CatStore.reload(db)
            val names = CatStore.names().toSet()
            rows.forEach { if (it.category != null && it.category !in names) it.category = null }
            renderRows()
        }
    }

    private fun parts(): List<Part> = rows.map { Part(it.category, SplitMath.parsePaise(it.amount) ?: 0L, it.note) }

    private fun renderRows(focusLast: Boolean = false) {
        list.removeAllViews()
        rows.forEachIndexed { i, row -> list.addView(rowView(i, row)) }
        refreshTotals()
        if (focusLast) list.post {
            (list.getChildAt(list.childCount - 1)?.findViewWithTag<EditText>("amount"))?.requestFocus()
        }
    }

    private fun rowView(i: Int, row: Row): LinearLayout = card().apply {
        val top = LinearLayout(this@SplitActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val catChip = chip(if (row.category == null) "Pick category ▾" else catLabel(row.category) + " ▾", row.category == null) {
            syncFromViews()
            pickCategory("Part ${i + 1} is…", row.category, includeExcluded = true) { picked -> row.category = picked; renderRows() }
        }
        if (row.category != null) catChip.background = rounded(C.cat(row.category) and 0x33FFFFFF, dp(20).toFloat())
        top.addView(catChip, lp(0, WRAP, 1f).apply { rightMargin = dp(10) })
        top.addView(label("₹", 18f, C.MUTED, true))
        val amount = EditText(this@SplitActivity).apply {
            tag = "amount"; hint = "0"; textSize = 20f; setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(row.amount); setTextColor(C.INK); setHintTextColor(C.MUTED); gravity = Gravity.END
            background = rounded(C.BG, dp(12).toFloat()); setPadding(dp(10), dp(8), dp(10), dp(8))
            addTextChangedListener(watch { row.amount = it; refreshTotals() })
        }
        top.addView(amount, lp(dp(110), WRAP))
        top.addView(label("✕", 18f, C.MUTED).apply {
            setPadding(dp(12), dp(6), dp(2), dp(6))
            contentDescription = "Remove part"
            setOnClickListener { syncFromViews(); rows.removeAt(i); if (rows.isEmpty()) rows.add(Row(null, "", "")); renderRows() }
        })
        addView(top)
        addView(EditText(this@SplitActivity).apply {
            tag = "note"; hint = "note (optional)"; textSize = 14f; setSingleLine()
            setText(row.note); setTextColor(C.INK); setHintTextColor(C.MUTED)
            background = rounded(C.BG, dp(12).toFloat()); setPadding(dp(12), dp(8), dp(12), dp(8))
            addTextChangedListener(watch { row.note = it })
        }, lp().apply { topMargin = dp(8) })
    }

    private fun watch(on: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { on(s?.toString() ?: "") }
    }

    /** Rows hold their own text via watchers; this is a no-op kept for clarity before structural changes. */
    private fun syncFromViews() {}

    private fun refreshTotals() {
        val p = parts()
        val rem = SplitMath.remaining(txn.amountPaise, p)
        when {
            rem > 0 -> { remainingText.text = rupees(rem) + " left of " + rupees(txn.amountPaise); remainingText.setTextColor(C.ACCENT) }
            rem < 0 -> { remainingText.text = rupees(-rem) + " over " + rupees(txn.amountPaise); remainingText.setTextColor(0xFFB3261E.toInt()) }
            else -> { remainingText.text = "All " + rupees(txn.amountPaise) + " assigned ✓"; remainingText.setTextColor(0xFF4E7D3A.toInt()) }
        }
        restChip.visibility = if (rem > 0) android.view.View.VISIBLE else android.view.View.GONE
        saveBtn.alpha = if (SplitMath.isValid(txn.amountPaise, p)) 1f else 0.45f
    }

    private fun putRest() {
        pickCategory("Put the rest (" + rupees(SplitMath.remaining(txn.amountPaise, parts())) + ") in…", null, includeExcluded = true) { cat ->
            val updated = SplitMath.putRest(txn.amountPaise, parts(), cat)
            // Map back onto rows, keeping notes.
            updated.forEachIndexed { i, p ->
                if (i < rows.size) { rows[i].category = p.category; rows[i].amount = if (p.amountPaise == 0L) rows[i].amount else SplitMath.rupeeText(p.amountPaise) }
                else rows.add(Row(p.category, SplitMath.rupeeText(p.amountPaise), ""))
            }
            renderRows()
        }
    }

    private fun save() {
        val p = parts()
        val err = db.saveSplit(txn, p)
        if (err != null) { Toast.makeText(this, err, Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "Split into ${SplitMath.cleaned(p).size} parts", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmUnsplit() {
        AlertDialog.Builder(this).setTitle("Undo split?")
            .setMessage("The payment goes back to one " + rupees(txn.amountPaise) + " spend in " + catLabel(txn.category) + ".")
            .setPositiveButton("Undo split") { _, _ -> db.unsplit(txn.id); finish() }
            .setNegativeButton("Keep", null).show()
    }
}
