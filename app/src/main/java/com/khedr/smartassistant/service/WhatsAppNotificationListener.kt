package com.khedr.smartassistant.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.khedr.smartassistant.ai.GeminiFlashClient
import com.khedr.smartassistant.data.AssistantStore
import com.khedr.smartassistant.data.SecureApiKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Reads notification previews and exposes WhatsApp's inline reply action when available. */
class WhatsAppNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** WhatsApp re-posts the same notification several times; remember what was already handled. */
    private val handled = object : LinkedHashMap<String, Long>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 60
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Never let one odd notification kill the service: every failure is caught and logged.
        try { handle(sbn) } catch (e: Exception) {
            AssistantStore(this).log("خطأ داخلي: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.whatsapp" && sbn.packageName != "com.whatsapp.w4b") return
        val n = sbn.notification
        // Group summaries ("3 new messages") carry no reply action and no real message.
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val store = AssistantStore(this)
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var message = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (message.isBlank()) message = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val preview = message.replace("\n", " ").take(40)

        if (!store.enabled()) { store.log("تجاهل (المساعد متوقف): $title — $preview"); return }
        if (title.isBlank() || message.isBlank()) { store.log("تجاهل (عنوان/نص فارغ): \"$title\""); return }

        // WhatsApp appends counters such as "Name (3 messages)" to the title.
        val cleanTitle = title.replace(Regex("\\s*\\(\\d+[^)]*\\)\\s*$"), "").trim()
        val allowed = store.allowedContacts()
        val contact = allowed.firstOrNull { it.equals(cleanTitle, ignoreCase = true) }
            ?: allowed.firstOrNull { cleanTitle.startsWith(it, ignoreCase = true) }
            ?: run { store.log("تجاهل (ليس في القائمة): \"$title\""); return }

        if (n.category == Notification.CATEGORY_CALL || isCallOrMedia(message)) {
            store.log("تجاهل (مكالمة/وسائط): $contact — $preview"); return
        }

        val dedupeKey = "${sbn.key}|${message.hashCode()}"
        synchronized(handled) {
            if (handled.containsKey(dedupeKey)) return
            handled[dedupeKey] = System.currentTimeMillis()
        }
        val actions = n.actions
        val hasReply = actions?.any { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } == true
        store.log("قراءة رسالة من $contact: $preview | أزرار الإشعار: ${actions?.size ?: 0} | رد سريع: ${if (hasReply) "متوفر" else "غير متوفر"}")

        scope.launch {
            try {
                val canned = store.replies(contact)
                val profile = store.profile(contact)
                val key = SecureApiKeyStore(this@WhatsAppNotificationListener).read()
                val ai = if (!key.isNullOrBlank()) GeminiFlashClient.generate(key, message, contact, profile, canned) else null
                if (ai != null && !ai.success) store.log("Gemini فشل: ${ai.message}")
                if (key.isNullOrBlank()) store.log("لا يوجد مفتاح Gemini؛ سيُستخدم رد جاهز")
                val proposed = if (ai?.success == true) ReplyComposer.clean(ai.text) else ReplyComposer.compose(message, canned, profile)
                if (proposed.isBlank()) return@launch
                // Auto-send only a successfully generated AI response; otherwise (or if sending is
                // impossible) fall back to the approval notification so nothing is lost.
                val sent = store.autoSend() && ai?.success == true && replyInline(sbn, proposed)
                if (store.autoSend() && !sent) store.log("فشل الإرسال التلقائي: ${lastSendError ?: "لا يوجد إجراء رد سريع في الإشعار"}")
                if (sent) store.log("استُدعي رد واتساب تلقائياً: ${proposed.take(60)}")
                else {
                    ReplyReviewNotifier.show(this@WhatsAppNotificationListener, contact, message, proposed, sbn)
                    store.log("إشعار موافقة: ${proposed.take(60)}")
                }
            } catch (e: Exception) {
                store.log("خطأ أثناء المعالجة: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            }
        }
    }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    private fun replyInline(sbn: StatusBarNotification, text: String): Boolean {
        val actions = sbn.notification.actions ?: return false
        val action = actions.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } ?: return false
        return sendReply(action, text, this)
    }

    /** Calls, photos, videos, voice notes, stickers: WhatsApp shows only a placeholder, so there is nothing to answer. */
    private fun isCallOrMedia(m: String): Boolean {
        if (m.codePointAt(0) in setOf(0x1F4F7, 0x1F3A5, 0x1F3A4, 0x1F3B5, 0x1F4C4, 0x1F5BC, 0x1F3AC, 0x1F4CD, 0x1F464, 0x1F4DE, 0x1F4F9)) return true
        if (m.startsWith("تم إرسال") || m.startsWith("تم ارسال")) return true
        if (listOf("مكالمة", "اتصال فائت", "Missed voice call", "Missed video call", "Incoming voice call", "Incoming video call", "Ongoing voice call", "Ongoing video call")
                .any { m.contains(it, ignoreCase = true) }) return true
        if (m.length <= 25 && listOf("صورة", "فيديو", "مقطع فيديو", "رسالة صوتية", "ملصق", "Photo", "Video", "Voice message", "Audio", "Sticker", "GIF", "Document")
                .any { m.equals(it, ignoreCase = true) || m.startsWith("$it ", ignoreCase = true) && m.length <= 14 }) return true
        return false
    }

    companion object {
        @Volatile private var instance: WhatsAppNotificationListener? = null

        /** The newest live WhatsApp notification of this contact that still has a reply action (older ones may be dead). */
        fun freshAction(contact: String): Notification.Action? {
            val svc = instance ?: return null
            return try {
                svc.activeNotifications
                    .filter { it.packageName == "com.whatsapp" || it.packageName == "com.whatsapp.w4b" }
                    .filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }
                    .filter { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().equals(contact, ignoreCase = true) }
                    .sortedByDescending { it.postTime }
                    .firstNotNullOfOrNull { n -> n.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true } }
            } catch (_: Exception) { null }
        }

        @Volatile var lastSendError: String? = null

        fun sendReply(action: Notification.Action, text: String, context: android.content.Context? = null): Boolean {
            lastSendError = null
            val input = action.remoteInputs?.firstOrNull { it.allowFreeFormInput }
            if (input == null) { lastSendError = "لا يوجد حقل رد في الإجراء"; return false }
            val intent = Intent()
            val results = android.os.Bundle().apply { putCharSequence(input.resultKey, text) }
            RemoteInput.addResultsToIntent(arrayOf(input), intent, results)
            if (android.os.Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            return try {
                action.actionIntent.send(context, 0, intent); true
            } catch (e: Exception) {
                lastSendError = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"; false
            }
        }
    }
}

/** Reply cleanup and canned fallback used when Gemini is not configured or unavailable. */
object ReplyComposer {
    fun clean(text: String): String = text.trim()
        .removeSurrounding("\"").removeSurrounding("«", "»").removeSurrounding("“", "”")
        .replace(Regex("[\\t ]{2,}"), " ")
        .replace(Regex("([😂🤣])\\1{2,}"), "$1")
        .replace(Regex("\\n{3,}"), "\n\n").trim().take(1200)

    fun compose(message: String, canned: List<String>, profile: String): String {
        val clean = message.lowercase()
        val selected = when {
            clean.contains("السلام") -> canned.firstOrNull { it.contains("وعليكم") || it.contains("سلام") }
            clean.contains("كيفك") || clean.contains("كيف حالك") -> canned.firstOrNull { it.contains("الحمد") || it.contains("تمام") }
            clean.contains("شكرا") || clean.contains("شكراً") -> canned.firstOrNull { it.contains("عفو") || it.contains("أهلا") }
            else -> null
        }
        // No invented personal commitments: an unrelated canned line (e.g. "on my way") must never be
        // sent just because it is first in the list, so without a keyword match the reply stays neutral.
        return selected ?: "وصلتني رسالتك، سأرد عليك قريباً."
    }
}
