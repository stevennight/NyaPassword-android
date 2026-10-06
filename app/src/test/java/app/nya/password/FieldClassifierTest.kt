package app.nya.password

import app.nya.password.autofill.FieldClassifier
import app.nya.password.autofill.ViewDesc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Login form heuristics on synthetic screens (what StructureParser builds from real AssistStructures). */
class FieldClassifierTest {
    // android.text.InputType values
    private val plain = 0x01
    private val textPassword = 0x81
    private val textVisiblePassword = 0x91
    private val textWebPassword = 0xe1
    private val textEmail = 0x21
    private val number = 0x02
    private val numberPassword = 0x12
    private val phone = 0x03

    private fun views(vararg v: ViewDesc) = v.mapIndexed { i, d -> d.copy(index = i) }

    private fun view(
        inputType: Int = plain,
        id: String? = null,
        hint: String? = null,
        hints: List<String> = emptyList(),
        html: Map<String, String>? = null,
        visible: Boolean = true,
        autofillType: Int = 1,
        text: String? = null,
    ) = ViewDesc(
        index = 0,
        autofillHints = hints,
        inputType = if (html != null) 0 else inputType,
        idEntry = id,
        hint = hint,
        text = text,
        htmlTag = if (html != null) "input" else null,
        htmlAttrs = html ?: emptyMap(),
        visible = visible,
        autofillType = autofillType,
    )

    @Test
    fun nativeAppWithChineseHints() {
        val c = FieldClassifier.classify(
            views(
                view(id = "et_account", hint = "手机号/邮箱"),
                view(textPassword, id = "et_pwd", hint = "请输入密码"),
                view(id = "tv_forget", text = "忘记密码？", autofillType = 0),
            ),
        )
        assertEquals(listOf(0), c.username)
        assertEquals(listOf(1), c.password)
        assertTrue(c.otp.isEmpty())
        assertFalse(c.newPassword)
    }

    @Test
    fun compatibilityModeBrowserPage() {
        // the system's accessibility bridge: no ids, no hints, password as the bare variation 0x80
        val c = FieldClassifier.classify(views(view(plain, hint = ""), view(0x80, hint = "")))
        assertEquals(listOf(0), c.username)
        assertEquals(listOf(1), c.password)
    }

    @Test
    fun multiStepLoginGuessesTheFocusedField() {
        val lone = views(view(plain, hint = "").copy(focused = true))
        assertTrue(FieldClassifier.classify(lone).isEmpty)
        val c = FieldClassifier.classify(lone, guessFocused = true)
        assertEquals(listOf(0), c.username)
        assertTrue(c.guessed)
        // Google's first step is recognized by its id
        val g = FieldClassifier.classify(views(view(plain, id = "identifierId").copy(focused = true)), guessFocused = true)
        assertEquals(listOf(0), g.username)
        assertFalse(g.guessed)
        // no guess for a search box, an unfocused field, or a page with a password field
        assertTrue(FieldClassifier.classify(views(view(plain, hint = "搜索").copy(focused = true)), guessFocused = true).isEmpty)
        assertTrue(FieldClassifier.classify(views(view(plain)), guessFocused = true).isEmpty)
        assertFalse(FieldClassifier.classify(views(view(plain).copy(focused = true), view(0x80)), guessFocused = true).guessed)
    }

    @Test
    fun keywordsWithoutInputTypes() {
        val c = FieldClassifier.classify(views(view(hint = "请输入账号"), view(hint = "请输入登录密码")))
        assertEquals(listOf(0), c.username)
        assertEquals(listOf(1), c.password)
    }

    @Test
    fun autofillHintsWin() {
        val c = FieldClassifier.classify(
            views(
                view(id = "field1", hints = listOf("emailAddress")),
                view(id = "field2", hints = listOf("password")),
                view(id = "field3", hints = listOf("smsOTPCode"), inputType = number),
            ),
        )
        assertEquals(listOf(0), c.username)
        assertEquals(listOf(1), c.password)
        assertEquals(listOf(2), c.otp)
    }

