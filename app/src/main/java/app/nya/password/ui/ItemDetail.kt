package app.nya.password.ui

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nya.password.MainActivity
import app.nya.password.core.Clipboard
import app.nya.password.core.Content
import app.nya.password.core.ItemField
import app.nya.password.core.ItemDoc
import app.nya.password.core.ItemView
import app.nya.password.core.Reprompt
import app.nya.password.core.RevisionInfo
import app.nya.password.core.Vault
import app.nya.password.core.decode
import app.nya.password.core.errorText
import app.nya.password.vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val MATCH_LABEL = mapOf(
    "domain" to "域名匹配", "host" to "主机匹配", "starts_with" to "前缀匹配", "exact" to "完全匹配", "regex" to "正则", "never" to "从不填写",
)

fun dateTime(ms: Long): String = if (ms <= 0) "—" else SimpleDateFormat("yyyy/M/d HH:mm", Locale.CHINA).format(Date(ms))

fun bytes(n: Long): String = sizeText(n)

/** Copies and tells the user (secrets: sensitive, cleared after 90 s). */
fun copy(v: Vault, context: android.content.Context, text: String, label: String, secret: Boolean) {
    Clipboard.copy(context, text, secret)
    v.say(if (secret) "已复制$label，90 秒后清除" else "已复制$label")
}

