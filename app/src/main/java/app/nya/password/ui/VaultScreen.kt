package app.nya.password.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nya.password.MainActivity
import app.nya.password.core.ItemView
import app.nya.password.core.errorText
import app.nya.password.vault
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Which items the list shows (the web vault's sidebar `Nav`). */
data class ListFilter(
    val kind: String = "all", // all | favorites | conflicts | vault | template | archived | trash
    val id: String? = null,
    val query: String = "",
) {
    fun toJson(): String = buildJsonObject {
        put("query", query)
        when (kind) {
            "favorites" -> put("favorites", true)
            "conflicts" -> put("conflicts", true)
            "archived" -> put("archived", true)
            "trash" -> put("trash", true)
            "vault" -> put("vault_id", id)
            "template" -> put("template", id)
        }
    }.toString()
}

/** Template icons as short glyphs (the web vault's TEMPLATE_ICONS). */
val TEMPLATE_GLYPH = mapOf(
    "login" to "🔑", "password" to "⚿", "secure_note" to "📝", "credit_card" to "💳", "bank_account" to "🏦",
    "identity" to "👤", "document" to "🪪", "ssh_key" to "⌘", "server" to "🖥", "database" to "🗄",
    "api_credential" to "⚙", "wifi" to "📶", "software_license" to "📄", "crypto_wallet" to "🪙",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(activity: MainActivity, pad: PaddingValues) {
    val v = activity.vault
    var filter by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<ListFilter, List<String?>>(
        save = { listOf(it.kind, it.id, it.query) },
        restore = { ListFilter(it[0] ?: "all", it[1], it[2] ?: "") },
    )) { mutableStateOf(ListFilter()) }
    var items by remember { mutableStateOf<List<ItemView>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf(false) }
    var templateMenu by remember { mutableStateOf(false) }
    val templates = remember { v.templatesNow() }

    LaunchedEffect(filter, v.version) {
        items = runCatching { v.items(filter.toJson()) }.getOrElse {
            v.say(errorText(it))
            emptyList()
        }
        loaded = true
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("密码库", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (v.syncError.isNotEmpty()) Chip("同步失败", ChipKind.ERR)
                IconButton(onClick = { v.syncSoon(silent = false) }, enabled = !v.syncing) {
                    Icon(Icons.Filled.Sync, "立即同步", tint = if (v.syncing) MaterialTheme.colorScheme.primary else muted)
                }
            }
            OutlinedTextField(
                filter.query,
                { filter = filter.copy(query = it) },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("搜索（支持拼音、首字母）") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (filter.query.isNotEmpty()) IconButton(onClick = { filter = filter.copy(query = "") }) { Icon(Icons.Filled.Clear, "清除") } },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(filter.kind == "all", { filter = filter.copy(kind = "all", id = null) }, { Text("全部") })
                FilterChip(filter.kind == "favorites", { filter = filter.copy(kind = "favorites", id = null) }, { Text("★ 收藏") })
                if (v.attention.first > 0) {
                    FilterChip(filter.kind == "conflicts", { filter = filter.copy(kind = "conflicts", id = null) }, { Text("⚠ 冲突 ${v.attention.first}") })
                }
                if (v.vaults.size > 1) {
                    v.vaults.forEach { vv ->
                        FilterChip(filter.kind == "vault" && filter.id == vv.id, { filter = filter.copy(kind = "vault", id = vv.id) }, { Text(vv.name) })
                    }
                }
                Box {
                    val t = templates.find { it.id == filter.id && filter.kind == "template" }
                    FilterChip(filter.kind == "template", { templateMenu = true }, { Text(if (t != null) "${TEMPLATE_GLYPH[t.id] ?: ""} ${t.label}" else "分类 ▾") })
                    androidx.compose.material3.DropdownMenu(templateMenu, { templateMenu = false }) {
                        templates.forEach { tp ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("${TEMPLATE_GLYPH[tp.id] ?: "•"}  ${tp.label}") },
                                onClick = {
                                    templateMenu = false
                                    filter = filter.copy(kind = "template", id = tp.id)
                                },
                            )
                        }
                    }
                }
                FilterChip(filter.kind == "archived", { filter = filter.copy(kind = "archived", id = null) }, { Text("归档") })
                FilterChip(filter.kind == "trash", { filter = filter.copy(kind = "trash", id = null) }, { Text("回收站") })
            }
            if (v.attention.second > 0) {
                Text("${v.attention.second} 处修改尚未同步", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), fontSize = 12.sp, color = muted)
            }
            PullToRefreshBox(isRefreshing = v.syncing, onRefresh = { v.syncSoon(silent = false) }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = pad.calculateBottomPadding() + 88.dp),
                ) {
                    if (loaded && items.isEmpty()) {
                        item {
                            Text(
                                when {
                                    filter.query.isNotEmpty() -> "没有找到“${filter.query}”"
                                    filter.kind == "trash" -> "回收站是空的"
                                    else -> "这里还没有条目，点右下角的 + 新建"
                                },
                                Modifier.fillMaxWidth().padding(32.dp),
                                color = muted,
                            )
                        }
                    }
                    items(items, key = { it.vaultId + "/" + it.itemId }) { item ->
                        ItemRow(item) { activity.open(Route.Detail(item.vaultId, item.itemId)) }
                        HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        if (filter.kind != "trash") {
            FloatingActionButton(
                onClick = { newMenu = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = pad.calculateBottomPadding() + 20.dp),
            ) { Icon(Icons.Filled.Add, "新建") }
        }
    }

    if (newMenu) {
        AlertDialog(
            onDismissRequest = { newMenu = false },
            title = { Text("新建") },
            text = {
                LazyColumn {
                    items(templates) { t ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                newMenu = false
                                val vid = if (filter.kind == "vault") filter.id!! else v.vaults.firstOrNull()?.id ?: return@clickable
                                activity.open(Route.Edit(vid, null, t.id))
                            }.padding(horizontal = 8.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(TEMPLATE_GLYPH[t.id] ?: "•", Modifier.width(32.dp), fontSize = 18.sp)
                            Text(t.label, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { newMenu = false }) { Text("取消") } },
        )
    }
}

@Composable
fun ItemRow(item: ItemView, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(item.title, item.template, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title.ifBlank { "（无标题）" }, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (item.favorite) Icon(Icons.Filled.Star, "收藏", Modifier.padding(start = 4.dp).size(14.dp), tint = Warn)
                if (item.reprompt) Icon(Icons.Filled.Lock, "使用前需要验证", Modifier.padding(start = 4.dp).size(13.dp), tint = muted)
            }
            val sub = item.subtitle.ifBlank { item.urls.firstOrNull()?.let { app.nya.password.ffi.displayHost(it) } ?: "" }
            if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (item.conflicts > 0) Chip("冲突", ChipKind.WARN)
        if (item.pending) Spacer(Modifier.width(4.dp))
        if (item.pending) Chip("未同步")
        if (item.passkeys > 0) Spacer(Modifier.width(4.dp))
        if (item.passkeys > 0) Chip("passkey")
    }
}

/** A coloured circle with the first letter of the title (the web vault's `avatar`). */
@Composable
fun Avatar(title: String, template: String, size: Int) {
    val letter = title.trim().firstOrNull()?.uppercase() ?: (TEMPLATE_GLYPH[template] ?: "?")
    val color = Color.hsl(hue(title.ifBlank { template }), 0.55f, 0.52f)
    Box(Modifier.size(size.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(letter, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.42f).sp)
    }
}
