package app.nya.password.autofill

import android.app.assist.AssistStructure
import android.os.Build
import android.view.View
import android.view.autofill.AutofillId

/** A screen asking for autofill, reduced to fillable views and their roles. */
class ParsedScreen(
    val packageName: String,
    /** The page's domain when the views are web content (browser or WebView). */
    val webDomain: String?,
    val webScheme: String?,
    val views: List<ViewDesc>,
    val ids: List<AutofillId>,
    /** Current text of each view (for saving). */
    val values: List<String?>,
    val classification: Classification,
) {
    fun idsOf(role: Role): List<AutofillId> = when (role) {
        Role.USERNAME -> classification.username
        Role.PASSWORD -> classification.password
        Role.OTP -> classification.otp
    }.map { ids[it] }

    fun valueOf(role: Role): String? = when (role) {
        Role.USERNAME -> classification.username
        Role.PASSWORD -> classification.password
        Role.OTP -> classification.otp
    }.firstNotNullOfOrNull { values[it]?.takeIf { v -> v.isNotEmpty() } }

    val allIds: List<AutofillId>
        get() = (classification.username + classification.password + classification.otp).map { ids[it] }
}

object StructureParser {
    fun parse(structure: AssistStructure): ParsedScreen {
        val views = ArrayList<ViewDesc>()
        val ids = ArrayList<AutofillId>()
        val values = ArrayList<String?>()
        val domains = HashMap<Int, Pair<String, String?>>()
        var firstDomain: Pair<String, String?>? = null

        fun visit(node: AssistStructure.ViewNode, inherited: Pair<String, String?>?) {
            val domain = node.webDomain?.takeIf { it.isNotBlank() }?.let { d ->
                d to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.webScheme else null)
            } ?: inherited
            if (domain != null && firstDomain == null) firstDomain = domain
            val id = node.autofillId
            if (id != null && node.autofillType != View.AUTOFILL_TYPE_NONE) {
                val html = node.htmlInfo
                val attrs = HashMap<String, String>()
                html?.attributes?.forEach { p -> if (p.first != null && p.second != null) attrs[p.first.lowercase()] = p.second }
                val index = views.size
                views += ViewDesc(
                    index = index,
                    autofillHints = node.autofillHints?.toList().orEmpty(),
                    inputType = node.inputType,
                    idEntry = node.idEntry,
                    hint = node.hint,
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    htmlTag = html?.tag,
                    htmlAttrs = attrs,
                    className = node.className,
                    autofillType = node.autofillType,
                    visible = node.visibility == View.VISIBLE,
                    focused = node.isFocused,
                )
                ids += id
                values += node.autofillValue?.takeIf { it.isText }?.textValue?.toString()
                if (domain != null) domains[index] = domain
            }
            for (i in 0 until node.childCount) visit(node.getChildAt(i), domain)
        }

        for (w in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(w).rootViewNode, null)

        val cls = FieldClassifier.classify(views)
        // the domain of the login fields themselves (an iframe's own domain), else the page's
        val keyIndex = (cls.password + cls.username + cls.otp).firstOrNull()
        val d = keyIndex?.let { domains[it] } ?: firstDomain
        return ParsedScreen(
            packageName = structure.activityComponent?.packageName.orEmpty(),
            webDomain = d?.first,
            webScheme = d?.second,
            views = views,
            ids = ids,
            values = values,
            classification = cls,
        )
    }
}
