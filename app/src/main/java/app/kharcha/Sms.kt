package app.kharcha

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony

fun Context.hasSms(): Boolean =
    checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

object Importer {
    /** Reads the SMS inbox for the last [days] days and stores every debit found. Returns how many were added. */
    fun importInbox(ctx: Context, days: Int = 60): Int {
        if (!ctx.hasSms()) return 0
        val db = Db.get(ctx)
        val since = System.currentTimeMillis() - days * 86400000L
        var added = 0
        try {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()), "${Telephony.Sms.DATE} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val addr = c.getString(0) ?: continue
                    val body = c.getString(1) ?: continue
                    if (db.ingestSms(addr, body, c.getLong(2)) != null) added++
                }
            }
        } catch (e: SecurityException) { return added }
        ctx.getSharedPreferences("k", Context.MODE_PRIVATE).edit().putBoolean("imported", true).apply()
        return added
    }
}

/** Logs each debit SMS the moment it arrives. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val pending = goAsync()
        Thread {
            try {
                // Multi-part SMS arrive as several PDUs: join them per sender.
                val bySender = LinkedHashMap<String, StringBuilder>()
                for (m in msgs) {
                    val from = m.displayOriginatingAddress ?: m.originatingAddress ?: continue
                    bySender.getOrPut(from) { StringBuilder() }.append(m.displayMessageBody ?: m.messageBody ?: "")
                }
                val db = Db.get(context)
                val now = System.currentTimeMillis()
                for ((from, body) in bySender) db.ingestSms(from, body.toString(), now)
            } finally { pending.finish() }
        }.start()
    }
}
