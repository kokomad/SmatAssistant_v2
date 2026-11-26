package com.khedr.smartassistant

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.khedr.smartassistant.ai.GeminiFlashClient
import com.khedr.smartassistant.data.AssistantStore
import com.khedr.smartassistant.data.SecureApiKeyStore
import com.khedr.smartassistant.service.FloatingBubbleService
import com.khedr.smartassistant.service.ReplyComposer
import com.khedr.smartassistant.ui.AppTheme
import com.khedr.smartassistant.ui.ThemedBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val OkGreen = Color(0xFF4DE38A)
private val WarnAmber = Color(0xFFFFB74D)
private val ErrorRed = Color(0xFFFF6B6B)
private val CardColor = Color(0xBF000000)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent { AssistantApp() }
    }
}

@Composable
fun AssistantApp() {
    val ctx = LocalContext.current
    val store = remember { AssistantStore(ctx) }
    var theme by remember { mutableStateOf(AppTheme.fromId(store.themeId())) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = theme.colorScheme()) {
            Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
                Box(Modifier.fillMaxSize()) {
                    ThemedBackground(theme)
                    // soft scrim so text stays readable on top of the bright artwork
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x22000000), Color(0x66000000)))))
                    AssistantScreen(store = store, theme = theme, onTheme = { theme = it; store.setThemeId(it.id) })
                }
            }
        }
    }
}

private data class ChatMsg(val fromContact: Boolean, val text: String, val note: String? = null)

private enum class KeyUi { NONE, SAVED, BUSY, OK, ERROR }

