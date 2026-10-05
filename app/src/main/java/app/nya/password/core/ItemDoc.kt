package app.nya.password.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Item content being edited, as the JSON tree the core sent. Every change
 * replaces only the keys it is about, so keys this version does not know
 * (written by a newer client, at any depth: item, field, URL, passkey…) are
 * written back untouched. Immutable: each edit returns a new document, which
 * suits Compose state.
 */
class ItemDoc(val root: JsonObject) {
    constructor(json: String) : this(PlainJson.parseToJsonElement(json).jsonObject)

    fun toJson(): String = root.toString()

    fun str(key: String): String = (root[key] as? JsonPrimitive)?.contentOrNull ?: ""
    fun bool(key: String): Boolean = (root[key] as? JsonPrimitive)?.contentOrNull == "true"
    fun array(key: String): List<JsonObject> = (root[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    val title: String get() = str("title")
    val template: String get() = str("template")
    val notes: String get() = str("notes")
    val favorite: Boolean get() = bool("favorite")
    val archived: Boolean get() = bool("archived")
    val tags: List<String> get() = (root["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    val fields: List<JsonObject> get() = array("fields")
    val urls: List<JsonObject> get() = array("urls")
    val sections: List<JsonObject> get() = array("sections")

    /** Sets (or, with null, removes) a top-level key. */
    fun with(key: String, value: JsonElement?): ItemDoc {
        val m = LinkedHashMap(root)
        if (value == null) m.remove(key) else m[key] = value
        return ItemDoc(JsonObject(m))
    }

    fun withTitle(t: String) = with("title", JsonPrimitive(t))
    fun withNotes(n: String) = with("notes", JsonPrimitive(n))
    fun withFavorite(b: Boolean) = with("favorite", JsonPrimitive(b))
    fun withArchived(b: Boolean) = with("archived", JsonPrimitive(b))
    fun withTags(t: List<String>) = with("tags", JsonArray(t.map { JsonPrimitive(it) }))

    /** The `autofill.never` switch; other keys of `autofill` stay. */
    fun withAutofillNever(never: Boolean): ItemDoc {
        val af = (root["autofill"] as? JsonObject) ?: JsonObject(emptyMap())
        return with("autofill", patch(af, "never", JsonPrimitive(never)))
    }

    val autofillNever: Boolean
        get() = ((root["autofill"] as? JsonObject)?.get("never") as? JsonPrimitive)?.contentOrNull == "true"

    // ---------------------------------------------------------------- arrays of objects with ids

    private fun withArray(key: String, items: List<JsonObject>) = with(key, JsonArray(items))

    /** Replaces one key of the element of `key` whose id is [id]. */
    fun patchIn(key: String, id: String, prop: String, value: JsonElement?): ItemDoc =
        withArray(key, array(key).map { if (idOf(it) == id) patch(it, prop, value) else it })

    fun removeFrom(key: String, id: String): ItemDoc = withArray(key, array(key).filter { idOf(it) != id })

    fun appendTo(key: String, element: JsonObject): ItemDoc = withArray(key, array(key) + element)

    /** Moves the element with [id] one place up (-1) or down (+1). */
    fun move(key: String, id: String, delta: Int): ItemDoc {
        val list = array(key).toMutableList()
        val i = list.indexOfFirst { idOf(it) == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return this
        val e = list.removeAt(i)
        list.add(j, e)
        return withArray(key, list)
    }

    fun field(id: String): JsonObject? = fields.firstOrNull { idOf(it) == id }

    fun withFieldValue(id: String, value: String) = patchIn("fields", id, "value", JsonPrimitive(value))
    fun withFieldValue(id: String, value: JsonElement) = patchIn("fields", id, "value", value)
    fun withFieldLabel(id: String, label: String) = patchIn("fields", id, "label", JsonPrimitive(label))
    fun withFieldKind(id: String, kind: String) = patchIn("fields", id, "kind", JsonPrimitive(kind))
    fun withFieldSection(id: String, section: String?) = patchIn("fields", id, "section", section?.let { JsonPrimitive(it) })
    fun withoutField(id: String) = removeFrom("fields", id)
    fun withField(f: JsonObject) = appendTo("fields", f)

    fun withUrl(id: String, url: String) = patchIn("urls", id, "url", JsonPrimitive(url))
    fun withUrlMatch(id: String, match: String) = patchIn("urls", id, "match", JsonPrimitive(match))
    fun withoutUrl(id: String) = removeFrom("urls", id)
    fun withNewUrl(id: String, url: String = "", match: String = "domain") = appendTo(
        "urls",
        buildJsonObject {
            put("id", id)
            put("url", url)
            put("match", match)
        },
    )

    fun withSection(id: String, label: String) = appendTo(
        "sections",
        buildJsonObject {
            put("id", id)
            put("label", label)
        },
    )

    fun withSectionLabel(id: String, label: String) = patchIn("sections", id, "label", JsonPrimitive(label))

    /** Removes a section; its fields move to the main group. */
    fun withoutSection(id: String): ItemDoc {
        var d = removeFrom("sections", id)
        for (f in d.fields) if ((f["section"] as? JsonPrimitive)?.contentOrNull == id) d = d.withFieldSection(idOf(f), null)
        return d
    }

    fun withoutPasskey(id: String) = removeFrom("passkeys", id)
    fun withoutAttachment(id: String) = removeFrom("attachments", id)

    /** Drops URL entries left empty in the editor. */
    fun withoutEmptyUrls(): ItemDoc = withArray("urls", urls.filter { ((it["url"] as? JsonPrimitive)?.contentOrNull ?: "").isNotBlank() })

    companion object {
        fun idOf(o: JsonObject): String = (o["id"] as? JsonPrimitive)?.contentOrNull ?: ""

        fun patch(o: JsonObject, key: String, value: JsonElement?): JsonObject {
            val m = LinkedHashMap(o)
            if (value == null) m.remove(key) else m[key] = value
            return JsonObject(m)
        }

        /** A new field object (what the web editor's "add field" creates). */
        fun newField(id: String, label: String, kind: String, multiline: Boolean = false, purpose: String? = null): JsonObject =
            buildJsonObject {
                put("id", id)
                put("label", label)
                put("kind", kind)
                if (purpose != null) put("purpose", purpose)
                put("value", if (kind == "address") JsonObject(emptyMap()) else JsonPrimitive(""))
                if (multiline) put("multiline", true)
            }

        fun text(o: JsonObject, key: String): String = when (val v = o[key]) {
            null, JsonNull -> ""
            is JsonPrimitive -> v.contentOrNull ?: ""
            else -> v.toString()
        }
    }
}
