package app.kharcha.core

enum class Kind { DEBIT, REVERSAL }

data class Parsed(
    val kind: Kind,
    val amountPaise: Long,
    val payee: String?,
    val payeeKey: String?,
    val bank: String?,
    val account: String?,
    val ref: String?,
    val isAtm: Boolean = false,
)

/**
 * Parses Indian bank / UPI / card transaction SMS. Pure Kotlin, no Android imports,
 * so it is unit-tested on the JVM. Returns null for anything that is not a money-out
 * event (credits, OTPs, balance alerts, promos, failed txns, future debits).
 */
object SmsParser {
    private val IC = setOf(RegexOption.IGNORE_CASE)

    private val AMOUNT = Regex("""(?:(?<![A-Za-z])(?:rs\.?|inr)|₹)\s*:?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", IC)
    private val BARE_AMOUNT = Regex("""(?:debited|deducted)\s+(?:by|for|with)?\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\b""", IC)

    private val DEBIT = Regex("""\b(debited|spent|sent|paid|withdrawn|deducted|purchase|transferred|dr\.|dr\b|txn|trf)""", IC)
    private val CREDIT = Regex("""\b(credited|received|deposited|cr\.)""", IC)

    private val OTP = Regex("""\botp\b|one[ -]time password|verification code|\bpasscode\b""", IC)
    private val NOT_YET = Regex("""will be debited|to be debited|is due|due on|due date|amount due|requested|collect request|is scheduled|mandate|autopay (?:is )?set|e-?mandate|reminder""", IC)
    private val FAILED = Regex("""\bfailed\b|declined|unsuccessful|could not be|not processed|was not successful|has been cancelled""", IC)
    private val REVERSAL = Regex("""reversed|reversal|refund""", IC)
    private val PROMO = Regex("""\boffer\b|t&c|apply now|pre-?approved|congratulations|voucher|discount|\bupto\b|limited period|hurry|\bwin\b|click to claim""", IC)
    private val ACCOUNT_MARK = Regex("""a/c|\bac\b|acct|account|card|\bref|upi:|upi/|rrn|utr|wallet|balance""", IC)

    private val VPA_TO = Regex("""(?:\bto|\bvpa|\bcr\.?\s*to|\btrf to|\bpaid to)\s*:?\s*(?:vpa\s*)?([a-z0-9][a-z0-9._-]{1,}@[a-z][a-z0-9]{1,})""", IC)
    private val ANY_VPA = Regex("""([a-z0-9][a-z0-9._-]{1,}@[a-z][a-z0-9]{1,})""", IC)
    private val UPI_SLASH = Regex("""upi/p2[am]/(\d+)/([^/\n|]+)""", IC)
    private val SEMI_CREDITED = Regex(""";\s*([^;.\n]+?)\s+credited""", IC)

    private const val STOP = """(?=\s+(?:on|via|from|ref|refno|upi|avl|bal|not|txn|using|thru|dated|dt|by|is|has|and|info|value|for|at)\b|\s*[.;(,|!]|\s+\d|\s*$)"""
    private val TO_NAME = Regex("""\b(?:trf to|transferred to|paid to|sent to|to)\s+(.+?)$STOP""", IC)
    private val AT_NAME = Regex("""\bat\s+(.+?)$STOP""", IC)
    private val FOR_NAME = Regex("""\bfor\s+(?!rs\b|rs\.|inr|₹|\d|a purchase|purchase|txn|dispute|security|more|any|details|queries)(.+?)$STOP""", IC)
    private val ON_NAME = Regex("""\bon\s+([A-Z][A-Z0-9 &*'_-]{2,}?)$STOP""")
    private val HONORIFIC = Regex("""\b(?:mr|mrs|ms|miss|shri|smt|m/s|dr)\.?\s+(?=[A-Za-z])""", IC)

    private val ACCOUNT = Regex("""(?:a/c|\bac\b|acct|account|card)(?:\s*no\.?)?(?:\s*ending(?:\s*with)?)?\s*[x*#.]*\s*(\d{3,6})\b""", IC)
    private val REF = Regex("""(?:upi\s*ref(?:\s*no)?|\bref(?:\s*no)?|refno|\brrn|\butr|txn\s*id|txn#|\bupi)[\s:.#-]*((?=[A-Z0-9]*\d)[A-Z0-9]{8,})""", IC)

    private val BANKS = listOf(
        "HDFC" to listOf("hdfc"), "SBI" to listOf("sbi", "state bank"), "ICICI" to listOf("icici"),
        "Axis" to listOf("axis"), "Kotak" to listOf("kotak"), "PNB" to listOf("pnb", "punjab national"),
        "BOB" to listOf("bank of baroda", "bobtxn", "bobsms", "barodam"), "Canara" to listOf("canara", "cnrb"),
        "Union" to listOf("union bank", "unionb"), "IDFC" to listOf("idfc"), "Yes Bank" to listOf("yes bank", "yesbnk"),
        "IndusInd" to listOf("indusind", "indusb"), "BOI" to listOf("bank of india", "boiind"),
        "Federal" to listOf("federal", "fedbnk"), "AU" to listOf("au bank", "aubank"),
        "IPPB" to listOf("ippb", "india post"), "Airtel PB" to listOf("airtel payments", "airbnk"),
        "Paytm" to listOf("paytm balance", "paytm wallet", "paytm payments bank", "paytmb"),
    )

    fun isPersonalNumber(sender: String): Boolean {
        val s = sender.replace(" ", "")
        return s.length >= 10 && s.all { it.isDigit() || it == '+' }
    }

    fun parse(sender: String, body: String): Parsed? {
        if (isPersonalNumber(sender)) return null
        val text = body.replace('\u00A0', ' ')
        if (OTP.containsMatchIn(text)) return null
        if (NOT_YET.containsMatchIn(text)) return null
        if (FAILED.containsMatchIn(text)) return null
        if (PROMO.containsMatchIn(text) && !ACCOUNT_MARK.containsMatchIn(text)) return null

        val amount = findAmount(text) ?: return null
        if (amount <= 0) return null
        val bank = detectBank(sender, text)
        val account = ACCOUNT.find(text)?.groupValues?.get(1)
        val ref = findRef(text)

        if (REVERSAL.containsMatchIn(text)) {
            return Parsed(Kind.REVERSAL, amount, null, null, bank, account, ref)
        }

        val d = DEBIT.find(text) ?: return null
        val c = CREDIT.find(text)
        if (c != null && c.range.first < d.range.first) return null

        val atm = Regex("""\batm\b""", IC).containsMatchIn(text) &&
            Regex("""withdraw|cash""", IC).containsMatchIn(text)
        if (atm) return Parsed(Kind.DEBIT, amount, "ATM cash", "atm cash", bank, account, ref, isAtm = true)

        val payee = findPayee(text)
        return Parsed(Kind.DEBIT, amount, payee, payee?.let { keyOf(it) }, bank, account, ref)
    }

    fun keyOf(payee: String): String = payee.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun toPaise(s: String): Long? {
        val clean = s.replace(",", "").trimEnd('.')
        val v = clean.toBigDecimalOrNull() ?: return null
        return v.movePointRight(2).toLong()
    }

    private fun findAmount(text: String): Long? {
        for (m in AMOUNT.findAll(text)) {
            val start = m.range.first
            var before = text.substring(maxOf(0, start - 30), start)
            val nl = before.lastIndexOf('\n'); if (nl >= 0) before = before.substring(nl + 1)
            if (Regex("""bal|limit|avl|available""", IC).containsMatchIn(before)) continue
            return toPaise(m.groupValues[1])
        }
        return BARE_AMOUNT.find(text)?.let { toPaise(it.groupValues[1]) }
    }

    private fun findRef(text: String): String? {
        UPI_SLASH.find(text)?.let { return it.groupValues[1] }
        return REF.findAll(text).map { it.groupValues[1] }.firstOrNull()
    }

    private fun detectBank(sender: String, text: String): String? {
        val s = sender.lowercase()
        for ((name, keys) in BANKS) if (keys.any { s.contains(it.replace(" ", "")) }) return name
        if (s.contains("bob")) return "BOB"
        val t = text.lowercase()
        for ((name, keys) in BANKS) if (keys.any { t.contains(it) }) return name
        return null
    }

    private val BAD_PAYEE = Regex("""^(your|you|the|a |an |bank|block|sms|call|dispute|security|avoid|report|customer|us\b|this)|\bblock\b|\bcall\b|\bbank\b|\bcard\b|^\d""", IC)

    private fun clean(raw: String): String? {
        var p = raw.trim().trim('-', ':', '*', '\'', '"', ' ').replace(Regex("\\s+"), " ")
        if (p.length > 40) p = p.substring(0, 40).trim()
        if (p.length < 2) return null
        return p
    }

    private fun findPayee(original: String): String? {
        val lines = original.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val flat = HONORIFIC.replace(lines.joinToString(" | "), "")

        VPA_TO.find(flat)?.let { return it.groupValues[1].lowercase() }
        UPI_SLASH.find(flat)?.let { m -> clean(m.groupValues[2])?.let { return it } }
        SEMI_CREDITED.find(flat)?.let { m -> clean(m.groupValues[1])?.let { if (!BAD_PAYEE.containsMatchIn(it)) return it } }

        Regex("""\bto\s+(?:a/c|ac|acct|account)\s*(?:no\.?)?\s*[x*#.]*\s*(\d{3,6})\b""", IC).find(flat)?.let { return "A/c ••" + it.groupValues[1] }

        for (re in listOf(TO_NAME, AT_NAME, FOR_NAME)) {
            for (m in re.findAll(flat)) {
                val cand = clean(m.groupValues[1]) ?: continue
                val acct = Regex("""^(?:a/c|ac|acct|account)\s*(?:no\.?)?\s*[x*#.]*\s*(\d{3,6})$""", IC).find(cand)
                if (acct != null) return "A/c ••" + acct.groupValues[1]
                if (BAD_PAYEE.containsMatchIn(cand)) continue
                if (Regex("""^\d|^[x*]+\d""", IC).containsMatchIn(cand)) continue
                return cand
            }
        }
        for (m in ON_NAME.findAll(flat)) {
            val cand = clean(m.groupValues[1]) ?: continue
            if (BAD_PAYEE.containsMatchIn(cand)) continue
            return cand
        }
        ANY_VPA.findAll(flat).map { it.groupValues[1] }.lastOrNull()?.let { return it.lowercase() }
        // Multi-line formats (e.g. Axis card): a line that is just an uppercase merchant name.
        if (lines.size >= 3) {
            for (l in lines.drop(1)) {
                if (Regex("""^[A-Z][A-Z0-9 &*'._-]{2,39}$""").matches(l) &&
                    !Regex("""bank|card|\bist\b|avl|not you|limit|a/c|\binr\b|\brs\b|^spent$|^txn$|\d{2}[-/:]\d{2}""", IC).containsMatchIn(l)) return l
            }
        }
        return null
    }
}
