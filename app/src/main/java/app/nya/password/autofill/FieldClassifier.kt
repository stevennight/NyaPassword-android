package app.nya.password.autofill

import java.util.Locale

/**
 * One fillable view, reduced to what the heuristics look at (no Android types,
 * so the rules run in JVM tests on synthetic screens). Built from the
 * AssistStructure by [StructureParser].
 */
data class ViewDesc(
    /** Position in traversal order (also the key back to the AutofillId). */
    val index: Int,
    val autofillHints: List<String> = emptyList(),
    /** android.text.InputType bits. */
    val inputType: Int = 0,
    val idEntry: String? = null,
    val hint: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    /** HTML tag of a browser / WebView element (`input`, `select`), if any. */
    val htmlTag: String? = null,
    /** HTML attributes: type, name, id, autocomplete, placeholder, aria-label, label… */
    val htmlAttrs: Map<String, String> = emptyMap(),
    val className: String? = null,
    /** View.AUTOFILL_TYPE_TEXT = 1 (only text views are classified). */
    val autofillType: Int = 1,
    val visible: Boolean = true,
    val focused: Boolean = false,
)

enum class Role { USERNAME, PASSWORD, OTP }

data class Classification(
    val username: List<Int> = emptyList(),
    val password: List<Int> = emptyList(),
    val otp: List<Int> = emptyList(),
    /** A sign-up / change-password form (new-password hints, a confirmation field, …). */
    val newPassword: Boolean = false,
    /** Nothing was recognized; the focused text field is taken as the username (multi-step logins). */
    val guessed: Boolean = false,
) {
    val isEmpty: Boolean get() = username.isEmpty() && password.isEmpty() && otp.isEmpty()
    fun roleOf(index: Int): Role? = when (index) {
        in password -> Role.PASSWORD
        in username -> Role.USERNAME
        in otp -> Role.OTP
        else -> null
    }
}

/**
 * Login form heuristics, Chinese and English (design doc §10.4 / §10.5: the
 * same signals as the extension: autofill hints, HTML autocomplete / type,
 * input type, then id / name / hint / label keywords). Order of precedence:
 * explicit hints → HTML attributes → input type → keywords → "the text field
 * just before the password field is the username".
 */
object FieldClassifier {
    // InputType constants (android.text.InputType), copied so this file needs no Android classes.
    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TYPE_CLASS_PHONE = 0x00000003
    private const val VAR_EMAIL = 0x00000020
    private const val VAR_PASSWORD = 0x00000080
    private const val VAR_VISIBLE_PASSWORD = 0x00000090
    private const val VAR_WEB_EMAIL = 0x000000d0
    private const val VAR_WEB_PASSWORD = 0x000000e0
    private const val NUM_VAR_PASSWORD = 0x00000010

    private val HINT_USERNAME = setOf(
        "username", "emailaddress", "email", "phone", "phonenumber", "tel", "newusername",
        "personname", // some apps tag the login name this way
    )
    private val HINT_PASSWORD = setOf("password", "current-password", "currentpassword")
    private val HINT_NEW_PASSWORD = setOf("newpassword", "new-password")
    private val HINT_OTP = setOf("smsotpcode", "one-time-code", "otp", "2faappotpcode", "smsotpcode1", "onetimecode")