@Composable
fun PageTop(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemDetailScreen(activity: MainActivity, vaultId: String, itemId: String) {
    val v = activity.vault
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var view by remember { mutableStateOf<ItemView?>(null) }
    var missing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(false) }
    var conflicts by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<Pair<String, String>?>(null) }

    // opening this item ends a verification of another one ("使用前需要验证")
    LaunchedEffect(vaultId, itemId) { v.opened(vaultId, itemId) }
    LaunchedEffect(v.version) {
        view = runCatching { v.item(vaultId, itemId) }.getOrElse {
            missing = true
            null
        }
    }

    val saveAttachment = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val (attId, _) = saving ?: return@rememberLauncherForActivityResult
        saving = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val data = v.call { it.attachment(vaultId, itemId, attId) }
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(data) } }
                data.fill(0)
                v.say("已保存")
            } catch (e: Exception) {
                v.say(errorText(e))
            }
        }
    }

    val item = view
    if (item == null) {
        Column(Modifier.fillMaxSize()) {
            PageTop("", activity::back)
            if (missing) Text("找不到这个条目", Modifier.padding(24.dp), color = muted)
        }
        return
    }
    val c = remember(item) { item.content?.let { Content.of(it) } } ?: return
    val tpl = remember { v.templatesNow() }.find { it.id == item.template }
    // secrets, copying, history and editing stay hidden until the user verifies
    val locked = v.gated(item.reprompt, vaultId, itemId)

    fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                v.say(errorText(e))
            }
        }
    }

    fun toggle(key: String) = act {
        val doc = ItemDoc(item.content!!)
        val next = if (key == "favorite") doc.withFavorite(!c.favorite) else doc.withArchived(!c.archived)
        v.call { it.saveItem(vaultId, itemId, next.toJson()) }
        v.say(if (key == "favorite") (if (!c.favorite) "已收藏" else "已取消收藏") else if (!c.archived) "已归档" else "已取消归档")
        v.edited()
    }

    Column(Modifier.fillMaxSize()) {
        PageTop(item.title.ifBlank { "（无标题）" }, activity::back) {
            if (!item.deleted) {
                IconButton(onClick = { toggle("favorite") }) {
                    Icon(if (c.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, "收藏", tint = if (c.favorite) Warn else muted)
                }
                if (!locked) {
                    IconButton(onClick = { activity.open(Route.Edit(vaultId, itemId, item.template)) }, enabled = !item.readOnly) {
                        Icon(Icons.Filled.Edit, "编辑")
                    }
                }
            }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
            DropdownMenu(menu, { menu = false }) {
                if (item.deleted) {
                    DropdownMenuItem({ Text("恢复") }, onClick = {
                        menu = false
                        act {
                            v.call { it.restoreItem(vaultId, itemId) }
                            v.say("已从回收站恢复")
                            v.edited()
                        }
                    })
                    DropdownMenuItem({ Text("永久删除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirm = "purge" })
                } else {
                    DropdownMenuItem({ Text(if (c.archived) "取消归档" else "归档") }, onClick = { menu = false; toggle("archived") })
                    if (!locked) DropdownMenuItem({ Text("历史版本") }, onClick = { menu = false; history = true })
                    DropdownMenuItem({ Text("移到回收站", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirm = "delete" })
                }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(item.title, item.template, 48)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title.ifBlank { "（无标题）" }, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${TEMPLATE_GLYPH[item.template] ?: ""} ${tpl?.label ?: item.template} · ${v.vaults.find { it.id == vaultId }?.name ?: ""}",
                        fontSize = 13.sp, color = muted,
                    )
                }
            }
            if (c.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { c.tags.forEach { Chip("#$it") } }
            }
            if (item.readOnly) Banner("这个条目由更新版本的 NyaPassword 写入，此版本只能查看。更新应用后即可编辑。")
            item.rejected?.let { Banner("服务器拒绝了对这个条目的修改：$it。你的修改仍保存在本设备上。", error = true) }
            if (c.conflicts.isNotEmpty()) {
                Banner("同步冲突：${c.conflicts.size} 处在不同设备上被改成了不同的值。两个值都已保留，请选择要用哪个。") {
                    if (!locked) TextButton(onClick = { conflicts = true }) { Text("逐项处理") }
                }
            }

            if (locked) {
                UnlockPanel(activity, "此条目需要验证", "查看、复制这个条目的密码等内容前，请验证身份", verify = true) {
                    v.verifiedItem = Reprompt.key(vaultId, itemId)
                }
                if (item.subtitle.isNotBlank()) Group { Field(item.subtitle, "摘要", divider = false) }
                val hidden = listOfNotNull(
                    "通行密钥 ${c.passkeys.size} 个".takeIf { c.passkeys.isNotEmpty() },
                    "附件 ${c.attachments.size} 个".takeIf { c.attachments.isNotEmpty() },
                    "备注".takeIf { c.notes.isNotEmpty() },
                )
                if (hidden.isNotEmpty()) Text("${hidden.joinToString(" · ")}：验证后显示", fontSize = 12.5.sp, color = muted)
            }

            // fields, grouped by section
            val visible = if (locked) emptyList() else c.fields.filter { !it.isEmpty }
            val known = c.sections.map { it.id }.toSet()
            val groups = buildList {
                add("" to visible.filter { it.section == null })
                c.sections.forEach { s -> add(s.label to visible.filter { it.section == s.id }) }
                visible.filter { it.section != null && it.section !in known }.takeIf { it.isNotEmpty() }?.let { add("其他" to it) }
            }.filter { it.second.isNotEmpty() }
            groups.forEach { (label, fields) ->
                Group(title = label.ifEmpty { null }) {
                    fields.forEachIndexed { i, f -> FieldRow(v, f, divider = i > 0 || label.isNotEmpty()) }
                }
            }

            if (c.passkeys.isNotEmpty() && !locked) {
                Group(title = "通行密钥") {
                    c.passkeys.forEach { p ->
                        Field(p.rpId, listOf(p.userName.ifEmpty { p.userDisplayName }, p.createdAt.takeIf { it > 0 }?.let { "创建于 ${dateTime(it)}" }).filterNotNull().filter { it.isNotEmpty() }.joinToString(" · ")) {
                            Chip("passkey", ChipKind.OK)
                        }
                    }
                }
            }

            if (c.urls.isNotEmpty()) {
                Group(title = "网站与应用") {
                    c.urls.forEach { u ->
                        val app = u.url.startsWith("androidapp://")
                        Field(
                            if (app) "📱 ${u.url.removePrefix("androidapp://")}" else u.url,
                            MATCH_LABEL[u.match] ?: u.match,
                        ) {
                            if (app && u.certSha256.isNotEmpty()) {
                                Chip("签名已记录", ChipKind.OK)
                            } else if (!app) {
                                TextButton(onClick = {
                                    val url = if (Regex("^https?:", RegexOption.IGNORE_CASE).containsMatchIn(u.url)) u.url else "https://${u.url}"
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                                }) { Text("打开") }
                            }
                        }
                    }
                }
            }

            if (c.notes.isNotEmpty() && !locked) {
                Group(title = "备注") {
                    Text(c.notes, Modifier.padding(16.dp), fontSize = 14.5.sp)
                }
            }

            if (c.attachments.isNotEmpty() && !locked) {
                Group(title = "附件") {
                    c.attachments.forEach { a ->
                        Field("📎 ${a.name}", bytes(a.size)) {
                            OutlinedButton(onClick = {
                                saving = a.id to a.name
                                saveAttachment.launch(a.name)
                            }) { Text("下载") }
                        }
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("修改于 ${dateTime(c.updatedAt)}", fontSize = 12.sp, color = muted)
                Text("创建于 ${dateTime(c.createdAt)}", fontSize = 12.sp, color = muted)
                if (item.pending) Chip("尚未同步")
                if (locked) {
                    Chip("使用前需要验证")
                } else {
                    Text(
                        "历史版本${if (item.revision > 0) "（${item.revision}）" else ""}",
                        Modifier.clickable { history = true },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (c.history.isNotEmpty()) Text("密码历史 ${c.history.size} 条", fontSize = 12.sp, color = muted)
                Text("格式 ${c.format}", fontSize = 12.sp, color = muted)
            }
        }
    }

    when (confirm) {
        "delete" -> ConfirmDialog("移到回收站？", "“${item.title}”会移到回收站，可以随时恢复。", "移到回收站", true, { confirm = null }) {
            confirm = null
            act {
                v.call { it.deleteItem(vaultId, itemId) }
                v.edited()
                activity.back()
            }
        }
        "purge" -> ConfirmDialog("永久删除？", "“${item.title}”及其全部历史版本和附件会从服务器上删除，无法恢复（备份里还有）。", "永久删除", true, { confirm = null }) {
            confirm = null
            act {
                v.call { it.purge(vaultId, listOf(itemId)) }
                v.say("已永久删除")
                v.edited()
                activity.back()
            }
        }
    }
    if (history && !locked) HistoryDialog(v, vaultId, itemId, c) { history = false }
    if (conflicts && !locked) ConflictsDialog(v, vaultId, itemId, c) { conflicts = false }
}

/** One field: label, value (hidden for secrets), reveal / copy, per-line copy for multi-line secrets, live TOTP. */
@Composable
fun FieldRow(v: Vault, f: ItemField, divider: Boolean) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(false) }
    val text = f.text
    val multi = f.multiline || f.kind == "multiline"
    val lineList = text.split('\n').filter { it.isNotBlank() }
    Column(Modifier.fillMaxWidth()) {
        if (divider) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(f.label.ifBlank { f.id }, fontSize = 12.5.sp, color = muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    when {
                        f.kind == "totp" -> TotpCode(v, text)
                        f.secret && !shown -> Text(
                            "••••••••••••" + if (multi && lineList.size > 1) "（${lineList.size} 行，已隐藏）" else "",
                            fontFamily = FontFamily.Monospace, fontSize = 15.sp,
                        )
                        multi -> Text(text, fontFamily = if (f.secret) FontFamily.Monospace else FontFamily.Default, fontSize = 14.5.sp)
                        f.kind == "url" && text.startsWith("http") -> Text(
                            text,
                            Modifier.clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, text.toUri())) } },
                            color = MaterialTheme.colorScheme.primary, fontSize = 15.sp,
                        )
                        f.kind == "reference" -> Chip("引用：$text")
                        f.kind == "boolean" -> Text(if (text == "true") "是" else "否", fontSize = 15.sp)
                        else -> Text(text, fontFamily = if (f.secret) FontFamily.Monospace else FontFamily.Default, fontSize = 15.sp)
                    }
                }
                if (f.secret) {
                    IconButton(onClick = { shown = !shown }) {
                        Icon(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (shown) "隐藏" else "显示", tint = muted)
                    }
                }
                if (f.kind != "totp") {
                    IconButton(onClick = { copy(v, context, text, f.label, f.secret) }) { Icon(Icons.Filled.ContentCopy, if (multi) "全部复制" else "复制", tint = muted) }
                }
            }
            if (multi && f.secret && lineList.size > 1) {
                TextButton(onClick = { lines = true }, contentPadding = PaddingValues(0.dp)) { Text("按行复制") }
            }
        }
    }
    if (lines) {
        AlertDialog(
            onDismissRequest = { lines = false },
            title = { Text(f.label) },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(lineList.withIndex().toList()) { (i, l) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (shown) l else "••••••", Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                            TextButton(onClick = { copy(v, context, l, "第 ${i + 1} 行", true) }) { Text("复制") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { lines = false }) { Text("完成") } },
            dismissButton = { TextButton(onClick = { shown = !shown }) { Text(if (shown) "隐藏" else "显示") } },
        )
    }
}

