package com.khedr.smartassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.StatusBarNotification

/** Approval notification. Reply button uses WhatsApp's own inline action if available. */
object ReplyReviewNotifier {
    private const val CHANNEL = "reply_review"
    fun show(context: Context, contact: String, incoming: String, proposed: String, sbn: StatusBarNotification) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "مراجعة الردود", NotificationManager.IMPORTANCE_HIGH))
        val id = (contact + incoming).hashCode()
        val action = sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val approve = if (action != null) {
            val sendIntent = Intent(context, ReplyApprovalReceiver::class.java).apply {
                putExtra(ReplyApprovalReceiver.EXTRA_TEXT, proposed)
                putExtra(ReplyApprovalReceiver.EXTRA_ACTION, action)
                putExtra(ReplyApprovalReceiver.EXTRA_NOTIFICATION_ID, id)
                putExtra(ReplyApprovalReceiver.EXTRA_CONTACT, contact)
            }
            val pi = PendingIntent.getBroadcast(context, id, sendIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_send), "إرسال الرد المقترح", pi).build()
        } else null
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        builder.setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("رد مقترح على: $contact")
            .setContentText("الرسالة: $incoming")
            .setStyle(Notification.BigTextStyle().bigText("الرسالة: $incoming\n\nالرد المقترح: $proposed"))
            .setColor(0xFF4DE38A.toInt())
            .setAutoCancel(true)
        if (approve != null) builder.addAction(approve)
        nm.notify(id, builder.build())
    }
}