    // Keyword lists (lower case). Matched against id / name / hint / label text.
    private val KW_PASSWORD = listOf("password", "passwd", "passwort", "pwd", "pass_word", "passcode", "密码", "口令", "密碼")
    private val KW_NEW = listOf("new", "confirm", "repeat", "again", "retype", "re-enter", "register", "signup", "sign_up", "新密码", "确认", "確認", "再次", "重复", "注册", "設定")
    private val KW_OTP = listOf(
        "otp", "totp", "2fa", "mfa", "one-time", "onetime", "verification", "verify_code", "verifycode", "auth_code", "authcode",
        "sms", "smscode", "checkcode", "check_code", "dynamic", "token",
        "验证码", "驗證碼", "校验码", "动态码", "动态密码", "短信码", "安全码", "两步验证", "二次验证", "身份验证器",
    )
    private val KW_USERNAME = listOf(
        "user", "login", "account", "acct", "identifier", "email", "e-mail", "mail", "phone", "mobile", "tel", "uid", "loginid", "nick", "member",
        "账号", "帳號", "帐号", "账户", "帳戶", "用户名", "用戶名", "登录名", "登录账号", "会员名", "手机号", "手機號", "手机", "手機",
        "邮箱", "郵箱", "电子邮件", "電子郵件", "邮件地址", "通行证", "学号", "工号", "身份证号",
    )
    /** Not login fields even if they mention one of the words above. */
    private val KW_IGNORE = listOf(
        "search", "query", "captcha", "imagecode", "img_code", "piccode", "pic_code", "coupon", "promo", "invite", "comment", "message", "chat",
        "搜索", "搜尋", "查找", "图形验证码", "圖形驗證碼", "图片验证码", "邀请码", "优惠码", "评论", "留言", "备注", "地址", "姓名",
    )

    private fun lower(s: String?) = s?.lowercase(Locale.ROOT).orEmpty()

    /** All text a view says about itself, for keyword matching. */
    private fun words(v: ViewDesc): String = buildString {
        for (s in listOf(v.idEntry, v.hint, v.contentDescription)) {
            if (!s.isNullOrBlank()) append(lower(s)).append(' ')
        }
        for (k in listOf("name", "id", "placeholder", "aria-label", "label", "title")) {
            v.htmlAttrs[k]?.let { append(lower(it)).append(' ') }
        }
        // the current text only helps for empty-ish fields that show their label as text (some apps)
        if (v.hint.isNullOrBlank() && v.text != null && v.text.length <= 20 && v.inputType and TYPE_MASK_VARIATION !in PASSWORD_VARIATIONS) {
            append(lower(v.text))
        }
    }

    private val PASSWORD_VARIATIONS = setOf(VAR_PASSWORD, VAR_VISIBLE_PASSWORD, VAR_WEB_PASSWORD)

    private fun any(text: String, kws: List<String>) = kws.any { text.contains(it) }

    /** Whether the view is a text input we may fill at all. */
    fun fillable(v: ViewDesc): Boolean {
        if (!v.visible || v.autofillType != 1) return false
        val tag = lower(v.htmlTag)
        if (tag.isNotEmpty() && tag != "input" && tag != "textarea") return false
        val type = lower(v.htmlAttrs["type"])
        if (type in setOf("hidden", "submit", "button", "checkbox", "radio", "file", "image", "reset", "search", "range", "color", "date")) return false
        return true
    }

    private fun hintRole(v: ViewDesc): Pair<Role, Boolean>? {
        for (h in v.autofillHints.map { lower(it).replace("_", "") }) {
            when {
                h in HINT_NEW_PASSWORD -> return Role.PASSWORD to true
                h in HINT_PASSWORD -> return Role.PASSWORD to false
                h in HINT_OTP -> return Role.OTP to false
                h in HINT_USERNAME -> return Role.USERNAME to false
            }
        }
        // HTML autocomplete may hold several tokens, e.g. "section-login username"
        val ac = lower(v.htmlAttrs["autocomplete"]).split(' ', ',').filter { it.isNotBlank() }
        when {
            "new-password" in ac -> return Role.PASSWORD to true
            "current-password" in ac -> return Role.PASSWORD to false
            "one-time-code" in ac -> return Role.OTP to false
            ac.any { it == "username" || it == "email" || it == "tel" || it == "webauthn" } -> return Role.USERNAME to false
            "off" in ac || ac.isEmpty() -> Unit
        }
        return null
    }

