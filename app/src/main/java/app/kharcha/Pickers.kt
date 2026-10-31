package app.kharcha

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * A category picker in the app's chip style. [includeExcluded] adds ATM cash / Not spending.
 * The last chip opens the Categories screen so a missing category is one tap away.
 */
fun Activity.pickCategory(title: String, current: String?, includeExcluded: Boolean, onPick: (String) -> Unit) {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }
    box.addView(label(title, 18f, C.INK, true).apply { setPadding(0, 0, 0, dp(10)) })
    val flow = FlowLayout(this, dp(8), dp(8)); box.addView(flow)
    val dlg = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Cancel", null).create()
    val cats = if (includeExcluded) CatStore.all() else CatStore.spending()
    for (c in cats) flow.addView(chip(c.label, c.name == current) { dlg.dismiss(); onPick(c.name) })
    flow.addView(editCategoriesChip { dlg.dismiss() })
    dlg.show()
}

/** "✎ Edit categories" chip, used at the end of every category list. */
fun Activity.editCategoriesChip(before: () -> Unit = {}) = chip("✎ Edit categories") {
    before(); startActivity(Intent(this, CategoriesActivity::class.java))
}.apply { setTextColor(C.ACCENT); background = rounded(C.ACCENT_SOFT, dp(20).toFloat()) }
