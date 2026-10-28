package app.kharcha

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.kharcha.core.Categorizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var db: Db
    private lateinit var content: LinearLayout
    private var period = 0 // 0 today, 1 week, 2 month
    private var importing = false
    private var smsDenied = false
    private val prefs by lazy { getSharedPreferences("k", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.get(this)
        val root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(96)) }
        root.addView(ScrollView(this).apply { addView(content) })
        val fab = button("+ Cash spend") { showAdd() }.apply { elevation = dp(6).toFloat() }
        root.addView(fab, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(16), dp(16), dp(20), dp(24)) })
        setContentView(root)
        Reminder.ensureChannel(this)
        Reminder.schedule(this)
        if (intent?.getBooleanExtra("add", false) == true) content.post { showAdd() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("add", false) == true) showAdd()
    }

    override fun onResume() {
        super.onResume()
        if (hasSms() && !prefs.getBoolean("imported", false) && !importing) runImport()
        render()
    }

    private fun runImport() {
        importing = true; render()
        Thread {
            val n = Importer.importInbox(this, 60)
            runOnUiThread {
                importing = false; render()
                Toast.makeText(this, if (n > 0) "Found $n payments from the last 60 days" else "No bank payment SMS found in the last 60 days", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun askSms() = requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS), 1)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            smsDenied = !hasSms()
            if (!smsDenied) {
                runImport()
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
            render()
        }
    }

    // ---------- ranges ----------
    private fun rangeStart(p: Int): Long {
        val sod = Reminder.startOfDay()
        val c = Calendar.getInstance().apply { timeInMillis = sod }
        return when (p) {
            0 -> sod
            1 -> { val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; c.add(Calendar.DAY_OF_YEAR, -dow); c.timeInMillis } // Monday
            else -> { c.set(Calendar.DAY_OF_MONTH, 1); c.timeInMillis }
        }
    }
    private val farFuture get() = System.currentTimeMillis() + 365L * 86400000

    // ---------- home ----------
    private fun render() {
        content.removeAllViews()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(16), dp(12), dp(8)) }
        header.addView(label("Kharcha", 28f, C.INK, true), lp(0, WRAP, 1f))
        header.addView(label("⚙", 24f, C.MUTED).apply { setPadding(dp(12), dp(4), dp(12), dp(4)); setOnClickListener { showSettings() } })
        content.addView(header)

        if (!hasSms()) content.addView(permissionCard())
        if (importing) content.addView(card().apply {
            addView(label("Reading your bank SMS from the last 60 days…", 15f, C.INK, true))
            addView(label("Only on this phone. Nothing is uploaded.", 13f, C.MUTED))
        })

        val untagged = db.untaggedSince(Reminder.startOfDay() - 6 * 86400000L)
        if (untagged.isNotEmpty()) content.addView(card().apply {
            background = rounded(C.ACCENT_SOFT, dp(18).toFloat())
            addView(label("${untagged.size} payment${if (untagged.size == 1) "" else "s"} need a tag  →", 16f, C.ACCENT, true))
            addView(label(untagged.take(3).joinToString("   ") { "${rupees(it.amountPaise)} · ${it.title}" }, 13f, C.INK).apply { maxLines = 1 })
            setOnClickListener { startActivity(Intent(this@MainActivity, TagActivity::class.java)) }
        })

        val totals = (0..2).map { p -> db.between(rangeStart(p), farFuture).filter { it.counts }.sumOf { it.amountPaise } }
        val txns = db.between(rangeStart(period), farFuture)
        val counted = txns.filter { it.counts }

        content.addView(card().apply {
            val chips = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("Today", "This week", "This month").forEachIndexed { i, s ->
                chips.addView(chip(s, i == period) { period = i; render() }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
            }
            addView(chips)
            addView(label(rupees(totals[period]), 40f, C.INK, true).apply { setPadding(0, dp(12), 0, 0) })
            addView(label("${counted.size} spend${if (counted.size == 1) "" else "s"}" +
                "   ·   today ${rupees(totals[0])}  ·  week ${rupees(totals[1])}  ·  month ${rupees(totals[2])}", 12f, C.MUTED))
        })

        if (counted.isNotEmpty()) content.addView(barsCard(counted))
        content.addView(listCard(txns))
    }

    private fun permissionCard() = card().apply {
        addView(label("Track spends automatically", 18f, C.INK, true))
        addView(label("Kharcha reads your bank & UPI payment SMS (debited / spent / sent) and logs the amount and shop for you. " +
            "It never sends anything anywhere — this app has no internet access at all.", 14f, C.INK).apply { setPadding(0, dp(6), 0, dp(12)) })
        addView(button("Allow SMS access") { askSms() })
        if (smsDenied) {
            addView(label("Blocked? On Android 13+ apps installed from WhatsApp/Files need one extra step:\n" +
                "App info → ⋮ (top right) → Allow restricted settings → then Permissions → SMS → Allow.", 13f, C.ACCENT).apply { setPadding(0, dp(12), 0, dp(8)) })
            addView(button("Open App info", primary = false) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            })
        }
    }

    private fun barsCard(counted: List<Txn>) = card().apply {
        addView(label("Where it went", 16f, C.INK, true).apply { setPadding(0, 0, 0, dp(10)) })
        val byCat = counted.groupBy { it.category ?: "Untagged" }.mapValues { e -> e.value.sumOf { it.amountPaise } }
            .entries.sortedByDescending { it.value }
        val max = byCat.maxOf { it.value }.coerceAtLeast(1)
        val total = byCat.sumOf { it.value }.coerceAtLeast(1)
        for ((cat, sum) in byCat) {
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            row.addView(label(catLabel(if (cat == "Untagged") null else cat), 13f, C.INK).apply { maxLines = 1 }, lp(dp(112), WRAP))
            val track = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            val frac = sum.toFloat() / max
            track.addView(View(this@MainActivity).apply { background = rounded(C.cat(if (cat == "Untagged") null else cat), dp(6).toFloat()) }, lp(0, dp(12), frac))
            track.addView(View(this@MainActivity), lp(0, dp(12), 1f - frac + 0.0001f))
            row.addView(track, lp(0, WRAP, 1f))
            row.addView(label(rupees(sum) + "  " + (sum * 100 / total) + "%", 12f, C.MUTED).apply { gravity = Gravity.END }, lp(dp(96), WRAP))
            addView(row)
        }
    }

    private fun listCard(txns: List<Txn>) = card().apply {
        addView(label(if (period == 0) "Today" else "Payments", 16f, C.INK, true).apply { setPadding(0, 0, 0, dp(4)) })
        if (txns.isEmpty()) {
            addView(label(if (hasSms()) "No spends yet. New bank SMS show up here automatically." else "Allow SMS access above, or add a cash spend.", 14f, C.MUTED).apply { setPadding(0, dp(8), 0, dp(8)) })
            return@apply
        }
        val dayFmt = SimpleDateFormat("EEEE, d MMM", Locale("en", "IN"))
        val timeFmt = SimpleDateFormat("h:mm a", Locale("en", "IN"))
        var lastDay = ""
        for (t in txns.take(300)) {
            val d = dayFmt.format(Date(t.ts))
            if (period != 0 && d != lastDay) {
                addView(label(d, 12f, C.MUTED, true).apply { setPadding(0, dp(12), 0, dp(2)) }); lastDay = d
            }
            addView(txnRow(t, timeFmt))
        }
    }

    private fun txnRow(t: Txn, timeFmt: SimpleDateFormat): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10))
        addView(View(this@MainActivity).apply { background = rounded(C.cat(t.category), dp(5).toFloat()) }, lp(dp(10), dp(10)).apply { rightMargin = dp(12) })
        val mid = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(label(t.title, 15f, C.INK, true).apply { maxLines = 1 })
        val sub = (if (t.category == null) "Tap to tag" else catLabel(t.category)) + " · " + timeFmt.format(Date(t.ts)) +
            (t.bank?.let { " · $it" } ?: if (t.source == "manual") " · cash" else "") + (t.note?.let { " · $it" } ?: "")
        mid.addView(label(sub, 12f, if (t.category == null) C.ACCENT else C.MUTED).apply { maxLines = 1 })
        addView(mid, lp(0, WRAP, 1f))
        addView(label(rupees(t.amountPaise), 16f, if (t.counts) C.INK else C.MUTED, true).apply {
            if (t.reversed) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        })
        setOnClickListener { showEdit(t) }
    }

    // ---------- dialogs ----------
    private fun dialogBox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }

    private fun showAdd() {
        val box = dialogBox()
        val amount = EditText(this).apply {
            hint = "₹ amount"; textSize = 28f; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(C.INK); setHintTextColor(C.MUTED)
        }
        val note = EditText(this).apply { hint = "what for? (optional)"; textSize = 15f; setSingleLine(); setTextColor(C.INK); setHintTextColor(C.MUTED) }
        box.addView(label("Cash spend", 18f, C.INK, true)); box.addView(amount); box.addView(note)
        box.addView(label("Tap a category to save", 12f, C.MUTED).apply { setPadding(0, dp(10), 0, dp(6)) })
        val flow = FlowLayout(this, dp(8), dp(8)); box.addView(flow)
        val dlg = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Cancel", null).create()
        for (cat in Categorizer.CHIPS) flow.addView(chip(catLabel(cat)) {
            val v = amount.text.toString().replace(",", "").toBigDecimalOrNull()
            if (v == null || v.signum() <= 0) { amount.error = "Enter amount"; return@chip }
            db.addManual(v.movePointRight(2).toLong(), cat, note.text.toString())
            dlg.dismiss(); render()
            Toast.makeText(this, "Added ${rupees(v.movePointRight(2).toLong())} · $cat", Toast.LENGTH_SHORT).show()
        })
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show(); amount.requestFocus()
    }

    private fun showEdit(t: Txn) {
        val box = dialogBox()
        box.addView(label(rupees(t.amountPaise) + " · " + t.title, 20f, C.INK, true))
        box.addView(label(SimpleDateFormat("EEE d MMM yyyy, h:mm a", Locale("en", "IN")).format(Date(t.ts)) +
            listOfNotNull(t.bank, t.account?.let { "••$it" }, t.ref?.let { "Ref $it" }).joinToString(" · ", prefix = "  "), 12f, C.MUTED))
        val flow = FlowLayout(this, dp(8), dp(8)).apply { setPadding(0, dp(12), 0, dp(8)) }
        box.addView(flow)
        val note = EditText(this).apply { hint = "note"; setText(t.note ?: ""); setSingleLine(); setTextColor(C.INK); setHintTextColor(C.MUTED) }
        box.addView(note)
        if (t.raw != null) box.addView(label(t.raw, 11f, C.MUTED).apply { setPadding(0, dp(10), 0, 0) })
        val dlg = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ -> db.setNote(t.id, note.text.toString()); render() }
            .setNeutralButton("Delete") { _, _ -> db.delete(t.id); render() }
            .setNegativeButton(if (t.reversed) "Not reversed" else "Mark refunded") { _, _ -> db.setReversed(t.id, !t.reversed); render() }
            .create()
        for (cat in Categorizer.ALL) flow.addView(chip(catLabel(cat), cat == t.category) {
            db.setCategory(t, cat); db.setNote(t.id, note.text.toString()); dlg.dismiss(); render()
        })
        dlg.show()
    }

    private fun showSettings() {
        val box = dialogBox()
        box.addView(label("Settings", 18f, C.INK, true))
        val timeText = label("", 15f, C.INK)
        fun refreshTime() {
            val h = Reminder.hour(this); val m = Reminder.minute(this)
            timeText.text = "Nightly reminder: " + (if (Reminder.enabled(this)) String.format(Locale.US, "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM") else "off")
        }
        refreshTime()
        box.addView(timeText.apply { setPadding(0, dp(12), 0, dp(6)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(chip("Change time") {
            TimePickerDialog(this, { _, h, m -> Reminder.setTime(this, h, m); Reminder.setEnabled(this, true); refreshTime() },
                Reminder.hour(this), Reminder.minute(this), false).show()
        }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        row.addView(chip(if (Reminder.enabled(this)) "Turn off" else "Turn on") { Reminder.setEnabled(this, !Reminder.enabled(this)); refreshTime() }, lp(WRAP, WRAP).apply { rightMargin = dp(8) })
        row.addView(chip("Test now") { Reminder.notifyNow(this) })
        box.addView(row)
        box.addView(chip("Re-scan SMS (last 60 days)") { if (hasSms()) runImport() else askSms() }, lp(WRAP, WRAP).apply { topMargin = dp(14) })
        box.addView(chip("Export CSV") { exportCsv() }, lp(WRAP, WRAP).apply { topMargin = dp(8) })
        box.addView(label("ATM withdrawals are logged as \"ATM cash\" and not counted, so cash you add by hand isn't counted twice.\n\n" +
            "Privacy: everything stays on this phone. Kharcha has no internet permission.", 12f, C.MUTED).apply { setPadding(0, dp(14), 0, 0) })
        AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Close", null).show()
    }

    // ---------- CSV ----------
    private fun exportCsv() {
        val name = "kharcha-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".csv"
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv").putExtra(Intent.EXTRA_TITLE, name), 42)
    }

    @Deprecated("framework API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != 42 || resultCode != RESULT_OK) return
        fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"").replace("\n", " ") + "\""
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("date,amount,payee,category,note,bank,account,ref,source,reversed,sms\n")
        for (t in db.all()) sb.append(listOf(q(f.format(Date(t.ts))), String.format(Locale.US, "%.2f", t.amountPaise / 100.0), q(t.payee), q(t.category),
            q(t.note), q(t.bank), q(t.account), q(t.ref), t.source, if (t.reversed) "yes" else "no", q(t.raw)).joinToString(",")).append('\n')
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
            Toast.makeText(this, "Exported ${db.count()} rows", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show() }
    }
}
