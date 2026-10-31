package app.kharcha

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The nightly "what were these?" screen: one tap per untagged payment. */
class TagActivity : Activity() {
    private lateinit var db: Db
    private lateinit var list: LinearLayout
    private val notes = HashMap<Long, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.get(this)
        CatStore.reload(db)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        root.addView(label("What were these?", 26f, C.INK, true).apply { setPadding(dp(20), dp(24), dp(20), dp(2)) })
        root.addView(label("Tap a category. I'll remember the payee next time.", 14f, C.MUTED).apply { setPadding(dp(20), 0, dp(20), dp(10)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(12)) }
        root.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f))
        root.addView(button("Done") { finish() }, lp().apply { setMargins(dp(16), dp(8), dp(16), dp(16)) })
        setContentView(root)
        render()
    }

    override fun onPause() {
        super.onPause()
        for ((id, n) in notes) db.setNote(id, n)
    }

    private fun render() {
        list.removeAllViews()
        val items = db.untaggedSince(Reminder.startOfDay() - 6 * 86400000L)
        if (items.isEmpty()) {
            list.addView(card().apply {
                addView(label("All tagged ✓", 18f, C.INK, true))
                addView(label("Nothing left to sort. Nice.", 14f, C.MUTED))
            })
            return
        }
        val fmt = SimpleDateFormat("EEE d MMM, h:mm a", Locale("en", "IN"))
        for (t in items) list.addView(row(t, fmt))
    }

    private fun row(t: Txn, fmt: SimpleDateFormat): LinearLayout = card().apply {
        val head = LinearLayout(this@TagActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(rupees(t.amountPaise), 20f, C.INK, true))
        head.addView(label("  ·  " + t.title, 16f, C.INK).apply { maxLines = 1 }, lp(0, WRAP, 1f))
        addView(head)
        addView(label(fmt.format(Date(t.ts)) + (t.bank?.let { " · $it" } ?: ""), 12f, C.MUTED).apply { setPadding(0, dp(2), 0, dp(10)) })

        val status = label("", 13f, C.ACCENT, true)
        val flow = FlowLayout(this@TagActivity, dp(8), dp(8))
        for (cat in CatStore.spending().map { it.name }) {
            flow.addView(chip(catLabel(cat)) {
                db.setCategory(t, cat)
                notes[t.id]?.let { db.setNote(t.id, it) }
                status.text = "✓ " + catLabel(cat) + if (t.payeeKey != null) "  (remembered for ${t.title})" else ""
                for (i in 0 until flow.childCount) {
                    val v = flow.getChildAt(i) as TextView
                    val sel = v.text == catLabel(cat)
                    v.background = rounded(if (sel) C.ACCENT else C.CHIP, dp(20).toFloat())
                    v.setTextColor(if (sel) 0xFFFFFFFF.toInt() else C.INK)
                }
            })
        }
        addView(flow)
        addView(EditText(this@TagActivity).apply {
            hint = "what was it? (optional)"; textSize = 14f; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_DONE
            setText(t.note ?: ""); setTextColor(C.INK); setHintTextColor(C.MUTED)
            background = rounded(C.BG, dp(12).toFloat()); setPadding(dp(12), dp(10), dp(12), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { notes[t.id] = s?.toString() ?: "" }
            })
        }, lp().apply { topMargin = dp(10) })
        addView(status, lp().apply { topMargin = dp(6) })
    }
}
