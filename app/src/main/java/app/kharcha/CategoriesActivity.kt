package app.kharcha

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Add, rename, re-emoji, recolour, reorder and delete categories. */
class CategoriesActivity : Activity() {
    private lateinit var db: Db
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.get(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        root.addView(label("Categories", 26f, C.INK, true).apply { setPadding(dp(20), dp(24), dp(20), dp(2)) })
        root.addView(label("Tap one to rename it or change its emoji and colour. ↑ ↓ to reorder.", 14f, C.MUTED).apply { setPadding(dp(20), 0, dp(20), dp(10)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(12)) }
        scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, lp(MATCH, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        bar.addView(button("+ New category", primary = false) { edit(null) }, lp(0, WRAP, 1f).apply { rightMargin = dp(8) })
        bar.addView(button("Done") { finish() }, lp(0, WRAP, 1f))
        root.addView(bar)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        CatStore.reload(db)
        val y = scroll.scrollY // keep the list where it was after a reorder or edit
        scroll.post { scroll.scrollTo(0, y) }
        list.removeAllViews()
        val spending = CatStore.spending()
        list.addView(card().apply {
            spending.forEachIndexed { i, c ->
                if (i > 0) addView(View(this@CategoriesActivity).apply { setBackgroundColor(C.LINE) }, lp(MATCH, 1))
                addView(row(c, i, spending))
            }
        })
        val fixed = CatStore.all().filter { !it.counts }
        if (fixed.isNotEmpty()) {
            list.addView(label("Logged but not counted as spending", 12f, C.MUTED, true).apply { setPadding(dp(24), dp(14), dp(24), dp(2)) })
            list.addView(card().apply {
                fixed.forEachIndexed { i, c ->
                    if (i > 0) addView(View(this@CategoriesActivity).apply { setBackgroundColor(C.LINE) }, lp(MATCH, 1))
                    addView(row(c, -1, fixed))
                }
            })
        }
    }

    private fun row(c: Category, index: Int, group: List<Category>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10))
        addView(View(this@CategoriesActivity).apply { background = rounded(c.color, dp(7).toFloat()) }, lp(dp(14), dp(14)).apply { rightMargin = dp(12) })
        val mid = LinearLayout(this@CategoriesActivity).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(label(c.label, 16f, C.INK, true).apply { maxLines = 1 })
        val n = db.usage(c.name)
        mid.addView(label(if (n == 0) "not used yet" else "$n payment${if (n == 1) "" else "s"}" + if (c.locked) " · built-in" else "", 12f, C.MUTED))
        addView(mid, lp(0, WRAP, 1f))
        if (index >= 0) {
            addView(arrow("↑", index > 0) { move(group, index, -1) })
            addView(arrow("↓", index < group.size - 1) { move(group, index, +1) })
        }
        setOnClickListener { edit(c) }
    }

    private fun arrow(s: String, enabled: Boolean, onClick: () -> Unit): TextView = label(s, 20f, if (enabled) C.ACCENT else C.LINE, true).apply {
        setPadding(dp(12), dp(4), dp(12), dp(4))
        contentDescription = if (s == "↑") "Move up" else "Move down"
        if (enabled) setOnClickListener { onClick() }
    }

    private fun move(group: List<Category>, i: Int, d: Int) {
        val ids = group.map { it.id }.toMutableList()
        val j = i + d
        if (j !in ids.indices) return
        ids[i] = ids[j].also { ids[j] = ids[i] }
        db.reorder(ids)
        render()
    }

