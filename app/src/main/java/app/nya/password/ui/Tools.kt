package app.nya.password.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.nya.password.core.Generated
import app.nya.password.core.HealthReport
import app.nya.password.core.SecurityReport
import app.nya.password.core.decode
import app.nya.password.core.errorText
import app.nya.password.ffi.generate
import app.nya.password.vault
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The password generator (the web vault's Generator): random, memorable, PIN. */
@Composable
fun Generator(onUse: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val v = context.vault
    var kind by rememberSaveable { mutableStateOf("random") }
    var length by rememberSaveable { mutableIntStateOf(20) }
    var upper by rememberSaveable { mutableStateOf(true) }
    var lower by rememberSaveable { mutableStateOf(true) }
    var digits by rememberSaveable { mutableStateOf(true) }
    var symbols by rememberSaveable { mutableStateOf(true) }
    var avoid by rememberSaveable { mutableStateOf(true) }
    var words by rememberSaveable { mutableIntStateOf(4) }
    var sep by rememberSaveable { mutableStateOf("-") }
    var cap by rememberSaveable { mutableStateOf(true) }
    var wordDigits by rememberSaveable { mutableStateOf(true) }
    var pinLen by rememberSaveable { mutableIntStateOf(6) }
    var nonce by remember { mutableIntStateOf(0) }

    val recipe = when (kind) {
        "random" -> buildJsonObject {
            put("kind", "random"); put("length", length); put("upper", upper); put("lower", lower)
            put("digits", digits); put("symbols", symbols); put("avoid_ambiguous", avoid)
        }
        "memorable" -> buildJsonObject {
            put("kind", "memorable"); put("words", words); put("separator", sep); put("capitalize", cap); put("digits", wordDigits)
        }
        else -> buildJsonObject { put("kind", "pin"); put("length", pinLen) }
    }.toString()
    var result by remember { mutableStateOf(Generated()) }
    LaunchedEffect(recipe, nonce) {
        result = runCatching { decode<Generated>(generate(recipe)) }.getOrDefault(Generated())
    }
    val strength = when {
        result.bits >= 100 -> "非常强"
        result.bits >= 70 -> "强"
        result.bits >= 45 -> "一般"
        else -> "弱"
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            result.password,
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
            fontFamily = FontFamily.Monospace, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
        )
        Text("${Math.round(result.bits)} 位熵 · $strength", fontSize = 12.5.sp, color = muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { nonce++ }) { Text("↻ 换一个") }
            OutlinedButton(onClick = { copy(v, context, result.password, "", true) }) { Text("复制") }
            if (onUse != null) Button(onClick = { onUse(result.password) }) { Text("使用") }
        }
        Seg(listOf("random" to "随机字符", "memorable" to "易记口令", "pin" to "PIN"), kind) { kind = it }
        when (kind) {
            "random" -> {
                Text("长度 $length", fontSize = 13.sp)
                Slider(length.toFloat(), { length = it.toInt() }, valueRange = 8f..64f)
                Checks(listOf("大写" to upper, "小写" to lower, "数字" to digits, "符号" to symbols, "排除易混字符" to avoid)) { i, b ->
                    when (i) {
                        0 -> upper = b
                        1 -> lower = b
                        2 -> digits = b
                        3 -> symbols = b
                        else -> avoid = b
                    }
                }
            }
            "memorable" -> {
                Text("组数 $words", fontSize = 13.sp)
                Slider(words.toFloat(), { words = it.toInt() }, valueRange = 3f..10f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("分隔符  ", fontSize = 13.sp)
                    Seg(listOf("-" to "-", "." to ".", "_" to "_", " " to "空格"), sep) { sep = it }
                }
                Checks(listOf("大写首字母" to cap, "加数字" to wordDigits)) { i, b -> if (i == 0) cap = b else wordDigits = b }
            }
            else -> {
                Text("位数 $pinLen", fontSize = 13.sp)
                Slider(pinLen.toFloat(), { pinLen = it.toInt() }, valueRange = 4f..12f)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Checks(items: List<Pair<String, Boolean>>, onChange: (Int, Boolean) -> Unit) {
    FlowRow {
        items.forEachIndexed { i, (label, on) ->
            Row(Modifier.clickable { onChange(i, !on) }.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, { onChange(i, it) })
                Text(label, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun GeneratorScreen(pad: PaddingValues) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("密码生成器", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Group {
            Column(Modifier.padding(16.dp)) { Generator() }
        }
    }
}

private val ISSUES = listOf(
    Triple("weak", "弱密码", "容易被猜到，建议用生成器换成随机密码"),
    Triple("reused", "重复使用", "同一个密码用在多个网站，一处泄露处处受影响"),
    Triple("totp_available", "可开启两步验证", "这些网站支持一次性密码（TOTP），开启后在这里保存即可自动填写"),
    Triple("old", "长期未改", "超过一年没有更换的密码"),
    Triple("insecure", "不安全的网址", "在 http（未加密）页面上使用的密码"),
)

/** "https://user@a.example.com:8443/login?x" → "a.example.com:8443"; bare domains pass through. */
private fun hostOf(url: String): String =
    url.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').ifBlank { url }

/** Security report and vault health check, computed on the device (the web vault's SecurityPage). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecurityScreen(activity: MainActivity, pad: PaddingValues) {
    val v = activity.vault
    var report by remember { mutableStateOf<SecurityReport?>(null) }
    var health by remember { mutableStateOf<HealthReport?>(null) }
    var tab by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(v.version) {
        try {
            val r: SecurityReport = decode(v.call { it.securityReport() })
            report = r
            health = decode(v.call { it.healthCheck() })
            if (tab.isEmpty()) tab = ISSUES.firstOrNull { (k) -> r.findings.any { it.issue == k } }?.first ?: "weak"
        } catch (e: Exception) {
            v.say(errorText(e))
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = pad.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("安全检查", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("全部在本机计算，不会把任何密码发出去。", color = muted, fontSize = 13.sp)
        val r = report
        if (r == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ISSUES.forEach { (k, label) ->
                val n = r.findings.count { it.issue == k }
                val on = tab == k
                Column(
                    Modifier.widthIn(min = 96.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable { tab = k }.padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text("$n", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (n > 0 && k != "old") Danger else MaterialTheme.colorScheme.onSurface)
                    Text(label, fontSize = 12.5.sp)
                }
            }
        }
        Text(ISSUES.find { it.first == tab }?.third ?: "", fontSize = 13.sp, color = muted)
        Group {
            val list = r.findings.filter { it.issue == tab }
            if (list.isEmpty()) Text("没有问题 🎉", Modifier.padding(16.dp), color = muted)
            list.forEachIndexed { i, f ->
                val open = Modifier.fillMaxWidth().clickable { activity.open(Route.Detail(f.vaultId, f.itemId)) }.padding(horizontal = 16.dp, vertical = 12.dp)
                val title = f.title.ifBlank { "（无标题）" }
                if (tab == "totp_available" || tab == "insecure") {
                    // the detail is the item's full URL: show only the host, under the title
                    Column(open, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, fontSize = 14.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (tab == "insecure") "http://${hostOf(f.detail)}" else hostOf(f.detail),
                            fontSize = 12.5.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Row(open, verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), fontSize = 14.5.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (tab) {
                                "reused" -> "${f.detail} 个条目共用"
                                "old" -> "${f.detail} 天"
                                "weak" -> "强度 ${f.detail}/4"
                                else -> f.detail
                            },
                            fontSize = 12.5.sp, color = muted, maxLines = 1,
                        )
                    }
                }
                if (i < list.size - 1) androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        health?.let { h ->
            Text("密码库健康检查", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Group {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "检查了 ${h.checked} 个条目：${h.ok} 个正常" +
                            (if (h.pending > 0) "，${h.pending} 个尚未同步" else "") +
                            (if (h.conflicts > 0) "，${h.conflicts} 个有冲突待处理" else "") +
                            (if (h.readOnly > 0) "，${h.readOnly} 个需要新版本才能编辑" else "") + "。",
                        fontSize = 14.sp,
                    )
                    h.problems.forEach { p -> Banner("${p.getOrNull(1)?.take(8) ?: ""}…：${p.getOrNull(2) ?: ""}", error = true) }
                    Text("检查时间 ${dateTime(h.checkedAt)}", fontSize = 12.sp, color = muted)
                }
            }
        }
    }
}
