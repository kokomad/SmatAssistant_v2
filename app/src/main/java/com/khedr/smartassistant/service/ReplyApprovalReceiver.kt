package com.khedr.smartassistant.service

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Sends the exact reviewed text through WhatsApp's notification reply PendingIntent. */
class ReplyApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val contact = intent.getStringExtra(EXTRA_CONTACT).orEmpty()
        val saved = if (android.os.Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_ACTION, Notification.Action::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_ACTION)

        // Prefer the newest live WhatsApp notification: the one captured earlier is often dead by now.
        val fresh = if (contact.isNotBlank()) WhatsAppNotificationListener.freshAction(contact) else null
        val ok = text.isNotBlank() && (
            (fresh != null && WhatsAppNotificationListener.sendReply(fresh, text, context)) ||
            (saved != null && WhatsAppNotificationListener.sendReply(saved, text, context))
        )

        com.khedr.smartassistant.data.AssistantStore(context).log(
            if (ok) "زر الموافقة: استُدعي رد واتساب" else "زر الموافقة: فشل (${WhatsAppNotificationListener.lastSendError ?: "لا يوجد إشعار حي لهذا الشخص"})"
        )
        if (ok) {
            Toast.makeText(context, "تم إرسال الرد", Toast.LENGTH_SHORT).show()
        } else {
            // WhatsApp's notification is gone (opened/dismissed): keep the reply so nothing is lost.
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("reply", text))
            Toast.makeText(context, "تعذّر الإرسال لأن إشعار واتساب أُغلق. نُسخ الرد؛ الصقه في المحادثة.", Toast.LENGTH_LONG).show()
        }
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        if (id != 0) context.getSystemService(NotificationManager::class.java)?.cancel(id)
    }
    companion object {
        const val EXTRA_TEXT = "reviewed_reply_text"
        const val EXTRA_ACTION = "whatsapp_reply_action"
        const val EXTRA_NOTIFICATION_ID = "approval_notification_id"
        const val EXTRA_CONTACT = "approval_contact"
    }
}
