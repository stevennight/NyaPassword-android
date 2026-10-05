package app.nya.password.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nya.password.MainActivity
import app.nya.password.core.ItemDoc
import app.nya.password.core.PlainJson
import app.nya.password.core.Reprompt
import app.nya.password.core.errorText
import app.nya.password.ffi.fieldPresets
import app.nya.password.ffi.newShortId
import app.nya.password.vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val KINDS = listOf(
    "text" to "文本", "concealed" to "密文", "multiline" to "多行文本", "pin" to "数字密码（PIN）", "totp" to "一次性密码（TOTP）",
    "email" to "邮箱", "phone" to "电话", "url" to "网址", "date" to "日期", "month_year" to "年月", "number" to "数字", "boolean" to "是 / 否",
)
private val MATCHES = listOf("domain" to "域名", "host" to "主机", "starts_with" to "前缀", "exact" to "完全一致", "regex" to "正则", "never" to "从不填写")
private val ADDRESS_PARTS = listOf("province" to "省", "city" to "市", "district" to "区", "street" to "详细地址", "postal_code" to "邮编", "country" to "国家")
private const val MAX_ATTACHMENT = 100L * 1024 * 1024

/**
 * Creates or edits an item. The content is edited as the JSON tree from the
 * core ([ItemDoc]), so keys this version does not know are saved unchanged.
 */