/** The current one-time code with a countdown; tap to copy. */
@Composable
fun TotpCode(v: Vault, uri: String) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(uri) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    val code = remember(uri, now / 1000) { v.otp(uri) }
    if (code == null) {
        Text("无效的一次性密码设置", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { copy(v, context, code.code, "一次性密码", true) }) {
        Text(code.code.chunked(3).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        CircularProgressIndicator(
            progress = { code.remaining / code.period.coerceAtLeast(1).toFloat() },
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.5.dp,
            color = if (code.remaining <= 5) Danger else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Text("${code.remaining}s", fontSize = 12.sp, color = muted)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.ContentCopy, "复制", Modifier.size(18.dp), tint = muted)
    }
}

private fun show(e: JsonElement): String = when (e) {
    JsonNull -> "（空）"
    is JsonPrimitive -> e.contentOrNull ?: ""
    else -> e.toString()
}

/** Revisions on the server (view / restore) and the password history (the web vault's HistoryModal). */
@Composable
fun HistoryDialog(v: Vault, vaultId: String, itemId: String, current: Content, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revisions by remember { mutableStateOf<List<RevisionInfo>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Pair<Long, Content>?>(null) }
    var showPw by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        try {
            revisions = decode(v.call { it.itemHistory(vaultId, itemId) })
        } catch (e: Exception) {
            error = "需要联网才能查看历史版本"
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("历史版本") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text("服务器只追加不改写：每次保存都是一个版本，都可以查看和恢复。", fontSize = 12.5.sp, color = muted)
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                if (revisions.isEmpty() && error.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                revisions.forEachIndexed { i, r ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Chip("r${r.revision}", if (i == 0) ChipKind.OK else ChipKind.PLAIN)
                        Spacer(Modifier.width(8.dp))
                        Text(dateTime(r.createdAt) + if (r.deleted) " · 移到回收站" else "", Modifier.weight(1f), fontSize = 13.sp)
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { decode<kotlinx.serialization.json.JsonObject>(v.call { it.itemRevision(vaultId, itemId, r.revision) }) }
                                    .onSuccess { open = r.revision to Content.of(it) }
                                    .onFailure { v.say(errorText(it)) }
                            }
                        }) { Text("查看") }
                        if (i > 0) TextButton(onClick = { restoring = r.revision }) { Text("恢复") }
                    }
                }
                if (current.history.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                        Text("密码历史", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showPw = !showPw }) { Text(if (showPw) "隐藏" else "显示") }
                    }
                    current.history.reversed().forEach { h ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (showPw) show(h.value) else "••••••••", fontFamily = FontFamily.Monospace, fontSize = 13.5.sp)
                                Text("${h.label.ifEmpty { h.field }} · 用到 ${dateTime(h.until)}", fontSize = 11.5.sp, color = muted)
                            }
                            TextButton(onClick = { copy(v, context, show(h.value), "旧密码", true) }) { Text("复制") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
    open?.let { (rev, content) ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text("版本 r$rev") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(content.title, fontWeight = FontWeight.SemiBold)
                    content.fields.filter { !it.isEmpty }.forEach { f ->
                        Text("${f.label}：${if (f.secret || f.kind == "totp") "••••••" else f.text}", fontSize = 13.sp)
                    }
                    content.urls.forEach { Text(it.url, fontSize = 13.sp, color = muted) }
                    if (content.notes.isNotEmpty()) Text(content.notes, fontSize = 13.sp)
                    val changed = diff(content, current)
                    if (changed.isNotEmpty()) Text("与当前不同：${changed.joinToString("、")}", fontSize = 12.sp, color = Warn, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text("关闭") } },
        )
    }
    restoring?.let { rev ->
        ConfirmDialog("恢复到这个版本？", "会作为一个新版本保存，当前内容仍保留在历史里。", "恢复", false, { restoring = null }) {
            restoring = null
            scope.launch {
                try {
                    v.call { it.restoreRevision(vaultId, itemId, rev) }
                    v.say("已恢复（作为新版本保存）")
                    v.edited()
                    onClose()
                } catch (e: Exception) {
                    v.say(errorText(e))
                }
            }
        }
    }
}

