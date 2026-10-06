package app.nya.password.autofill

import android.content.Context
import androidx.core.content.edit
import app.nya.password.core.PlainJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * The last autofill requests and what the service did with them, shown in the
 * setup guide so a silent failure can be told apart without adb. Keeps the
 * calling app / domain and field counts only, never what is in the fields.
 */
object FillLog {
    @Serializable
    data class Entry(
        val at: Long,
        val pkg: String = "",
        val domain: String? = null,
        /** e.g. "8 个输入框：用户名 1 · 密码 1 · 验证码 0". */
        val fields: String = "",
        /** What the keyboard asked for (inline suggestions), empty when nothing. */
        val inline: String = "",
        val outcome: String,
        val ms: Long = 0,
        /** One line per fillable view: class, input type, id, hint, hints, HTML attributes (no values). */
        val views: String = "",
    )

    private const val MAX = 20
    private const val KEY = "entries"
    private val serializer = ListSerializer(Entry.serializer())

    private fun prefs(context: Context) = context.getSharedPreferences("autofill_log", Context.MODE_PRIVATE)

    @Synchronized
    fun read(context: Context): List<Entry> = runCatching {
        PlainJson.decodeFromString(serializer, prefs(context).getString(KEY, null) ?: return emptyList())
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(context: Context, e: Entry) {
        val list = (listOf(e) + read(context)).take(MAX)
        prefs(context).edit { putString(KEY, PlainJson.encodeToString(serializer, list)) }
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit { remove(KEY) }
    }

    fun fields(screen: ParsedScreen): String {
        val c = screen.classification
        return "共 ${screen.nodeCount} 个节点，${screen.views.size} 个可填视图：用户名 ${c.username.size}${if (c.guessed) "（猜测）" else ""} · 密码 ${c.password.size} · 验证码 ${c.otp.size}"
    }

    /** What the classifier saw of each view; the field contents themselves are left out. */
    fun views(screen: ParsedScreen, max: Int = 30): String = screen.views.take(max).joinToString("\n") { v ->
        val role = screen.classification.roleOf(v.index)?.name?.lowercase() ?: "-"
        buildList {
            add("#${v.index} $role")
            add(v.className?.substringAfterLast('.') ?: "?")
            add("input=0x%x".format(v.inputType))
            v.idEntry?.let { add("id=${it.take(40)}") }
            v.hint?.let { add("hint=\"${it.take(40)}\"") }
            // only the length: tells a placeholder reported as text (compatibility mode) from an empty field
            screen.values.getOrNull(v.index)?.let { add("text=${it.length}字") }
            v.contentDescription?.let { add("desc=\"${it.take(40)}\"") }
            if (v.autofillHints.isNotEmpty()) add("hints=${v.autofillHints.joinToString(",")}")
            v.htmlTag?.let { add("html=$it") }
            for (k in listOf("type", "name", "id", "autocomplete")) v.htmlAttrs[k]?.let { add("$k=${it.take(40)}") }
            if (!v.visible) add("invisible")
            if (v.autofillType != 1) add("type=${v.autofillType}")
            if (v.focused) add("focused")
        }.joinToString(" ")
    } + if (screen.views.size > max) "\n…" else ""

    /** The whole log as text, to paste into a bug report. */
    fun text(entries: List<Entry>): String = entries.joinToString("\n\n") { e ->
        listOf(
            "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(e.at))} ${e.pkg} ${e.domain.orEmpty()}",
            e.outcome,
            listOf(e.fields, e.inline, "${e.ms} ms").filter { it.isNotBlank() }.joinToString(" · "),
            e.views,
        ).filter { it.isNotBlank() }.joinToString("\n")
    }
}