@Composable
private fun AssistantScreen(store: AssistantStore, theme: AppTheme, onTheme: (AppTheme) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyStore = remember { SecureApiKeyStore(ctx) }
    val cs = MaterialTheme.colorScheme

    var enabled by remember { mutableStateOf(store.enabled()) }
    var auto by remember { mutableStateOf(store.autoSend()) }
    var confirmAuto by remember { mutableStateOf(false) }

    // ── Gemini key ──
    var apiKey by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(keyStore.hasKey()) }
    var keyUi by remember { mutableStateOf(if (keyStore.hasKey()) KeyUi.SAVED else KeyUi.NONE) }
    var keyMsg by remember { mutableStateOf(if (keyStore.hasKey()) "يوجد مفتاح محفوظ (مخفي)" else "لم يتم حفظ مفتاح Gemini") }

    var logText by remember { mutableStateOf(store.logText()) }

    // ── permissions ──
    var listenerOn by remember { mutableStateOf(false) }
    var overlayOn by remember { mutableStateOf(false) }
    var notifOn by remember { mutableStateOf(true) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        listenerOn = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        overlayOn = Settings.canDrawOverlays(ctx)
        notifOn = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        logText = store.logText()
        enabled = store.enabled() // the floating bubble can toggle this from outside the app
    }

    // ── contacts ──
    var contacts by remember { mutableStateOf(store.allowedContacts().toList().sorted()) }
    var selected by remember { mutableStateOf(contacts.firstOrNull().orEmpty()) }
    var profile by remember { mutableStateOf(selected.takeIf { it.isNotBlank() }?.let { store.profile(it) }.orEmpty()) }
    var canned by remember { mutableStateOf(selected.takeIf { it.isNotBlank() }?.let { store.replies(it).joinToString("\n") }.orEmpty()) }
    var newContact by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    fun select(name: String) {
        selected = name
        profile = if (name.isBlank()) "" else store.profile(name)
        canned = if (name.isBlank()) "" else store.replies(name).joinToString("\n")
        saved = false
    }

    // ── simulated chat ──
    val chat = remember { mutableStateListOf<ChatMsg>() }
    var chatInput by remember { mutableStateOf("") }
    var chatBusy by remember { mutableStateOf(false) }

    fun requestReply(text: String, history: List<String>) {
        chatBusy = true
        val lines = canned.lines().filter { it.isNotBlank() }
        val name = selected.ifBlank { "جهة اتصال" }
        val prof = profile
        scope.launch {
            val key = withContext(Dispatchers.IO) { keyStore.read() }
            var note: String? = null
            val reply: String
            if (key.isNullOrBlank()) {
                note = "بدون Gemini: رد جاهز من قائمتك"
                reply = ReplyComposer.compose(text, lines, prof)
            } else {
                val r = withContext(Dispatchers.IO) { GeminiFlashClient.generate(key, text, name, prof, lines, history) }
                if (r.success) {
                    reply = ReplyComposer.clean(r.text)
                } else {
                    note = "تعذّر Gemini: ${r.message}"
                    reply = ReplyComposer.compose(text, lines, prof)
                }
            }
            chat.add(ChatMsg(false, reply, note))
            chatBusy = false
        }
    }

    fun sendChat() {
        val text = chatInput.trim()
        if (text.isEmpty() || chatBusy) return
        val history = chat.map { (if (it.fromContact) "المرسل: " else "أنا: ") + it.text }.takeLast(10)
        chat.add(ChatMsg(true, text))
        chatInput = ""
        requestReply(text, history)
    }

    /** Re-asks Gemini for the last reply (used after a temporary server error). */
    fun retryLast() {
        if (chatBusy || chat.isEmpty()) return
        if (!chat.last().fromContact) chat.removeAt(chat.lastIndex)
        val last = chat.lastOrNull() ?: return
        if (!last.fromContact) return
        val history = chat.dropLast(1).map { (if (it.fromContact) "المرسل: " else "أنا: ") + it.text }.takeLast(10)
        requestReply(last.text, history)
    }

    // Ask for the notification permission once on Android 13+ (otherwise approval notifications never show).
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    if (confirmAuto) {
        AlertDialog(
            onDismissRequest = { confirmAuto = false },
            title = { Text("تفعيل الإرسال التلقائي؟") },
            text = { Text("سيرسل المساعد الردود إلى جهات الاتصال المسموح بها دون مراجعتك. قد يخطئ النموذج في فهم الرسالة. يُنصح بتجربة وضع الموافقة أولاً.") },
            confirmButton = { TextButton(onClick = { auto = true; store.setAutoSend(true); confirmAuto = false }) { Text("تفعيل") } },
            dismissButton = { TextButton(onClick = { confirmAuto = false }) { Text("إلغاء") } }
        )
    }

    val sys = WindowInsets.systemBars.asPaddingValues()
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = sys.calculateTopPadding() + 8.dp,
            bottom = sys.calculateBottomPadding() + 24.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ───── header ─────
        item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = cs.primary) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = cs.onPrimary, modifier = Modifier.padding(10.dp))
                    }
                    Column {
                        Text("مساعد واتساب الذكي", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("ردود ذكية بإشرافك • Gemini Flash", style = MaterialTheme.typography.bodySmall, color = Color(0xCCFFFFFF))
                    }
                }
            }
        }

        // ───── readiness ─────
        item {
            GlassCard("الجاهزية", Icons.Rounded.Tune) {
                StatusRow("مفتاح Gemini", hasKey, if (hasKey) "محفوظ" else "غير محفوظ", null, null)
                StatusRow("قراءة إشعارات واتساب", listenerOn, if (listenerOn) "مفعّلة" else "غير ممنوحة", "منح") {
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                StatusRow("الظهور فوق التطبيقات", overlayOn, if (overlayOn) "مسموح" else "غير ممنوح", "منح") {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                }
                StatusRow("إشعارات التطبيق", notifOn, if (notifOn) "مفعّلة" else "معطّلة", "تفعيل") {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }
            }
        }

        // ───── diagnostics ─────
        item {
            GlassCard("سجل الإشعارات (للتشخيص)", Icons.Rounded.Tune) {
                Text(
                    if (logText.isBlank()) "لا توجد أحداث بعد. أرسل رسالة من جهة اتصال مضافة وارجع هنا لترى ماذا استقبل المساعد وماذا قرر."
                    else logText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (logText.isBlank()) Color(0x99FFFFFF) else Color.White,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { logText = store.logText() }) { Text("تحديث") }
                    TextButton(onClick = { store.clearLog(); logText = "" }) { Text("مسح السجل") }
                }
            }
        }

        // ───── Gemini key ─────
        item {
            GlassCard("إعداد Gemini", Icons.Rounded.VpnKey) {
                Text("الصق مفتاح Google AI Studio. يُختبر الاتصال أولاً ثم يُحفظ المفتاح مشفراً على الجهاز.", style = MaterialTheme.typography.bodySmall, color = Color(0xCCFFFFFF))
                OutlinedTextField(
                    apiKey, { apiKey = it },
                    label = { Text("Gemini API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    keyMsg,
                    color = when (keyUi) { KeyUi.OK, KeyUi.SAVED -> OkGreen; KeyUi.ERROR -> ErrorRed; else -> Color(0xCCFFFFFF) },
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = keyUi != KeyUi.BUSY && apiKey.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        onClick = {
                            val candidate = apiKey.trim()
                            keyUi = KeyUi.BUSY; keyMsg = "جارٍ فحص المفتاح..."
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { GeminiFlashClient.test(candidate) }
                                // 503/429 mean the request reached Google (a bad key fails with 400/403), so keep the key.
                                val busy = !result.success && result.message.take(3) in setOf("503", "429")
                                if (result.success || busy) {
                                    withContext(Dispatchers.IO) { keyStore.save(candidate) }
                                    apiKey = ""; hasKey = true; keyUi = KeyUi.OK
                                    keyMsg = if (busy) "حُفظ المفتاح، لكن خوادم Gemini مشغولة الآن؛ جرّب المحادثة التجريبية بعد قليل."
                                    else "نجح الاتصال؛ تم حفظ المفتاح بأمان."
                                } else { keyUi = KeyUi.ERROR; keyMsg = result.message }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (keyUi == KeyUi.BUSY) "جارٍ الفحص" else "فحص وحفظ") }
                    OutlinedButton(
                        enabled = keyUi != KeyUi.BUSY && hasKey,
                        shape = RoundedCornerShape(14.dp),
                        onClick = { keyStore.clear(); apiKey = ""; hasKey = false; keyUi = KeyUi.NONE; keyMsg = "تم حذف المفتاح المحفوظ." },
                        modifier = Modifier.weight(1f)
                    ) { Text("حذف المفتاح") }
                }
                Text("النموذج: ${GeminiFlashClient.MODEL} • يحتاج مفتاحاً صالحاً وحصة API متاحة.", style = MaterialTheme.typography.labelSmall, color = Color(0x99FFFFFF))
            }
        }

        // ───── controls ─────
        item {
            GlassCard("التحكم بالمساعد", Icons.Rounded.Notifications) {
                SwitchRow(enabled, if (enabled) "المساعد يعمل" else "المساعد متوقف", null) { enabled = it; store.setEnabled(it) }
                SwitchRow(
                    auto,
                    if (auto) "إرسال تلقائي" else "طلب موافقة قبل الإرسال",
                    if (auto) "قد يرسل ردوداً دون مراجعة" else "يصلك إشعار بالرد المقترح لتوافق عليه"
                ) { if (it) confirmAuto = true else { auto = false; store.setAutoSend(false) } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        shape = RoundedCornerShape(14.dp),
                        onClick = {
                            if (!Settings.canDrawOverlays(ctx)) ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                            else ctx.startService(Intent(ctx, FloatingBubbleService::class.java))
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("تشغيل الأيقونة العائمة") }
                    OutlinedButton(
                        shape = RoundedCornerShape(14.dp),
                        onClick = { ctx.stopService(Intent(ctx, FloatingBubbleService::class.java)) },
                        modifier = Modifier.weight(1f)
                    ) { Text("إيقافها") }
                }
            }
        }

        // ───── theme picker ─────
        item {
            GlassCard("المظهر", Icons.Rounded.Palette) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppTheme.entries.forEach { t ->
                        ThemeSwatch(t, t == theme, Modifier.weight(1f)) { onTheme(t) }
                    }
                }
            }
        }

        // ───── contacts ─────
        item {
            GlassCard("جهات الاتصال المسموح بها", Icons.Rounded.Person) {
                OutlinedTextField(
                    newContact, { newContact = it },
                    label = { Text("اسم جهة الاتصال كما يظهر في واتساب") },
                    singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = newContact.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    onClick = {
                        val n = newContact.trim()
                        if (n.isNotEmpty()) {
                            store.addContact(n); contacts = store.allowedContacts().toList().sorted(); newContact = ""; select(n)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("إضافة جهة اتصال") }
                if (contacts.isEmpty()) Text("لا توجد جهات اتصال بعد. المساعد لا يرد إلا على من تضيفهم هنا.", style = MaterialTheme.typography.bodySmall, color = Color(0xCCFFFFFF))
            }
        }
        items(contacts, key = { it }) { name ->
            Surface(
                shape = RoundedCornerShape(16.dp), color = CardColor,
                border = BorderStroke(1.dp, if (selected == name) cs.primary else cs.primary.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth().clickable { select(name) }
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == name, { select(name) })
                    Text(name, Modifier.weight(1f), fontWeight = if (selected == name) FontWeight.Bold else FontWeight.Normal)
                    IconButton(onClick = {
                        store.removeContact(name); contacts = store.allowedContacts().toList().sorted()
                        if (selected == name) select(contacts.firstOrNull().orEmpty())
                    }) { Icon(Icons.Rounded.Delete, "حذف", tint = ErrorRed) }
                }
            }
        }
        if (selected.isNotBlank()) {
            item {
                GlassCard("ملف $selected", Icons.Rounded.Person) {
                    OutlinedTextField(profile, { profile = it; saved = false }, label = { Text("العلاقة والأسلوب والمعلومات") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(), minLines = 3)
                    OutlinedTextField(canned, { canned = it; saved = false }, label = { Text("رد جاهز في كل سطر") }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(), minLines = 4)
                    Button(
                        shape = RoundedCornerShape(14.dp),
                        onClick = { store.setProfile(selected, profile); store.setReplies(selected, canned.lines().filter { it.isNotBlank() }); saved = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (saved) "تم الحفظ ✓" else "حفظ بيانات جهة الاتصال") }
                }
            }
        }

        // ───── simulated chat ─────
        item {
            GlassCard("محادثة تجريبية", Icons.Rounded.AutoAwesome) {
                Text(
                    "أنت هنا تلعب دور " + (if (selected.isNotBlank()) selected else "جهة الاتصال") +
                        ". اكتب رسالة وسيرد المساعد بالأسلوب نفسه الذي سيرد به على واتساب.",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xCCFFFFFF)
                )
                Text(
                    if (auto) "الوضع الحالي: إرسال تلقائي — الرد يُرسل مباشرة"
                    else "الوضع الحالي: موافقة — يصلك الرد في إشعار لتوافق عليه",
                    style = MaterialTheme.typography.labelSmall, color = OkGreen
                )
                Surface(shape = RoundedCornerShape(16.dp), color = Color(0x99000000), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (chat.isEmpty()) {
                            Text("لا رسائل بعد. جرّب: السلام عليكم، كيف حالك؟", style = MaterialTheme.typography.bodySmall, color = Color(0x99FFFFFF), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                        chat.forEach { ChatBubble(it, selected.ifBlank { "جهة الاتصال" }) }
                        if (chatBusy) Text("المساعد يكتب…", style = MaterialTheme.typography.labelSmall, color = cs.primary)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        chatInput, { chatInput = it },
                        placeholder = { Text("اكتب رسالة…") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 3,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        enabled = !chatBusy && chatInput.isNotBlank(),
                        shape = RoundedCornerShape(24.dp),
                        onClick = { sendChat() }
                    ) { Icon(Icons.AutoMirrored.Rounded.Send, "إرسال") }
                }
                if (!chatBusy && chat.lastOrNull()?.note?.startsWith("تعذّر") == true) {
                    Button(onClick = { retryLast() }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) { Text("أعد المحاولة بـ Gemini") }
                }
                if (chat.isNotEmpty()) TextButton(onClick = { chat.clear() }) { Text("مسح المحادثة") }
            }
        }

        // ───── notes ─────
        item {
            Text(
                "الإرسال التلقائي يعتمد على توفر إجراء الرد السريع في إشعار واتساب، وقد لا يعمل على كل الأجهزة.",
                style = MaterialTheme.typography.bodySmall, color = Color(0x99FFFFFF), textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ───── developer credit ─────
        item { DeveloperCredit() }
    }
}

// ───────────────────────── building blocks ─────────────────────────

@Composable
private fun GlassCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = CardColor,
        border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, tint = cs.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, state: String, actionLabel: String?, onAction: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = if (ok) OkGreen else WarnAmber)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(state, style = MaterialTheme.typography.labelSmall, color = if (ok) OkGreen else WarnAmber)
        }
        if (!ok && actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(12.dp),
            ) { Text(actionLabel) }
        }
    }
}

