package app.nya.password.core

/** A failed core call: [code] from `CoreError::code` (or a local one), [detail] for logs. */
class CoreFailure(val code: String, val detail: String) : Exception("$code: $detail")

/** The message shown for a failure (the web vault's `errorText`). */
fun errorText(e: Throwable): String = when (e) {
    is CoreFailure -> errorText(e.code, e.detail)
    else -> e.message ?: e.toString()
}

fun errorText(code: String, detail: String): String = when (code) {
    "wrong_password" -> "主密码或 Secret Key 不正确"
    "locked" -> "已锁定，请先解锁"
    "offline", "network" -> "无法连接服务器（${detail.removePrefix("network error: ")}）"
    "session_expired" -> "登录已过期，请重新输入主密码"
    "device_revoked" -> "此设备已被移出账户，请重新登录"
    "read_only" -> "这个条目由更新版本的 NyaPassword 写入，请先更新本应用再编辑"
    "not_signed_in" -> "此设备尚未登录"
    "not_found" -> "找不到这个条目（可能已在其他设备上删除）"
    "crypto" -> if (detail.contains("secret key", ignoreCase = true)) "Secret Key 格式不对，请检查是否抄错" else "解密失败：$detail"
    "api" -> apiText(detail)
    "invalid" -> invalidText(detail)
    else -> detail.ifBlank { code }
}

private fun apiText(detail: String): String = when {
    detail.contains("registration", ignoreCase = true) -> "服务器不接受注册（需要邀请码，或已有账户）"
    detail.contains("invite", ignoreCase = true) -> "邀请码无效"
    detail.contains("rate", ignoreCase = true) || detail.contains(" 429 ") -> "尝试太频繁，请稍后再试"
    else -> "服务器返回错误：$detail"
}

private fun invalidText(detail: String): String = when {
    detail.startsWith("passkey:") -> passkeyText(detail)
    detail.contains("https", ignoreCase = true) && detail.contains("localhost") -> "服务器地址必须是 https://（只有 localhost 可以用 http）"
    detail.contains("edits have not been synced") -> "还有修改没有同步到服务器"
    detail.contains("already signed in") -> "此设备已登录了账户"
    else -> detail
}

/** `passkey:<DOMException name>:<text>` from the core. */
fun passkeyText(detail: String): String {
    val name = detail.removePrefix("passkey:").substringBefore(':')
    return when (name) {
        "InvalidStateError" -> "这个账户在该网站已经有通行密钥了"
        "NotSupportedError" -> "网站要求的算法不受支持"
        "SecurityError" -> "来源与网站不匹配，已拒绝"
        "NotAllowedError" -> "不允许使用这个通行密钥"
        else -> detail.substringAfter(':').substringAfter(':').ifBlank { detail }
    }
}
