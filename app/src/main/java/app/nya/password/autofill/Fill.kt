@file:Suppress("DEPRECATION")

package app.nya.password.autofill

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.slice.Slice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import app.nya.password.MainActivity
import app.nya.password.R
import app.nya.password.core.Content
import app.nya.password.core.ItemView
import app.nya.password.core.Origins
import app.nya.password.core.Vault
import app.nya.password.core.decode

/** Where a fill request comes from and what the vault matches it against. */
data class FillTarget(
    /** `https://<domain>` for a page in a browser, `androidapp://<package>` for an app. */
    val target: String,
    val packageName: String,
    val browser: Boolean,
    /** SHA-256 (hex) of the calling app's signing certificates. */
    val certs: List<String>,
    /** Shown to the user: the domain or the app's name. */
    val label: String,
) {
    fun toBundle() = Bundle().apply {
        putString("target", target)
        putString("package", packageName)
        putBoolean("browser", browser)
        putStringArrayList("certs", ArrayList(certs))
        putString("label", label)
    }

    companion object {
        fun from(b: Bundle?): FillTarget? {
            b ?: return null
            return FillTarget(
                b.getString("target") ?: return null,
                b.getString("package").orEmpty(),
                b.getBoolean("browser"),
                b.getStringArrayList("certs").orEmpty(),
                b.getString("label").orEmpty(),
            )
        }

        /**
         * Pages in browsers match by their domain; everything else (including
         * WebViews inside ordinary apps, which could read what is filled) by
         * the app's package and signing certificate.
         */
        fun resolve(context: Context, screen: ParsedScreen): FillTarget? {
            val pkg = screen.packageName.ifEmpty { return null }
            val certs = Origins.signingCerts(context, pkg).map(Origins::hex)
            if (Browsers.isBrowser(context, pkg, certs)) {
                val domain = screen.webDomain ?: return null
                return FillTarget(Origins.webTarget(screen.webScheme, domain), pkg, true, certs, domain)
            }
            val label = runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            return FillTarget(Origins.appTarget(pkg), pkg, false, certs, label)
        }
    }
}

/** Browsers whose reported page domain is trusted. */
object Browsers {
    /**
     * Browsers without an entry in the privileged allowlist (common in China),
     * recognized by package name only. A fake app could take one of these
     * names on a phone that lacks the real browser; the allowlisted ones are
     * checked by certificate.
     */
    val BY_PACKAGE = setOf(
        "com.UCMobile", "com.uc.browser.en", "com.tencent.mtt", "com.quark.browser", "com.baidu.searchbox", "com.baidu.browser.apps",
        "com.huawei.browser", "com.hihonor.baidu.browser", "com.android.browser", "com.mi.globalbrowser", "com.vivo.browser",
        "com.coloros.browser", "com.oppo.browser", "com.qihoo.browser", "com.sogou.activity.src", "sogou.mobile.explorer",
        "com.microsoft.bing", "com.kiwibrowser.browser", "org.bromite.bromite", "org.cromite.cromite", "com.ecosia.android",
        "mark.via", "mark.via.gp", "com.mmbox.xbrowser", "org.chromium.webview_shell",
    )

    @Volatile private var allowlist: String? = null

    fun allowlist(context: Context): String = allowlist ?: Origins.privilegedAllowlist(context).also { allowlist = it }

    fun isBrowser(context: Context, pkg: String, certs: List<String>): Boolean =
        Origins.privileged(allowlist(context), pkg, certs) || pkg in BY_PACKAGE
}

object Fill {
    /** Matching items with their content, best first (blocking: call off the main thread). */
    fun candidates(vault: Vault, t: FillTarget, limit: Int = 12): List<Pair<ItemView, Content>> {
        val certs = if (t.browser) emptyList() else t.certs
        val views: List<ItemView> = decode(vault.callNow { it.autofillCandidates(t.target, certs) })
        return views.take(limit).mapNotNull { v ->
            runCatching {
                val full: ItemView = decode(vault.callNow { it.item(v.vaultId, v.itemId) })
                v to Content.of(full.content ?: return@runCatching null)
            }.getOrNull()
        }
    }

