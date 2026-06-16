package com.xiddoc.claudeophobia.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xiddoc.claudeophobia.MainActivity
import com.xiddoc.claudeophobia.R
import com.xiddoc.claudeophobia.data.WeeklyNudges

/** Notification channel + posting for the daily pacing nudge. */
object Notifications {

    const val CHANNEL_ID = "weekly_nudge"
    private const val NOTIFICATION_ID = 4711

    fun ensureChannel(context: Context) {
        // minSdk is 26, so notification channels always exist.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Weekly pace nudges",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "A once-a-day, friendly check-in on your weekly Claude pace."
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    fun show(context: Context, nudge: WeeklyNudges.Nudge) {
        ensureChannel(context)

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nudge)
            .setContentTitle(nudge.title)
            .setContentText(nudge.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(nudge.body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // areNotificationsEnabled() covers the POST_NOTIFICATIONS runtime grant
        // on Android 13+; if the user declined, this is simply a no-op.
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) {
            try {
                manager.notify(NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // Permission revoked between the check and the post — ignore.
            }
        }
    }
}
