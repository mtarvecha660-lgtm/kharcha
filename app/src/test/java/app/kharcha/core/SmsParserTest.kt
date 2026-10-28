package app.kharcha.core

import org.junit.Assert.*
import org.junit.Test

class SmsParserTest {
    private fun paise(s: String) = java.math.BigDecimal(s).movePointRight(2).toLong()

    @Test fun hdfcUpiSent() {
        val p = SmsParser.parse("VM-HDFCBK", "Sent Rs.240.00\nFrom HDFC Bank A/C *1234\nTo ZOMATO\nOn 27/10/26\nRef 429876543210\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("240.00"), p.amountPaise)
        assertEquals("ZOMATO", p.payee)
        assertEquals("HDFC", p.bank)
    }
    @Test fun sbiUpiTrf() {
        val p = SmsParser.parse("AD-SBIUPI", "Dear UPI user A/C X1234 debited by 150.0 on date 27Oct26 trf to RAHUL KUMAR Refno 429812345678. If not u? call 1800111109. -SBI")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("150.00"), p.amountPaise)
        assertEquals("RAHUL KUMAR", p.payee)
        assertEquals("SBI", p.bank)
    }
    @Test fun sbiTransferred() {
        val p = SmsParser.parse("JD-SBIINB", "Your A/C XXXXX1234 Debited INR 500.00 on 27/10/26 -Transferred to Mr. AMIT SHARMA. Avl Balance INR 2,345.67-SBI")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("500.00"), p.amountPaise)
        assertEquals("AMIT SHARMA", p.payee)
        assertEquals("SBI", p.bank)
    }
    @Test fun iciciUpiCredited() {
        val p = SmsParser.parse("JD-ICICIT", "ICICI Bank Acct XX123 debited for Rs 99.00 on 27-Oct-26; SWIGGY credited. UPI:429811112222. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766.")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("99.00"), p.amountPaise)
        assertEquals("SWIGGY", p.payee)
        assertEquals("ICICI", p.bank)
    }
    @Test fun iciciCard() {
        val p = SmsParser.parse("VK-ICICIB", "INR 1,299.00 spent using ICICI Bank Card XX4321 on 27-Oct-26 on AMAZON PAY IN. Avl Limit: INR 48,701.00. If not you, call 1800 2662/SMS BLOCK 4321 to 9215676766.")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("1299.00"), p.amountPaise)
        assertEquals("AMAZON PAY IN", p.payee)
        assertEquals("ICICI", p.bank)
    }
    @Test fun hdfcCard() {
        val p = SmsParser.parse("VM-HDFCBK", "Spent Rs.450 On HDFC Bank Card 1234 At UBER INDIA On 2026-10-27:14:22:11 Not You? To Block+Reissue Call 18002586161/SMS BLOCK CC 1234 to 7308080808")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("450.00"), p.amountPaise)
        assertEquals("UBER INDIA", p.payee)
        assertEquals("HDFC", p.bank)
    }
    @Test fun axisUpi() {
        val p = SmsParser.parse("AX-AXISBK", "INR 120.00 debited\nA/c no. XX1234\n27-10-26, 13:45:12\nUPI/P2M/429811113333/RAPIDO\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("120.00"), p.amountPaise)
        assertEquals("RAPIDO", p.payee)
        assertEquals("Axis", p.bank)
    }
    @Test fun axisCard() {
        val p = SmsParser.parse("AX-AXISBK", "Spent\nCard no. XX1234\nINR 799\n27-10-26 18:20:11\nMYNTRA\nAvl Lmt INR 45,000\nSMS BLOCK 1234 to 919951860002, if not you - Axis Bank")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("799.00"), p.amountPaise)
        assertEquals("MYNTRA", p.payee)
        assertEquals("Axis", p.bank)
    }
    @Test fun kotakVpa() {
        val p = SmsParser.parse("JM-KOTAKB", "Sent Rs.60.00 from Kotak Bank AC X1234 to chaiwala@okaxis on 27-10-26.UPI Ref 429814445555. Not you, https://kotak.com/KBANKT/Fraud")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("60.00"), p.amountPaise)
        assertEquals("chaiwala@okaxis", p.payee)
        assertEquals("Kotak", p.bank)
    }
    @Test fun pnbUpi() {
        val p = SmsParser.parse("BZ-PNBSMS", "A/c XX1234 debited INR 200.00 Dt 27-10-26 15:10:22 thru UPI:429815556666.Bal INR 1,200.00 CR Not u?Fwd this SMS to 9264092640 to block UPI.-PNB")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("200.00"), p.amountPaise)
        assertEquals("PNB", p.bank)
    }
    @Test fun bobVpa() {
        val p = SmsParser.parse("AD-BOBTXN", "Rs.350.00 Dr. from A/C XXXXXX1234 and Cr. to jio@paytm. Ref:429816667777. AvlBal:Rs5000.00(2026:10:27 16:05:41). Not you? Call 18005700/send SMS BLOCK to 8422009988-Bank of Baroda")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("350.00"), p.amountPaise)
        assertEquals("jio@paytm", p.payee)
        assertEquals("BOB", p.bank)
    }
    @Test fun paytmWallet() {
        val p = SmsParser.parse("VM-PAYTMB", "Paid Rs.40 to Shree Ganesh Tea Stall from Paytm Balance. Updated Balance: Paytm Wallet- Rs 210. More Details: https://paytm.me/x-abcd")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("40.00"), p.amountPaise)
        assertEquals("Shree Ganesh Tea Stall", p.payee)
        assertEquals("Paytm", p.bank)
    }
    @Test fun rupeeSymbolVpa() {
        val p = SmsParser.parse("VM-HDFCBK", "₹1,250.50 debited from a/c **1234 to VPA zomato.order@hdfcbank (UPI Ref No 429820001111)")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("1250.50"), p.amountPaise)
        assertEquals("zomato.order@hdfcbank", p.payee)
        assertEquals("HDFC", p.bank)
    }
    @Test fun sbiDebitCardPos() {
        val p = SmsParser.parse("AD-SBIPSG", "Thank you for using your SBI Debit Card ending 1234 for a purchase worth Rs 560.00 on POS 123456 at DMART AVENUE txn# 7788990011.")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("560.00"), p.amountPaise)
        assertEquals("DMART AVENUE", p.payee)
        assertEquals("SBI", p.bank)
    }
    @Test fun hdfcRecharge() {
        val p = SmsParser.parse("VM-HDFCBK", "Rs 239.00 debited from A/c XX1234 for Jio Prepaid Recharge on 27-10-26. Ref 4298123456 -HDFC Bank")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("239.00"), p.amountPaise)
        assertEquals("Jio Prepaid Recharge", p.payee)
        assertEquals("HDFC", p.bank)
    }
    @Test fun hdfcTxnBlinkit() {
        val p = SmsParser.parse("VM-HDFCBK", "Txn Rs.105.00\nOn HDFC Bank Card 1234\nAt BLINKIT\nby UPI 429833334444\nOn 27-10\nNot You?\nCall 18002586161/SMS BLOCK DC 1234 to 7308080808")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("105.00"), p.amountPaise)
        assertEquals("BLINKIT", p.payee)
        assertEquals("HDFC", p.bank)
    }
    @Test fun acToAc() {
        val p = SmsParser.parse("VM-UNIONB", "Your a/c no. XXXXXXXX1234 is debited for Rs.65.00 on 27-10-2026 and credited to a/c no. XXXXXXXX5678 (UPI Ref no 429844445555)")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("65.00"), p.amountPaise)
        assertEquals("Union", p.bank)
    }
    @Test fun atm() {
        val p = SmsParser.parse("AD-SBIINB", "Rs.2000 withdrawn at ATM S1AW1234 from A/c XX1234 on 27-10-26. Avl bal Rs 3,000.00 -SBI")
        assertNotNull(p); p!!
        assertEquals(Kind.DEBIT, p.kind)
        assertEquals(paise("2000.00"), p.amountPaise)
        assertEquals("ATM cash", p.payee)
        assertEquals("SBI", p.bank)
    }
    @Test fun reversal() {
        val p = SmsParser.parse("AD-SBIUPI", "Rs 300.00 debited from your A/c XX1234 on 27-10-26 has been reversed. Ref 429819990000-SBI")
        assertNotNull(p); p!!
        assertEquals(Kind.REVERSAL, p.kind)
        assertEquals(paise("300.00"), p.amountPaise)
        assertEquals("SBI", p.bank)
    }
    @Test fun creditHdfc() {
        val p = SmsParser.parse("VM-HDFCBK", "Rs.5000.00 credited to HDFC Bank A/c XX1234 on 27-10-26 from VPA papa@oksbi (UPI 429817778888)")
        assertNull(p)
    }
    @Test fun creditIcici() {
        val p = SmsParser.parse("JD-ICICIT", "Dear Customer, Acct XX123 is credited with Rs 2000.00 on 27-Oct-26 from RAHUL. UPI:429818889999-ICICI Bank.")
        assertNull(p)
    }
    @Test fun otp() {
        val p = SmsParser.parse("VM-HDFCBK", "123456 is OTP for txn of INR 999.00 at AMAZON on HDFC Bank card ending 1234. Valid till 14:22. Do not share OTP for security reasons.")
        assertNull(p)
    }
    @Test fun promo() {
        val p = SmsParser.parse("VM-PAYTMS", "Get flat Rs.100 cashback on your first UPI payment of Rs 500 with Paytm! T&C apply")
        assertNull(p)
    }
    @Test fun failed() {
        val p = SmsParser.parse("AD-SBIUPI", "Your UPI transaction of Rs 300.00 to swiggy@icici has failed. Ref 429819990000. Amount if debited will be refunded.")
        assertNull(p)
    }
    @Test fun balanceOnly() {
        val p = SmsParser.parse("AD-SBIINB", "Avl Bal in A/c XX1234 is INR 5,000.00 as on 27-10-26. -SBI")
        assertNull(p)
    }
    @Test fun futureAutopay() {
        val p = SmsParser.parse("VM-HDFCBK", "Rs 199 will be debited from your account on 30-10-26 for Netflix autopay. -HDFC Bank")
        assertNull(p)
    }
    @Test fun ccPaymentReceived() {
        val p = SmsParser.parse("VM-ICICIB", "Payment of Rs 5,000.00 received towards your ICICI Bank Credit Card XX4321. Thank you.")
        assertNull(p)
    }
    @Test fun personalNumber() {
        val p = SmsParser.parse("+919876543210", "I paid Rs 200 for the pizza, send me your share")
        assertNull(p)
    }
    @Test fun collectRequest() {
        val p = SmsParser.parse("VM-PAYTMB", "Rahul has requested Rs 150 from you on Paytm UPI. Tap to pay.")
        assertNull(p)
    }

    @Test fun refsExtracted() {
        assertEquals("429876543210", SmsParser.parse("VM-HDFCBK", "Sent Rs.240.00 From HDFC Bank A/C *1234 To ZOMATO On 27/10/26 Ref 429876543210")!!.ref)
        assertEquals("429811113333", SmsParser.parse("AX-AXISBK", "INR 120.00 debited\nA/c no. XX1234\nUPI/P2M/429811113333/RAPIDO")!!.ref)
    }
    @Test fun acToAcPayee() {
        assertEquals("A/c ••5678", SmsParser.parse("VM-UNIONB", "Your a/c no. XXXXXXXX1234 is debited for Rs.65.00 on 27-10-2026 and credited to a/c no. XXXXXXXX5678 (UPI Ref no 429844445555)")!!.payee)
    }
    @Test fun categories() {
        assertEquals("Food", Categorizer.guess("ZOMATO")); assertEquals("Food", Categorizer.guess("zomato.order@hdfcbank"))
        assertEquals("Travel", Categorizer.guess("UBER INDIA")); assertEquals("Travel", Categorizer.guess("RAPIDO"))
        assertEquals("Shopping", Categorizer.guess("AMAZON PAY IN")); assertEquals("Shopping", Categorizer.guess("MYNTRA"))
        assertEquals("Bills", Categorizer.guess("Jio Prepaid Recharge")); assertEquals("Bills", Categorizer.guess("jio@paytm"))
        assertEquals("Groceries", Categorizer.guess("BLINKIT")); assertEquals("Groceries", Categorizer.guess("DMART AVENUE"))
        assertEquals("Food", Categorizer.guess("Shree Ganesh Tea Stall"))
        assertNull(Categorizer.guess("RAHUL KUMAR")); assertNull(Categorizer.guess("MOTOROLA STORE"))
        assertNull(Categorizer.guess("9876543210@ybl"))
    }
}