@Composable
fun EditorScreen(activity: MainActivity, vaultId: String, itemId: String?, template: String) {
    val v = activity.vault
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<ItemDoc?>(null) }
    var tags by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var genFor by remember { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var removeAsk by remember { mutableStateOf<Pair<String, String>?>(null) }
    val reveal = remember { mutableStateMapOf<String, Boolean>() }
    var savedId by remember { mutableStateOf(itemId) }
    /** The saved item asks for verification ("使用前需要验证"); editing shows its secrets. */
    var savedReprompt by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val d = if (itemId != null) {
                val view = v.item(vaultId, itemId)
                savedReprompt = view.reprompt
                ItemDoc(view.content ?: error("no content"))
            } else {
                val n = ItemDoc(v.call { it.newItem(template) })
                if (template == "login") n.with("urls", JsonArray(emptyList())) else n
            }
            doc = d
            tags = d.tags.joinToString(", ")
        } catch (e: Exception) {
            v.say(errorText(e))
            activity.back()
        }
    }

    fun edit(f: (ItemDoc) -> ItemDoc) {
        doc = doc?.let(f)
        dirty = true
    }

    suspend fun save(close: Boolean): String? {
        val d = doc ?: return null
        val final = d.withTags(tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }).withoutEmptyUrls()
        busy = true
        return try {
            val id = v.call { it.saveItem(vaultId, savedId, final.toJson()) }
            savedId = id
            doc = ItemDoc(v.item(vaultId, id).content ?: final.root)
            dirty = false
            v.edited()
            if (close) {
                activity.back()
                if (itemId == null) activity.open(Route.Detail(vaultId, id))
                v.say("已保存")
            }
            id
        } catch (e: Exception) {
            v.say(errorText(e))
            null
        } finally {
            busy = false
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (name, size) = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        c.moveToFirst()
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) } ?: "附件"
                        val s = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L
                        n to s
                    } ?: ("附件" to 0L)
                }
                if (size > MAX_ATTACHMENT) return@launch v.say("附件不能超过 100 MB")
                val id = save(close = false) ?: return@launch
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val data = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: return@launch
                busy = true
                v.call { it.addAttachment(vaultId, id, name, mime, data) }
                data.fill(0)
                doc = ItemDoc(v.item(vaultId, id).content!!)
                v.say("已添加附件 $name")
                v.edited()
            } catch (e: Exception) {
                v.say(errorText(e))
            } finally {
                busy = false
            }
        }
    }

    BackHandler { if (dirty) discard = true else activity.back() }

    val d = doc
    Column(Modifier.fillMaxSize().imePadding()) {
        PageTop(if (itemId == null) "新建" else "编辑", { if (dirty) discard = true else activity.back() }) {
            Button(onClick = { scope.launch { save(close = true) } }, enabled = !busy && d != null, modifier = Modifier.padding(end = 8.dp)) { Text("保存") }
        }
        if (d == null) return@Column
        if (itemId != null && v.gated(savedReprompt, vaultId, itemId)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                UnlockPanel(activity, "此条目需要验证", "编辑这个条目前，请验证身份", verify = true) {
                    v.verifiedItem = Reprompt.key(vaultId, itemId)
                }
            }
            return@Column
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                d.title, { t -> edit { it.withTitle(t) } }, Modifier.fillMaxWidth(),
                label = { Text("标题") }, singleLine = true, textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(d.favorite, { b -> edit { it.withFavorite(b) } })
                Text("收藏")
            }

            val sections = listOf("" to "") + d.sections.map { ItemDoc.idOf(it) to ItemDoc.text(it, "label") }
            sections.forEach { (sid, slabel) ->
                val fields = d.fields.filter { ItemDoc.text(it, "section") == sid }
                if (sid.isNotEmpty() || fields.isNotEmpty()) {
                    Group {
                        if (sid.isNotEmpty()) {
                            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(slabel, { l -> edit { it.withSectionLabel(sid, l) } }, Modifier.weight(1f), label = { Text("分区名") }, singleLine = true)
                                TextButton(onClick = { edit { it.withoutSection(sid) } }) { Text("删除分区") }
                            }
                        }
                        fields.forEachIndexed { i, f ->
                            if (i > 0 || sid.isNotEmpty()) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            FieldEditor(
                                f, d.sections, reveal[ItemDoc.idOf(f)] == true,
                                onReveal = { id -> reveal[id] = reveal[id] != true },
                                onGenerate = { id -> genFor = id },
                                edit = ::edit,
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { addMenu = true }) { Text("＋ 添加字段") }
                    DropdownMenu(addMenu, { addMenu = false }) {
                        KINDS.forEach { (k, l) ->
                            DropdownMenuItem({ Text(l) }, onClick = {
                                addMenu = false
                                edit { it.withField(ItemDoc.newField(newShortId("f"), l, k)) }
                            })
                        }
                        DropdownMenuItem({ Text("多行密文（恢复码、私钥）") }, onClick = {
                            addMenu = false
                            edit { it.withField(ItemDoc.newField(newShortId("f"), "多行密文", "concealed", multiline = true)) }
                        })
                        HorizontalDivider()
                        presets().forEach { p ->
                            DropdownMenuItem({ Text(ItemDoc.text(p, "label")) }, onClick = {
                                addMenu = false
                                edit { it.withField(ItemDoc.patch(p, "id", JsonPrimitive(newShortId("f")))) }
                            })
                        }
                    }
                }
                OutlinedButton(onClick = { edit { it.withSection(newShortId("s"), "新分区") } }) { Text("＋ 添加分区") }
            }

            Group(title = "网站与应用") {
                d.urls.forEach { u ->
                    val id = ItemDoc.idOf(u)
                    val url = ItemDoc.text(u, "url")
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            url, { s -> edit { it.withUrl(id, s) } }, Modifier.weight(1f), singleLine = true,
                            placeholder = { Text("https://example.com 或 androidapp://包名") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        Select(MATCHES, ItemDoc.text(u, "match").ifEmpty { "domain" }) { m -> edit { it.withUrlMatch(id, m) } }
                        IconButton(onClick = { edit { it.withoutUrl(id) } }) { Icon(Icons.Filled.Close, "删除") }
                    }
                    if (url.startsWith("androidapp://") && ((u["cert_sha256"] as? JsonArray)?.isNotEmpty() == true)) {
                        Text("签名证书已记录：只有同一签名的应用能用这个条目", Modifier.padding(start = 16.dp), fontSize = 12.sp, color = muted)
                    }
                }
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { edit { it.withNewUrl(newShortId("u")) } }) { Text("＋ 网址") }
                    Checkbox(d.autofillNever, { b -> edit { it.withAutofillNever(b) } })
                    Text("不要自动填充这个条目", fontSize = 13.sp)
                }
            }

            Group(title = "使用前验证") {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(d.reprompt, { b -> edit { it.withReprompt(b) } })
                    Text("使用前需要验证（主密码 / Windows Hello / 指纹）", fontSize = 13.sp)
                }
                Text(
                    "查看、复制、自动填充这个条目的密码等内容前，都要再次验证身份。防的是别人趁你离开时使用已解锁的手机；条目的加密方式不变。",
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp), fontSize = 12.sp, color = muted,
                )
            }

            val passkeys = d.array("passkeys")
            if (passkeys.isNotEmpty()) {
                Group(title = "通行密钥") {
                    passkeys.forEach { p ->
                        Field(ItemDoc.text(p, "rp_id"), ItemDoc.text(p, "user_name")) {
                            TextButton(onClick = { removeAsk = "passkey" to ItemDoc.idOf(p) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }

            Group(title = "附件") {
                d.array("attachments").forEach { a ->
                    Field("📎 ${ItemDoc.text(a, "name")}", bytes(ItemDoc.text(a, "size").toLongOrNull() ?: 0)) {
                        IconButton(onClick = { removeAsk = "attachment" to ItemDoc.idOf(a) }) { Icon(Icons.Filled.Close, "移除") }
                    }
                }
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    TextButton(onClick = { pickFile.launch(arrayOf("*/*")) }, enabled = !busy) { Text("＋ 添加附件") }
                }
                if (savedId == null) Text("添加附件时会先保存条目", Modifier.padding(start = 16.dp, bottom = 8.dp), fontSize = 12.sp, color = muted)
            }

            OutlinedTextField(tags, { tags = it; dirty = true }, Modifier.fillMaxWidth(), label = { Text("标签（逗号分隔，“工作/开发”表示嵌套）") }, singleLine = true)
            OutlinedTextField(d.notes, { n -> edit { it.withNotes(n) } }, Modifier.fillMaxWidth(), label = { Text("备注") }, minLines = 4)
        }
    }

    genFor?.let { fid ->
        GeneratorDialog(onClose = { genFor = null }) { pw ->
            edit { it.withFieldValue(fid, pw) }
            reveal[fid] = true
            genFor = null
        }
    }
    if (discard) {
        ConfirmDialog("放弃修改？", "尚未保存的修改会丢失。", "放弃", true, { discard = false }) {
            discard = false
            activity.back()
        }
    }
    removeAsk?.let { (what, id) ->
        if (what == "passkey") {
            ConfirmDialog("删除通行密钥？", "保存后生效；之后需要在网站上重新创建。", "删除", true, { removeAsk = null }) {
                removeAsk = null
                edit { it.withoutPasskey(id) }
            }
        } else {
            ConfirmDialog("移除附件？", "附件会从条目中移除（旧版本里仍然有）。保存后生效。", "移除", true, { removeAsk = null }) {
                removeAsk = null
                edit { it.withoutAttachment(id) }
            }
        }
    }
}

private fun presets(): List<JsonObject> = runCatching {
    (PlainJson.parseToJsonElement(fieldPresets("zh-CN")) as JsonArray).map { it as JsonObject }
}.getOrDefault(emptyList())

@Composable
private fun FieldEditor(
    f: JsonObject,
    sections: List<JsonObject>,
    revealed: Boolean,
    onReveal: (String) -> Unit,
    onGenerate: (String) -> Unit,
    edit: ((ItemDoc) -> ItemDoc) -> Unit,
) {
    val id = ItemDoc.idOf(f)
    val kind = ItemDoc.text(f, "kind")
    val multiline = (f["multiline"] as? JsonPrimitive)?.contentOrNull == "true" || kind == "multiline"
    val secret = kind == "concealed" || kind == "pin"
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                ItemDoc.text(f, "label"), { l -> edit { it.withFieldLabel(id, l) } }, Modifier.weight(1f),
                placeholder = { Text("字段名") }, singleLine = true, textStyle = TextStyle(fontSize = 13.sp),
            )
            if (secret) {
                IconButton(onClick = { onReveal(id) }) { Icon(if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (revealed) "隐藏" else "显示") }
                IconButton(onClick = { onGenerate(id) }) { Icon(Icons.Filled.Key, "生成") }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "字段设置") }
                DropdownMenu(menu, { menu = false }) {
                    Text("类型", Modifier.padding(horizontal = 12.dp), fontSize = 12.sp, color = muted)
                    (KINDS + if (KINDS.none { it.first == kind }) listOf(kind to kind) else emptyList()).forEach { (k, l) ->
                        DropdownMenuItem({ Text(if (k == kind) "✓ $l" else l) }, onClick = { menu = false; edit { it.withFieldKind(id, k) } })
                    }
                    if (sections.isNotEmpty()) {
                        HorizontalDivider()
                        Text("分区", Modifier.padding(horizontal = 12.dp), fontSize = 12.sp, color = muted)
                        DropdownMenuItem({ Text("（无分区）") }, onClick = { menu = false; edit { it.withFieldSection(id, null) } })
                        sections.forEach { s ->
                            DropdownMenuItem({ Text(ItemDoc.text(s, "label")) }, onClick = { menu = false; edit { it.withFieldSection(id, ItemDoc.idOf(s)) } })
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem({ Text("上移") }, onClick = { menu = false; edit { it.move("fields", id, -1) } })
                    DropdownMenuItem({ Text("下移") }, onClick = { menu = false; edit { it.move("fields", id, 1) } })
                    DropdownMenuItem({ Text("删除字段", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; edit { it.withoutField(id) } })
                }
            }
        }
        when {
            kind == "address" -> {
                val a = f["value"] as? JsonObject ?: JsonObject(emptyMap())
                ADDRESS_PARTS.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(end = 8.dp)) {
                        pair.forEach { (k, l) ->
                            OutlinedTextField(
                                ItemDoc.text(a, k), { s -> edit { it.withFieldValue(id, ItemDoc.patch(a, k, JsonPrimitive(s))) } },
                                Modifier.weight(1f), label = { Text(l) }, singleLine = true,
                            )
                        }
                    }
                }
            }
            kind == "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ItemDoc.text(f, "value") == "true", { b -> edit { it.withFieldValue(id, if (b) "true" else "false") } })
                Text("是")
            }
            else -> OutlinedTextField(
                ItemDoc.text(f, "value"), { s -> edit { it.withFieldValue(id, s) } },
                Modifier.fillMaxWidth().padding(end = 8.dp),
                singleLine = !multiline,
                minLines = if (multiline) 3 else 1,
                placeholder = { if (kind == "totp") Text("otpauth://… 或密钥") },
                visualTransformation = if (secret && !revealed && !multiline) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = TextStyle(fontFamily = if (secret || kind == "totp") FontFamily.Monospace else FontFamily.Default, fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (kind) {
                        "pin", "number" -> KeyboardType.NumberPassword.takeIf { kind == "pin" } ?: KeyboardType.Number
                        "email" -> KeyboardType.Email
                        "phone" -> KeyboardType.Phone
                        "url" -> KeyboardType.Uri
                        "concealed" -> if (multiline) KeyboardType.Text else KeyboardType.Password
                        else -> KeyboardType.Text
                    },
                    autoCorrectEnabled = !(secret || kind == "totp"),
                ),
                supportingText = if (secret && multiline && !revealed) ({ Text("多行密文：保存后默认隐藏，可按行复制") }) else null,
            )
        }
    }
}

/** The generator as a dialog with "使用" (from the editor). */
@Composable
fun GeneratorDialog(onClose: () -> Unit, onUse: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("密码生成器") },
        text = { Generator(onUse = onUse) },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