private fun diff(a: Content, b: Content): List<String> {
    val out = mutableListOf<String>()
    if (a.title != b.title) out += "标题"
    val fa = a.fields.associate { it.id to it.value }
    b.fields.forEach { f -> if (fa[f.id] != f.value) out += f.label.ifEmpty { f.id } }
    if (a.notes != b.notes) out += "备注"
    if (a.urls.map { it.url } != b.urls.map { it.url }) out += "网址"
    return out
}

/** Side by side: the value kept and the other device's value (the web vault's conflict modal). */
@Composable
fun ConflictsDialog(v: Vault, vaultId: String, itemId: String, c: Content, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun resolve(id: String, useOther: Boolean) {
        scope.launch {
            try {
                v.call { it.resolveConflict(vaultId, itemId, id, useOther) }
                v.say(if (useOther) "已改用另一个值" else "已保留当前值")
                v.edited()
                if (c.conflicts.size <= 1) onClose()
            } catch (e: Exception) {
                v.say(errorText(e))
            }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("处理同步冲突") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("“当前”是现在保留的值，“另一台设备”是被替下的值。选了哪个都不会丢：另一个值仍在历史版本里。", fontSize = 12.5.sp, color = muted)
                c.conflicts.forEach { cf ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("${cf.label.ifEmpty { cf.path }} · ${ago(cf.at)}", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        Text("当前", fontSize = 11.5.sp, color = muted)
                        Text(show(cf.kept), Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        Text("另一台设备", fontSize = 11.5.sp, color = muted)
                        Text(show(cf.value), Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { resolve(cf.id, false) }) { Text("保留当前值") }
                            OutlinedButton(onClick = { resolve(cf.id, true) }) { Text("改用这个") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}