@Composable
private fun SwitchRow(checked: Boolean, label: String, sub: String?, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = Color(0xB3FFFFFF))
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun ThemeSwatch(t: AppTheme, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.56f).clip(shape)
                .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color(0x55FFFFFF), shape)
        ) {
            ThemedBackground(t, Modifier.fillMaxSize())
            if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp))
        }
        Text(t.title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DeveloperCredit() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = CardColor,
        border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Rounded.Code, null, tint = cs.primary)
            Text("تطوير", style = MaterialTheme.typography.labelMedium, color = Color(0xB3FFFFFF))
            Text(Developer.NAME_AR, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.primary)
            Text(Developer.NAME_EN, style = MaterialTheme.typography.bodyMedium)
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                    try { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Developer.EMAIL}"))) } catch (_: ActivityNotFoundException) { }
                }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Rounded.Email, null, tint = cs.primary)
                Text(Developer.EMAIL, style = MaterialTheme.typography.bodyMedium, color = cs.primary)
            }
        }
    }
}

@Composable
private fun ChatBubble(m: ChatMsg, contactName: String) {
    val cs = MaterialTheme.colorScheme
    val mine = !m.fromContact
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.fillMaxWidth(0.84f),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                if (mine) "المساعد (ردّك)" else contactName,
                style = MaterialTheme.typography.labelSmall,
                color = if (mine) cs.primary else Color(0xB3FFFFFF)
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (mine) cs.primary else Color(0xFF263238)
            ) {
                Text(
                    m.text,
                    color = if (mine) cs.onPrimary else Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            if (m.note != null) Text(m.note, style = MaterialTheme.typography.labelSmall, color = WarnAmber)
        }
    }
}