    /** Add (c == null) or edit a category. */
    private fun edit(c: Category?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        box.addView(label(if (c == null) "New category" else if (c.locked) c.name else "Edit category", 18f, C.INK, true))
        var color = c?.color ?: C.PALETTE.firstOrNull { col -> CatStore.all().none { it.color == col } } ?: C.PALETTE[CatStore.all().size % C.PALETTE.size]
        val preview = label("", 15f, 0xFFFFFFFF.toInt(), true).apply { setPadding(dp(14), dp(8), dp(14), dp(8)) }
        val emoji = EditText(this).apply {
            hint = "emoji"; setText(c?.emoji ?: ""); textSize = 22f; setSingleLine(); gravity = Gravity.CENTER
            setTextColor(C.INK); setHintTextColor(C.MUTED)
        }
        val name = EditText(this).apply {
            hint = "name, e.g. Movies"; setText(c?.name ?: ""); textSize = 18f; setSingleLine()
            setTextColor(C.INK); setHintTextColor(C.MUTED); isEnabled = c?.locked != true
        }
        fun refresh() {
            val e = emoji.text.toString().trim(); val n = name.text.toString().trim().ifEmpty { "Name" }
            preview.text = (if (e.isNotEmpty()) "$e " else "") + n
            preview.background = rounded(color, dp(20).toFloat())
        }
        val w = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, cc: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, cc: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh() }
        }
        emoji.addTextChangedListener(w); name.addTextChangedListener(w)
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        nameRow.addView(emoji, lp(dp(64), WRAP).apply { rightMargin = dp(8) })
        nameRow.addView(name, lp(0, WRAP, 1f))
        box.addView(nameRow)
        // Quick emoji picks (any emoji from the keyboard works too).
        val picks = FlowLayout(this, dp(4), dp(4)).apply { setPadding(0, dp(8), 0, 0) }
        for (e in listOf("🍿", "🎁", "☕", "🏠", "👕", "🎮", "✈️", "⛽", "📱", "🐶", "💇", "🍺", "💼", "🎉", "🧾", "🏋️", "🚌", "🍕"))
            picks.addView(label(e, 22f).apply { setPadding(dp(6), dp(2), dp(6), dp(2)); setOnClickListener { emoji.setText(e) } })
        box.addView(picks)
        if (c?.locked == true) box.addView(label("Built-in: logged but never counted as spending, so it can't be renamed or deleted.", 12f, C.MUTED).apply { setPadding(0, dp(4), 0, 0) })
        box.addView(label("Colour", 12f, C.MUTED, true).apply { setPadding(0, dp(14), 0, dp(6)) })
        val swatches = FlowLayout(this, dp(10), dp(10)); box.addView(swatches)
        fun drawSwatches() {
            swatches.removeAllViews()
            for (col in C.PALETTE) swatches.addView(TextView(this).apply {
                width = dp(36); height = dp(36) // fixed size (FlowLayout measures children by their own size)
                background = rounded(col, dp(18).toFloat(), if (col == color) dp(3) else 0, C.INK)
                if (col == color) { text = "✓"; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; textSize = 16f }
                contentDescription = "colour"
                setOnClickListener { color = col; drawSwatches(); refresh() }
            })
        }
        drawSwatches()
        box.addView(label("Preview", 12f, C.MUTED, true).apply { setPadding(0, dp(14), 0, dp(6)) })
        box.addView(preview, lp(WRAP, WRAP))
        refresh()

        val b = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(if (c == null) "Add" else "Save", null)
            .setNegativeButton("Cancel", null)
        if (c != null && !c.locked) b.setNeutralButton("Delete", null)
        val dlg = b.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val err = if (c == null) db.addCategory(name.text.toString(), emoji.text.toString(), color)
                          else db.updateCategory(c, name.text.toString(), emoji.text.toString(), color)
                if (err != null) name.error = err else { dlg.dismiss(); render() }
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { dlg.dismiss(); confirmDelete(c!!) }
        }
        dlg.show()
    }

    private fun confirmDelete(c: Category) {
        if (CatStore.spending().size <= 1) { Toast.makeText(this, "Keep at least one category", Toast.LENGTH_SHORT).show(); return }
        val n = db.usage(c.name)
        if (n == 0) {
            AlertDialog.Builder(this).setTitle("Delete ${c.label}?").setMessage("No payments use it.")
                .setPositiveButton("Delete") { _, _ -> done(db.deleteCategory(c, fallbackName(c)), c) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        // In use: ask where its payments go. Default is "Other".
        val targets = CatStore.all().filter { it.id != c.id }
        val labels = targets.map { it.label }.toTypedArray()
        var chosen = targets.indexOfFirst { it.name == fallbackName(c) }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Delete ${c.label}? Move its $n payment${if (n == 1) "" else "s"} to:")
            .setSingleChoiceItems(labels, chosen) { _, which -> chosen = which }
            .setPositiveButton("Move & delete") { _, _ -> done(db.deleteCategory(c, targets[chosen].name), c) }
            .setNegativeButton("Cancel", null).show()
    }

    /** "Other" (or whatever the user renamed it to); if that's the one being deleted, the first other category. */
    private fun fallbackName(c: Category): String {
        val other = db.currentNameForSeed("Other")
        return if (other != null && other != c.name) other else CatStore.spending().first { it.id != c.id }.name
    }

    private fun done(err: String?, c: Category) {
        Toast.makeText(this, err ?: "Deleted ${c.name}", Toast.LENGTH_SHORT).show()
        render()
    }
}
