package app.kharcha

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max

object C {
    const val BG = 0xFFFBF6EF.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val INK = 0xFF2B2420.toInt()
    const val MUTED = 0xFF8A7F76.toInt()
    const val ACCENT = 0xFFC8553D.toInt()
    const val ACCENT_SOFT = 0xFFF6E3DA.toInt()
    const val LINE = 0xFFEDE4DA.toInt()
    const val CHIP = 0xFFF3ECE3.toInt()
    val CAT = mapOf(
        "Food" to 0xFFE07A4F.toInt(), "Groceries" to 0xFF8DA65A.toInt(), "Travel" to 0xFF4F86A8.toInt(),
        "Shopping" to 0xFFB5658F.toInt(), "Bills" to 0xFFD9A441.toInt(), "Fun" to 0xFF8C6BB1.toInt(),
        "Health" to 0xFF5AA39A.toInt(), "Study" to 0xFF6B7FB8.toInt(), "People" to 0xFFB98B5E.toInt(),
        "Other" to 0xFF9A9087.toInt(), "Untagged" to 0xFFCFC4B8.toInt(),
    )
    fun cat(c: String?) = if (c == null) CAT.getValue("Untagged") else (CatStore.byName(c)?.color ?: CAT[c] ?: 0xFF9A9087.toInt())

    /** Colours offered when adding or editing a category: the original set plus a few more in the same muted family. */
    val PALETTE = listOf(
        0xFFE07A4F, 0xFFC8553D, 0xFFD9A441, 0xFF8DA65A, 0xFF5AA39A, 0xFF4F86A8,
        0xFF6B7FB8, 0xFF8C6BB1, 0xFFB5658F, 0xFFB98B5E, 0xFF9A9087, 0xFF5E6B73,
    ).map { it.toInt() }
}

/** In-memory copy of the user's categories (order, emoji, colour), refreshed after every edit. */
object CatStore {
    @Volatile private var list: List<Category> = emptyList()
    @Volatile private var map: Map<String, Category> = emptyMap()
    fun reload(db: Db) { list = db.categories(); map = list.associateBy { it.name } }
    fun ensure(db: Db) { if (list.isEmpty()) reload(db) }
    fun byName(n: String): Category? = map[n]
    /** Every category, spending ones first in the user's order, then ATM cash / Not spending. */
    fun all(): List<Category> = list
    /** Categories for picking what a spend was (no ATM cash / Not spending). */
    fun spending(): List<Category> = list.filter { it.counts }
    fun names(): List<String> = list.map { it.name }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(color: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radiusPx
    if (strokePx > 0) setStroke(strokePx, strokeColor)
}

fun Context.label(s: CharSequence, size: Float = 15f, color: Int = C.INK, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(C.CARD, dp(18).toFloat())
    setPadding(dp(16), dp(14), dp(16), dp(14))
    elevation = dp(1).toFloat()
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { setMargins(dp(16), dp(6), dp(16), dp(6)) }
}

fun Context.chip(text: String, selected: Boolean = false, onClick: (() -> Unit)? = null): TextView =
    TextView(this).apply {
        this.text = text; textSize = 14f
        setTextColor(if (selected) 0xFFFFFFFF.toInt() else C.INK)
        background = rounded(if (selected) C.ACCENT else C.CHIP, dp(20).toFloat())
        setPadding(dp(12), dp(8), dp(12), dp(8))
        gravity = Gravity.CENTER
        if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
    }

fun Context.button(text: String, primary: Boolean = true, onClick: () -> Unit): TextView =
    TextView(this).apply {
        this.text = text; textSize = 15f; gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else C.ACCENT)
        background = rounded(if (primary) C.ACCENT else C.ACCENT_SOFT, dp(24).toFloat())
        setPadding(dp(18), dp(12), dp(18), dp(12))
        setOnClickListener { onClick() }
    }

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

private val INR = NumberFormat.getNumberInstance(Locale("en", "IN"))
fun rupees(paise: Long): String {
    val whole = paise / 100; val frac = Math.abs(paise % 100)
    return "₹" + INR.format(whole) + if (frac != 0L) "." + frac.toString().padStart(2, '0') else ""
}

fun catLabel(c: String?): String =
    if (c == null) "Untagged" else (CatStore.byName(c)?.label ?: ((app.kharcha.core.Categorizer.EMOJI[c]?.let { "$it " } ?: "") + c))

/** Simple wrapping row for chips. */
class FlowLayout(ctx: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == View.GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += c.measuredWidth + hGap; rowH = max(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + hGap; rowH = max(rowH, c.measuredHeight)
        }
    }
}
