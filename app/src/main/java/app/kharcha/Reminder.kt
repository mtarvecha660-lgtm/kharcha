package app.kharcha

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Calendar

object Reminder {
    const val CHANNEL = "nightly"
    private const val ACTION_FIRE = "app.kharcha.REMIND"

    fun prefs(ctx: Context) = ctx.getSharedPreferences("k", Context.MODE_PRIVATE)
    fun hour(ctx: Context) = prefs(ctx).getInt("rem_h", 21)
    fun minute(ctx: Context) = prefs(ctx).getInt("rem_m", 30)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("rem_on", true)

    fun setTime(ctx: Context, h: Int, m: Int) { prefs(ctx).edit().putInt("rem_h", h).putInt("rem_m", m).apply(); schedule(ctx) }
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("rem_on", on).apply(); schedule(ctx) }

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pi(ctx))
        if (!enabled(ctx)) return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour(ctx)); set(Calendar.MINUTE, minute(ctx)); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 5000) add(Calendar.DAY_OF_YEAR, 1)
        }
        // Inexact 10-minute window: needs no exact-alarm permission and survives Doze.
        am.setWindow(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 10 * 60 * 1000L, pi(ctx))
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nightly reminder", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Asks what your untagged payments were"
            })
        }
    }

    fun startOfDay(t: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun notifyNow(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel(ctx)
        val db = Db.get(ctx)
        val dayStart = startOfDay()
        val untagged = db.untaggedSince(dayStart)
        val today = db.between(dayStart, dayStart + 86400000L).filter { it.counts }
        val total = today.sumOf { it.amountPaise }
        val (title, text, target) = if (untagged.isNotEmpty()) {
            val n = untagged.size
            Triple(
                "$n untagged payment${if (n == 1) "" else "s"} today",
                untagged.take(3).joinToString(", ") { "${rupees(it.amountPaise)} · ${it.title}" } + " — what were they?",
                Intent(ctx, TagActivity::class.java),
            )
        } else {
            Triple(
                if (today.isEmpty()) "Spent any cash today?" else "${rupees(total)} spent today, all tagged ✓",
                "Tap to add a cash spend in 2 taps.",
                Intent(ctx, MainActivity::class.java).putExtra("add", true),
            )
        }
        target.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val content = PendingIntent.getActivity(ctx, 2, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(C.ACCENT)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(content)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(7, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "app.kharcha.REMIND") {
            val pending = goAsync()
            Thread {
                try { Reminder.notifyNow(context) } finally { Reminder.schedule(context); pending.finish() }
            }.start()
        } else {
            Reminder.schedule(context) // boot, app update, clock/timezone change
        }
    }
}