    @Test
    fun webFormInBrowser() {
        val c = FieldClassifier.classify(
            views(
                view(html = mapOf("type" to "hidden", "name" to "csrf_token")),
                view(html = mapOf("type" to "search", "name" to "q", "placeholder" to "Search")),
                view(html = mapOf("type" to "email", "name" to "email")),
                view(html = mapOf("type" to "password", "name" to "pass", "autocomplete" to "current-password")),
                view(html = mapOf("type" to "checkbox", "name" to "remember")),
            ),
        )
        assertEquals(listOf(2), c.username)
        assertEquals(listOf(3), c.password)
        assertFalse(c.newPassword)
    }

    @Test
    fun signUpFormIsNewPassword() {
        val c = FieldClassifier.classify(
            views(
                view(html = mapOf("type" to "text", "name" to "username", "autocomplete" to "username")),
                view(html = mapOf("type" to "password", "name" to "password", "autocomplete" to "new-password")),
                view(html = mapOf("type" to "password", "name" to "password2", "placeholder" to "确认密码")),
            ),
        )
        assertEquals(listOf(0), c.username)
        assertEquals(listOf(1, 2), c.password)
        assertTrue(c.newPassword)
    }

    @Test
    fun smsLoginFillsOnlyThePhone() {
        val c = FieldClassifier.classify(
            views(
                view(phone, id = "et_phone", hint = "请输入手机号"),
                view(number, id = "et_code", hint = "短信验证码"),
            ),
        )
        assertEquals(listOf(0), c.username)
        assertTrue(c.password.isEmpty())
        assertEquals(listOf(1), c.otp)
    }

    @Test
    fun numericCodeMarkedAsPasswordIsAnOtp() {
        val c = FieldClassifier.classify(views(view(numberPassword, id = "verify_code", hint = "动态码")))
        assertTrue(c.password.isEmpty())
        assertEquals(listOf(0), c.otp)
    }

    @Test
    fun numericPaymentPasswordIsAPassword() {
        val c = FieldClassifier.classify(views(view(numberPassword, id = "pay_pwd", hint = "支付密码")))
        assertEquals(listOf(0), c.password)
    }

    @Test
    fun captchaAndSearchAreIgnored() {
        val c = FieldClassifier.classify(
            views(
                view(id = "search_box", hint = "搜索"),
                view(id = "user", hint = "用户名"),
                view(textPassword, id = "password"),
                view(id = "captcha", hint = "图形验证码"),
            ),
        )
        assertEquals(listOf(1), c.username)
        assertEquals(listOf(2), c.password)
        assertTrue(c.otp.isEmpty())
    }

    @Test
    fun unlabelledFieldBeforePasswordIsTheUsername() {
        val c = FieldClassifier.classify(views(view(id = "edit1"), view(id = "edit2"), view(textWebPassword, id = "edit3")))
        assertEquals(listOf(1), c.username)
        assertEquals(listOf(2), c.password)
    }

    @Test
    fun visiblePasswordVariationCounts() {
        val c = FieldClassifier.classify(views(view(textEmail, id = "a"), view(textVisiblePassword, id = "b")))
        assertEquals(listOf(0), c.username)
        assertEquals(listOf(1), c.password)
    }

    @Test
    fun invisibleAndNonTextViewsAreSkipped() {
        val c = FieldClassifier.classify(
            views(
                view(textPassword, id = "old_password", visible = false),
                view(id = "account", autofillType = 2),
                view(id = "account2", hint = "账号"),
                view(textPassword, id = "pwd"),
            ),
        )
        assertEquals(listOf(2), c.username)
        assertEquals(listOf(3), c.password)
    }

    @Test
    fun englishLoginLabels() {
        val c = FieldClassifier.classify(views(view(hint = "Email or phone"), view(hint = "Password")))
        assertEquals(listOf(0), c.username)
        assertEquals(listOf(1), c.password)
    }

    @Test
    fun nothingToFill() {
        val c = FieldClassifier.classify(views(view(hint = "留言"), view(id = "comment")))
        assertTrue(c.isEmpty)
    }
}
