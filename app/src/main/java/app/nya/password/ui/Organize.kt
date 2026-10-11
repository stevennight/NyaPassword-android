package app.nya.password.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nya.password.MainActivity
import app.nya.password.core.BulkReport
import app.nya.password.core.DuplicateGroup
import app.nya.password.core.ItemView
import app.nya.password.core.MergeReport
import app.nya.password.core.TagCount
import app.nya.password.core.Vault
import app.nya.password.core.errorText
import app.nya.password.vault
import kotlinx.coroutines.launch

// Tidying up (the web vault's 整理 page and bulk actions): the work is done by
// the core (npw-core organize.rs); these are the screens and dialogs around it.

/** `" 工作 / 开发/ "` → `工作/开发` (the core's `normalize_tag`). */
fun normalizeTag(tag: String): String = tag.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")

/** Comma-separated tags (as in the editor), normalized, without repeats. */
fun parseTags(text: String): List<String> = text.split(',', '，').map(::normalizeTag).filter { it.isNotEmpty() }.distinct()

fun itemKey(i: ItemView): String = "${i.vaultId}/${i.itemId}"

fun bulkText(r: BulkReport, verb: String = "更新"): String {
    var s = if (r.changed > 0) "已${verb} ${r.changed} 个条目" else "没有需要修改的条目"
    val readOnly = r.skipped.count { it.reason == "read_only" }
    if (readOnly > 0) s += "；$readOnly 个由更新版本的 NyaPassword 写入，已跳过"
    return s
}

fun mergeText(r: MergeReport): String {
    var s = "已合并，${r.trashed} 个条目移到了回收站"
    if (r.secretsToHistory > 0) s += "，${r.secretsToHistory} 个不同的密码存入了密码历史"
    if (r.attachmentsCopied > 0) s += "，复制了 ${r.attachmentsCopied} 个附件"
    return s
}

/** Why these items cannot be merged as they are (empty: they can). */
fun mergeBlocker(items: List<ItemView>): String = when {
    items.any { it.conflicts > 0 } -> "其中有条目存在同步冲突，请先处理冲突再合并"
    items.any { it.readOnly } -> "其中有条目由更新版本的 NyaPassword 写入，请先更新本应用"
    items.any { it.deleted } -> "回收站里的条目不能合并"
    else -> ""
}

/** A line of text, with existing tags to tap (appended to the comma-separated value). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagInputDialog(
    title: String,
    body: String,
    initial: String = "",
    suggestions: List<String> = emptyList(),
    ok: String = "确定",
    onDismiss: () -> Unit,
    onOk: (String) -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (body.isNotEmpty()) Text(body, fontSize = 13.sp, color = muted)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true)
                if (suggestions.isNotEmpty()) {
                    FlowRow(
                        Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestions.forEach { s ->
                            SuggestionChip(onClick = {
                                val parts = text.text.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                                if (s !in parts) parts += s
                                val t = parts.joinToString(", ")
                                text = TextFieldValue(t, TextRange(t.length))
                            }, label = { Text("#$s", fontSize = 12.sp) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(text.text) }, enabled = text.text.isNotBlank()) { Text(ok) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Tick some of `options`. */
