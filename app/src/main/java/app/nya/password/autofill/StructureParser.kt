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
    /** All nodes in the structure, fillable or not (diagnostics). */
    val nodeCount: Int = 0,
    /** Built by the system from a browser's accessibility tree ([StructureParser.parse]). */
    val compatMode: Boolean = false,
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
    /**
     * [compatMode]: the structure was built by the system from the browser's
     * accessibility tree. There an input only counts as a text field once it
     * has text, so empty page inputs come with AUTOFILL_TYPE_NONE; editable
     * nodes (EditText) are taken as text fields anyway.
     */
    fun parse(structure: AssistStructure, compatMode: Boolean = false): ParsedScreen {
        val views = ArrayList<ViewDesc>()
        val ids = ArrayList<AutofillId>()
        val values = ArrayList<String?>()
        val domains = HashMap<Int, Pair<String, String?>>()
        var firstDomain: Pair<String, String?>? = null
        var nodeCount = 0
        val packageName = structure.activityComponent?.packageName.orEmpty()
        val urlBarId = UrlBars.idOf(packageName)
        var urlBarDomain: Pair<String, String?>? = null

        fun visit(node: AssistStructure.ViewNode, inherited: Pair<String, String?>?) {
            nodeCount++
            val domain = node.webDomain?.takeIf { it.isNotBlank() }?.let { d ->
                d to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.webScheme else null)
            } ?: inherited
            if (domain != null && firstDomain == null) firstDomain = domain
            // Compatibility mode: the browser's own address bar (not a page field, not filled).
            // Its resource id must belong to the browser package: page elements may carry
            // their HTML id as a view id, but never a package.
            val isUrlBar = urlBarId != null && node.idEntry == urlBarId && node.idPackage == packageName
            if (isUrlBar && urlBarDomain == null) urlBarDomain = UrlBars.domainOf(node.text?.toString())
            val id = node.autofillId
            val emptyCompatInput = compatMode && node.autofillType == View.AUTOFILL_TYPE_NONE &&
                node.className?.endsWith("EditText") == true
            if (id != null && (node.autofillType != View.AUTOFILL_TYPE_NONE || emptyCompatInput) && !isUrlBar) {
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
                    autofillType = if (emptyCompatInput) View.AUTOFILL_TYPE_TEXT else node.autofillType,
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

        val cls = FieldClassifier.classify(views, guessFocused = compatMode)
        // the domain of the login fields themselves (an iframe's own domain), else the page's
        val keyIndex = (cls.password + cls.username + cls.otp).firstOrNull()
        val d = keyIndex?.let { domains[it] } ?: firstDomain ?: urlBarDomain
        return ParsedScreen(
            packageName = packageName,
            webDomain = d?.first,
            webScheme = d?.second,
            views = views,
            ids = ids,
            values = values,
            classification = cls,
            nodeCount = nodeCount,
            compatMode = compatMode,
        )
    }
}