    fun presentation(context: Context, title: String, subtitle: String?, icon: Int = R.drawable.ic_autofill_key): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.title, title)
            if (subtitle.isNullOrEmpty()) {
                setViewVisibility(R.id.subtitle, android.view.View.GONE)
            } else {
                setTextViewText(R.id.subtitle, subtitle)
            }
            setImageViewResource(R.id.icon, icon)
        }

    /** The inline (keyboard) suggestion for one dataset, when the keyboard asked for one. */
    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("RestrictedApi")
    fun inline(context: Context, spec: InlinePresentationSpec?, title: String, subtitle: String?, icon: Int = R.drawable.ic_autofill_key): InlinePresentation? {
        spec ?: return null
        if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) return null
        val attribution = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = InlineSuggestionUi.newContentBuilder(attribution)
            .setTitle(title)
            .setStartIcon(Icon.createWithResource(context, icon))
            .setContentDescription(title)
        if (!subtitle.isNullOrEmpty()) b.setSubtitle(subtitle)
        val slice: Slice = b.build().slice
        return InlinePresentation(slice, spec, false)
    }

    /** The fields a dataset of this item fills (username, password, one-time code). */
    fun fillIds(screen: ParsedScreen, c: Content): List<android.view.autofill.AutofillId> = buildList {
        if (c.username != null) addAll(screen.idsOf(Role.USERNAME))
        if (c.password != null) addAll(screen.idsOf(Role.PASSWORD))
        if (c.totp != null) addAll(screen.idsOf(Role.OTP))
    }

    /**
     * A dataset of an item marked "使用前需要验证": it carries no values; picking
     * it opens [AutofillActivity] to verify the user (biometrics / master
     * password), which then returns the real [dataset].
     */
    fun guardedDataset(
        context: Context,
        screen: ParsedScreen,
        item: ItemView,
        c: Content,
        target: FillTarget,
        inline: InlinePresentation?,
    ): Dataset? {
        val ids = fillIds(screen, c)
        if (ids.isEmpty()) return null
        val p = presentation(context, item.title.ifBlank { "（无标题）" }, "${c.username ?: item.subtitle} · 需要验证".trimStart(' ', '·'), R.drawable.ic_autofill_lock)
        val b = Dataset.Builder(p)
        ids.forEach { b.setValue(it, null, p) }
        b.setAuthentication(AutofillActivity.repromptSender(context, target, item.vaultId, item.itemId))
        if (inline != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) b.setInlinePresentation(inline)
        return b.build()
    }

    /** One dataset filling the item's username / password / one-time code. */
    fun dataset(
        context: Context,
        screen: ParsedScreen,
        item: ItemView,
        c: Content,
        inline: InlinePresentation?,
    ): Dataset? {
        val title = item.title.ifBlank { "（无标题）" }
        val p = presentation(context, title, c.username ?: item.subtitle)
        val b = Dataset.Builder(p)
        var any = false
        c.username?.let { u -> screen.idsOf(Role.USERNAME).forEach { b.setValue(it, AutofillValue.forText(u)); any = true } }
        c.password?.let { pw -> screen.idsOf(Role.PASSWORD).forEach { b.setValue(it, AutofillValue.forText(pw)); any = true } }
        c.totp?.let { uri ->
            val ids = screen.idsOf(Role.OTP)
            if (ids.isNotEmpty()) {
                val code = runCatching {
                    decode<app.nya.password.core.OtpCode>(app.nya.password.ffi.otpCode(uri, System.currentTimeMillis() / 1000)).code
                }.getOrNull()
                if (code != null) ids.forEach { b.setValue(it, AutofillValue.forText(code)); any = true }
            }
        }
        if (!any) return null
        if (inline != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) b.setInlinePresentation(inline)
        return b.build()
    }

    /** "Locked: tap to unlock" for the whole response. */
    fun lockedResponse(context: Context, screen: ParsedScreen, target: FillTarget, specs: List<InlinePresentationSpec>?): FillResponse {
        val sender = AutofillActivity.sender(context, AutofillActivity.MODE_UNLOCK, target, specs)
        val title = "NyaPassword 已锁定"
        val sub = "点按解锁，填写 ${target.label}"
        val p = presentation(context, title, sub, R.drawable.ic_autofill_lock)
        val r = FillResponse.Builder()
        val ids = screen.allIds.toTypedArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !specs.isNullOrEmpty()) {
            r.setAuthentication(ids, sender, p, inline(context, specs.first(), title, sub, R.drawable.ic_autofill_lock))
        } else {
            r.setAuthentication(ids, sender, p)
        }
        saveInfo(screen)?.let { r.setSaveInfo(it) }
        return r.build()
    }

    /** The datasets of the matching items, "search NyaPassword", and SaveInfo. Blocking. */
    fun response(context: Context, vault: Vault, screen: ParsedScreen, target: FillTarget, specs: List<InlinePresentationSpec>?): FillResponse? {
        val r = FillResponse.Builder()
        var count = 0
        // the last spec is the one keyboards keep for "more" entries; use the others first
        fun spec(i: Int): InlinePresentationSpec? = specs?.let { if (it.isEmpty()) null else it[minOf(i, it.size - 1)] }
        for ((v, c) in candidates(vault, target)) {
            val inl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                inline(context, spec(count), v.title.ifBlank { "（无标题）" }, c.username, if (v.reprompt) R.drawable.ic_autofill_lock else R.drawable.ic_autofill_key)
            } else {
                null
            }
            val ds = if (v.reprompt) guardedDataset(context, screen, v, c, target, inl) else dataset(context, screen, v, c, inl)
            ds?.let {
                r.addDataset(it)
                count++
            }
        }
        // Search the vault (and, for apps, remember the choice).
        val searchTitle = if (count == 0) "搜索 NyaPassword" else "搜索其他条目…"
        val sender = AutofillActivity.sender(context, AutofillActivity.MODE_PICK, target, specs)
        val sp = presentation(context, searchTitle, target.label, R.drawable.ic_autofill_search)
        val sb = Dataset.Builder(sp)
        screen.allIds.forEach { sb.setValue(it, null, sp) }
        sb.setAuthentication(sender)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            inline(context, spec(count), searchTitle, target.label, R.drawable.ic_autofill_search)?.let { sb.setInlinePresentation(it) }
        }
        r.addDataset(sb.build())
        saveInfo(screen)?.let { r.setSaveInfo(it) }
        return r.build()
    }

    /** Offer to save when the screen has a password field. */
    fun saveInfo(screen: ParsedScreen): SaveInfo? {
        val pw = screen.idsOf(Role.PASSWORD)
        if (pw.isEmpty()) return null
        val user = screen.idsOf(Role.USERNAME)
        val type = SaveInfo.SAVE_DATA_TYPE_PASSWORD or (if (user.isNotEmpty()) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0)
        val b = SaveInfo.Builder(type, pw.toTypedArray())
        if (user.isNotEmpty()) b.setOptionalIds(user.toTypedArray())
        b.setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
        return b.build()
    }

    fun appLabel(context: Context, pkg: String): String = runCatching {
        val pm: PackageManager = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

}