@Composable
fun ChooseDialog(title: String, body: String, options: List<String>, ok: String, onDismiss: () -> Unit, onOk: (List<String>) -> Unit) {
    var ticked by remember { mutableStateOf(emptyList<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (body.isNotEmpty()) Text(body, fontSize = 13.sp, color = muted)
                options.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().clickable { ticked = if (o in ticked) ticked - o else ticked + o },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(o in ticked, { on -> ticked = if (on) ticked + o else ticked - o })
                        Text(o, fontSize = 14.5.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(ticked) }, enabled = ticked.isNotEmpty()) { Text(ok) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Items to merge, one picked to keep (radio). */
@Composable
fun MergeRows(v: Vault, items: List<ItemView>, keep: String, onKeep: (String) -> Unit, onOpen: ((ItemView) -> Unit)? = null) {
    val manyVaults = items.map { it.vaultId }.distinct().size > 1
    Column {
        items.forEachIndexed { i, item ->
            val k = itemKey(item)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (k == keep) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onKeep(k) }.padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(k == keep, { onKeep(k) })
                Avatar(item.title, item.template, 34)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.title.ifBlank { "（无标题）" }, fontWeight = FontWeight.Medium, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (k == keep) {
                            Spacer(Modifier.width(6.dp))
                            Chip("保留", ChipKind.OK)
                        }
                    }
                    val vaultName = if (manyVaults) v.vaults.find { x -> x.id == item.vaultId }?.name?.let { n -> " · $n" } ?: "" else ""
                    val marks = buildList {
                        if (item.conflicts > 0) add("冲突")
                        if (item.attachments > 0) add("📎${item.attachments}")
                        if (item.passkeys > 0) add("通行密钥")
                        if (item.hasTotp) add("TOTP")
                        if (item.archived) add("归档")
                    }.joinToString(" ")
                    Text(
                        listOf(item.subtitle + vaultName, "更新于" + ago(item.updatedAt), marks).filter { s -> s.isNotBlank() }.joinToString(" · "),
                        fontSize = 12.5.sp, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onOpen != null) TextButton(onClick = { onOpen(item) }) { Text("查看") }
            }
            if (i < items.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** Merge picked items into one (from the list's selection). */
@Composable
fun MergeDialog(v: Vault, items: List<ItemView>, onClose: () -> Unit, onMerged: (ItemView) -> Unit) {
    val scope = rememberCoroutineScope()
    var keep by remember { mutableStateOf(itemKey(items.maxBy { it.updatedAt })) }
    var busy by remember { mutableStateOf(false) }
    val blocker = mergeBlocker(items)
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("合并 ${items.size} 个条目") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(MERGE_HELP, fontSize = 12.5.sp, color = muted)
                if (items.map { it.template }.distinct().size > 1) Banner("这些条目的类型不同，合并后保持“保留”条目的类型。")
                if (blocker.isNotEmpty()) Banner(blocker, error = true)
                MergeRows(v, items, keep, { keep = it })
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && blocker.isEmpty(), onClick = {
                val k = items.first { itemKey(it) == keep }
                busy = true
                scope.launch {
                    try {
                        v.say(mergeText(v.merge(k, items.filter { it !== k })))
                        v.edited()
                        onMerged(k)
                    } catch (e: Exception) {
                        v.say(errorText(e))
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "正在合并…" else "合并") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("取消") } },
    )
}

private const val MERGE_HELP =
    "选择要保留的条目，其他条目的内容会并入它，然后移到回收站：空着的字段会补上，不同的密码存入密码历史，其他不同的内容作为额外字段保留；网址、标签、备注、通行密钥和附件都会合并。"

/** Rename / delete a tag, with the confirmation the web vault asks for. */
@Composable
fun TagActionDialogs(v: Vault, renaming: String?, deleting: TagCount?, knownTags: List<String>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var merging by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun rename(from: String, to: String) {
        scope.launch {
            try {
                v.say(bulkText(v.renameTag(from, to)))
                v.edited()
            } catch (e: Exception) {
                v.say(errorText(e))
            }
        }
    }
    renaming?.let { tag ->
        TagInputDialog(
            "重命名标签",
            "“$tag”及其子标签会在所有条目上改名（回收站里的也会改）。用“/”表示嵌套，如“工作/开发”。",
            initial = tag,
            ok = "重命名",
            onDismiss = onDone,
        ) { text ->
            onDone()
            val to = normalizeTag(text)
            if (to.isEmpty() || to == tag) return@TagInputDialog
            if (knownTags.any { it == to || it.startsWith("$to/") }) merging = tag to to else rename(tag, to)
        }
    }
    merging?.let { (from, to) ->
        ConfirmDialog("合并标签？", "已经有标签“$to”，“$from”会并入其中。", "合并", false, { merging = null }) {
            merging = null
            rename(from, to)
        }
    }
    deleting?.let { c ->
        ConfirmDialog("删除标签？", "会从 ${c.items} 个条目上去掉“${c.tag}”及其子标签，条目本身不受影响。", "删除标签", true, onDone) {
            onDone()
            scope.launch {
                try {
                    v.say(bulkText(v.deleteTag(c.tag)))
                    v.edited()
                } catch (e: Exception) {
                    v.say(errorText(e))
                }
            }
        }
    }
}

/** 整理: duplicate items and tags (the web vault's OrganizePage). */
@Composable
fun OrganizeScreen(activity: MainActivity, start: String) {
    val v = activity.vault
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(start) }
    var groups by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
    var counts by remember { mutableStateOf<List<TagCount>?>(null) }
    val keeps = remember { mutableStateMapOf<String, String>() }
    var ignored by remember { mutableStateOf(v.prefs.ignoredDuplicates) }
    var busy by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<TagCount?>(null) }
    var mergeAll by remember { mutableStateOf(false) }

    fun groupKey(g: DuplicateGroup) = g.items.map(::itemKey).sorted().joinToString("|")
    fun saveIgnored(s: Set<String>) {
        ignored = s
        v.prefs.ignoredDuplicates = s
    }
    fun split(g: DuplicateGroup): Pair<ItemView, List<ItemView>> {
        val keep = g.items.find { itemKey(it) == keeps[groupKey(g)] } ?: g.items.first()
        return keep to g.items.filter { it !== keep }
    }

    LaunchedEffect(tab, v.version) {
        try {
            if (tab == "duplicates") {
                val gs = v.duplicates()
                gs.forEach { g ->
                    val k = groupKey(g)
                    if (keeps[k] == null || g.items.none { itemKey(it) == keeps[k] }) keeps[k] = itemKey(g.items.first())
                }
                groups = gs
                // forget dismissals of groups that no longer exist
                val live = gs.map(::groupKey).toSet()
                if (ignored.any { it !in live }) saveIgnored(ignored.filter { it in live }.toSet())
            } else {
                counts = v.tagCounts()
            }
        } catch (e: Exception) {
            v.say(errorText(e))
        }
    }

    val visible = groups.orEmpty().filter { groupKey(it) !in ignored }
    val identical = visible.filter { it.identical && mergeBlocker(it.items).isEmpty() }

    Column(Modifier.fillMaxSize()) {
        PageTop("整理", { activity.back() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Seg(listOf("duplicates" to "重复条目", "tags" to "标签"), tab) { tab = it }
            if (tab == "duplicates") {
                Text(
                    "同一网站上用户名相同、没有网址但标题和用户名相同、或者内容完全一样的条目。合并时选一个保留，其他条目的内容并入它后移到回收站，不会丢失任何数据。",
                    fontSize = 13.sp, color = muted,
                )
                val gs = groups
                if (gs == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (visible.isEmpty()) "没有发现重复的条目 🎉" else "找到 ${visible.size} 组可能重复的条目", Modifier.weight(1f))
                        if (gs.size > visible.size) TextButton(onClick = { saveIgnored(emptySet()) }) { Text("显示已忽略的 ${gs.size - visible.size} 组") }
                    }
                    if (identical.size > 1) {
                        Button(onClick = { mergeAll = true }, enabled = busy.isEmpty()) {
                            Text(if (busy == "all") "正在合并…" else "合并全部完全相同的（${identical.size} 组）")
                        }
                    }
                    visible.forEach { g ->
                        val k = groupKey(g)
                        val blocker = mergeBlocker(g.items)
                        Group {
                            Row(Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    g.site.ifEmpty { g.items.first().title.ifBlank { "（无标题）" } } + if (g.username.isNotEmpty()) " · ${g.username}" else "",
                                    Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                when {
                                    g.identical -> Chip("内容相同", ChipKind.OK)
                                    g.samePassword -> Chip("密码相同")
                                    else -> Chip("密码不同", ChipKind.WARN)
                                }
                            }
                            Column(Modifier.padding(horizontal = 6.dp)) {
                                MergeRows(v, g.items, keeps[k] ?: "", { keeps[k] = it }) { activity.open(Route.Detail(it.vaultId, it.itemId)) }
                            }
                            if (blocker.isNotEmpty()) Column(Modifier.padding(horizontal = 12.dp)) { Banner(blocker, error = true) }
                            if (!g.samePassword) {
                                Text("“保留”条目的密码不变，其他不同的密码会存进它的密码历史", Modifier.padding(horizontal = 14.dp), fontSize = 12.sp, color = muted)
                            }
                            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { saveIgnored(ignored + k) }) { Text("不是重复") }
                                Button(enabled = busy.isEmpty() && blocker.isEmpty(), onClick = {
                                    busy = k
                                    scope.launch {
                                        try {
                                            val (keep, others) = split(g)
                                            v.say(mergeText(v.merge(keep, others)))
                                            v.edited()
                                        } catch (e: Exception) {
                                            v.say(errorText(e))
                                        } finally {
                                            busy = ""
                                        }
                                    }
                                }) { Text(if (busy == k) "正在合并…" else "合并为一个") }
                            }
                        }
                    }
                }
            } else {
                Text(
                    "重命名会连同子标签一起改（“工作”改名后“工作/开发”也跟着改）；改成已有的标签名就是把两个标签合并。删除标签只去掉标签，不会删除条目。要给很多条目加标签，可以在密码库里长按条目多选后批量添加。",
                    fontSize = 13.sp, color = muted,
                )
                val cs = counts
                when {
                    cs == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    cs.isEmpty() -> Text("还没有标签", color = muted)
                    else -> Group {
                        cs.forEachIndexed { i, c ->
                            val depth = c.tag.count { it == '/' }
                            Row(Modifier.fillMaxWidth().padding(start = 14.dp + (depth * 16).dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { activity.showTag(c.tag) }.padding(vertical = 8.dp)) {
                                    Text((if (depth > 0) "└ " else "") + "# " + c.tag.substringAfterLast('/'), fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${c.items} 个条目", fontSize = 12.sp, color = muted)
                                }
                                TextButton(onClick = { renaming = c.tag }) { Text("重命名") }
                                TextButton(onClick = { deleting = c }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            }
                            if (i < cs.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }

    TagActionDialogs(v, renaming, deleting, counts.orEmpty().map { it.tag }) {
        renaming = null
        deleting = null
    }
    if (mergeAll) {
        ConfirmDialog(
            "合并完全相同的条目？",
            "${identical.size} 组内容完全相同的条目，每组保留选中的一个（默认最新的），其余移到回收站。",
            "全部合并", false, { mergeAll = false },
        ) {
            mergeAll = false
            val list = identical
            busy = "all"
            scope.launch {
                var done = 0
                var trashed = 0
                try {
                    for (g in list) {
                        val (keep, others) = split(g)
                        trashed += v.merge(keep, others).trashed
                        done++
                    }
                    v.say("已合并 $done 组，$trashed 个条目移到了回收站")
                } catch (e: Exception) {
                    v.say("合并了 $done 组后出错：${errorText(e)}")
                } finally {
                    busy = ""
                    v.edited()
                }
            }
        }
    }
}