    private fun typeRole(v: ViewDesc): Role? {
        val html = lower(v.htmlAttrs["type"])
        if (html == "password") return Role.PASSWORD
        if (html == "email" || html == "tel") return Role.USERNAME
        val cls = v.inputType and TYPE_MASK_CLASS
        val variation = v.inputType and TYPE_MASK_VARIATION
        return when {
            // compatibility mode reports a password input as the bare variation (no class bits)
            (cls == TYPE_CLASS_TEXT || cls == 0) && variation in PASSWORD_VARIATIONS -> Role.PASSWORD
            cls == TYPE_CLASS_NUMBER && variation == NUM_VAR_PASSWORD -> Role.PASSWORD
            cls == TYPE_CLASS_TEXT && (variation == VAR_EMAIL || variation == VAR_WEB_EMAIL) -> Role.USERNAME
            cls == TYPE_CLASS_PHONE -> Role.USERNAME
            else -> null
        }
    }

    /**
     * [guessFocused]: when nothing is recognized, take the focused plain text
     * field as the username. For browser pages in compatibility mode, which
     * carry no HTML attributes: the first step of a multi-step login (only an
     * account field) looks like any other text field there.
     */
    fun classify(views: List<ViewDesc>, guessFocused: Boolean = false): Classification {
        val candidates = views.filter(::fillable)
        val roles = LinkedHashMap<Int, Role>()
        var newPassword = false
        val ignored = HashSet<Int>()

        for (v in candidates) {
            val w = words(v)
            val hinted = hintRole(v)
            if (hinted != null) {
                roles[v.index] = hinted.first
                if (hinted.second) newPassword = true
                continue
            }
            if (any(w, KW_IGNORE) && !any(w, KW_PASSWORD)) {
                ignored += v.index
                continue
            }
            val t = typeRole(v)
            val role = when {
                // a numeric "password" field labelled as a code is a one-time code
                t == Role.PASSWORD && any(w, KW_OTP) && !any(w, KW_PASSWORD) -> Role.OTP
                t != null -> t
                any(w, KW_OTP) && !any(w, KW_PASSWORD) -> Role.OTP
                any(w, KW_PASSWORD) && !any(w, listOf("forgot", "忘记", "reset")) -> Role.PASSWORD
                any(w, KW_USERNAME) -> Role.USERNAME
                else -> null
            }
            if (role != null) roles[v.index] = role
            if (role == Role.PASSWORD && any(w, KW_NEW)) newPassword = true
        }

        val passwords = roles.filterValues { it == Role.PASSWORD }.keys.toList()
        val usernames = roles.filterValues { it == Role.USERNAME }.keys.toMutableList()
        val otps = roles.filterValues { it == Role.OTP }.keys.toList()

        // No username found: the text field just before the first password field.
        if (usernames.isEmpty() && passwords.isNotEmpty()) {
            val first = passwords.first()
            candidates.lastOrNull { it.index < first && it.index !in roles && it.index !in ignored && plainText(it) }?.let { usernames += it.index }
        }
        // Several username guesses: prefer the ones closest before the password.
        val user = if (usernames.size > 1 && passwords.isNotEmpty()) {
            val first = passwords.first()
            usernames.filter { it < first }.takeLast(1).ifEmpty { usernames.take(1) }
        } else {
            usernames
        }
        if (passwords.size >= 2) newPassword = true
        if (guessFocused && user.isEmpty() && passwords.isEmpty() && otps.isEmpty()) {
            candidates.firstOrNull { it.focused && it.index !in ignored && plainText(it) }?.let {
                return Classification(username = listOf(it.index), guessed = true)
            }
        }
        return Classification(user, passwords, otps, newPassword)
    }

    private fun plainText(v: ViewDesc): Boolean {
        val tag = lower(v.htmlTag)
        val type = lower(v.htmlAttrs["type"])
        if (tag == "input") return type.isEmpty() || type == "text" || type == "email" || type == "tel"
        val cls = v.inputType and TYPE_MASK_CLASS
        return v.inputType == 0 || cls == TYPE_CLASS_TEXT || cls == TYPE_CLASS_PHONE
    }
}
