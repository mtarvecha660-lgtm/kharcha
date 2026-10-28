package app.kharcha.core

object Categorizer {
    const val ATM = "ATM cash"
    const val NOT_SPENDING = "Not spending"
    val CHIPS = listOf("Food", "Groceries", "Travel", "Shopping", "Bills", "Fun", "Health", "Study", "People", "Other")
    val ALL = CHIPS + listOf(ATM, NOT_SPENDING)
    /** Categories that are logged but not counted as spending (ATM cash would double-count manual cash spends). */
    val EXCLUDED = setOf(ATM, NOT_SPENDING)

    val EMOJI = mapOf(
        "Food" to "🍔", "Groceries" to "🛒", "Travel" to "🛺", "Shopping" to "🛍️", "Bills" to "💡",
        "Fun" to "🎬", "Health" to "💊", "Study" to "📚", "People" to "👥", "Other" to "📦",
        ATM to "🏧", NOT_SPENDING to "↩️",
    )

    private val KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "Food" to listOf("zomato", "swiggy", "domino", "pizza", "mcdonald", "kfc", "burger", "cafe", "café", "chai", "tea",
            "restaurant", "dhaba", "eatsure", "starbucks", "bakery", "bakers", "juice", "canteen", "mess", "food", "foods",
            "biryani", "haldiram", "subway", "chaayos", "barbeque", "eatclub", "faasos", "box8", "kitchen", "sweets", "dosa"),
        "Groceries" to listOf("blinkit", "grofers", "zepto", "bigbasket", "dmart", "instamart", "jiomart", "grocery", "grocer",
            "kirana", "supermarket", "reliance fresh", "more retail", "milk", "dairy", "vegetable", "fruits", "provision", "general store"),
        "Travel" to listOf("uber", "ola", "olacabs", "rapido", "irctc", "redbus", "makemytrip", "goibibo", "indigo", "metro",
            "petrol", "fuel", "hpcl", "bpcl", "iocl", "indian oil", "fastag", "yulu", "namma yatri", "cab", "railway",
            "air india", "akasa", "ixigo", "abhibus", "bounce", "parking", "toll"),
        "Shopping" to listOf("amazon", "flipkart", "myntra", "ajio", "meesho", "nykaa", "decathlon", "croma", "reliance digital",
            "lenskart", "tata cliq", "snapdeal", "shopsy", "westside", "zudio", "trends", "pantaloons", "max fashion", "h&m", "uniqlo"),
        "Bills" to listOf("jio", "airtel", "vodafone", "vi", "bsnl", "recharge", "electricity", "bescom", "msedcl", "tneb",
            "broadband", "fibernet", "gas", "bill", "billpay", "postpaid", "prepaid", "dth", "tata play", "rent", "water", "insurance", "lic"),
        "Fun" to listOf("netflix", "spotify", "hotstar", "jiocinema", "prime video", "bookmyshow", "pvr", "inox", "youtube",
            "steam", "playstation", "gaming", "district", "cinepolis", "sonyliv", "zee5", "apple.com", "google play"),
        "Health" to listOf("pharmacy", "apollo", "medplus", "1mg", "pharmeasy", "netmeds", "hospital", "clinic", "doctor",
            "medical", "chemist", "diagnostic", "lab", "gym", "cult"),
        "Study" to listOf("udemy", "coursera", "college", "university", "school", "fees", "book", "books", "stationery",
            "xerox", "unacademy", "byju", "physics wallah", "pw", "library", "exam"),
    )

    private val WORD_RES = KEYWORDS.map { (cat, words) ->
        cat to Regex("(?<![a-z])(" + words.joinToString("|") { Regex.escape(it) } + ")(?![a-z])", RegexOption.IGNORE_CASE)
    }

    /** Guess a category from the payee name / VPA. Null when unknown (e.g. a person) — the nightly prompt asks. */
    fun guess(payee: String?): String? {
        if (payee.isNullOrBlank()) return null
        if (payee == "ATM cash") return ATM
        val p = payee.lowercase().replace('.', ' ').replace('_', ' ').replace('-', ' ').replace('@', ' ')
        for ((cat, re) in WORD_RES) if (re.containsMatchIn(p)) return cat
        return null
    }
}
