package app.nya.password

import app.nya.password.core.Content
import app.nya.password.core.ItemDoc
import app.nya.password.core.PlainJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Editing item content as a JSON tree keeps every key this version does not know. */
class ItemDocTest {
    private val json = """
        {
          "format": "1.7", "template": "login", "title": "GitHub", "future_top": {"nested": [1, 2, {"x": true}]},
          "fields": [
            {"id": "username", "label": "用户名", "kind": "text", "purpose": "username", "value": "octocat", "future_field": "keep me"},
            {"id": "password", "label": "密码", "kind": "concealed", "purpose": "password", "value": "old", "generator": {"length": 24}},
            {"id": "f_1", "label": "恢复码", "kind": "concealed", "multiline": true, "section": "s_1", "value": "a\nb"},
            {"id": "f_2", "label": "未来", "kind": "hologram", "value": {"shape": "cube"}}
          ],
          "sections": [{"id": "s_1", "label": "恢复", "future_section": 1}],
          "urls": [{"id": "u1", "url": "https://github.com", "match": "domain", "future_url": [true]}],
          "passkeys": [{"id": "pk1", "rp_id": "github.com", "credential_id": "AA", "private_key": "BB", "future_pk": "x"}],
          "autofill": {"never": false, "future_af": 7},
          "notes": "hello"
        }
    """.trimIndent()

    private fun tree(d: ItemDoc): JsonObject = PlainJson.parseToJsonElement(d.toJson()).jsonObject

    private fun field(o: JsonObject, id: String) = o["fields"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == id }

    @Test
    fun editsKeepUnknownKeysEverywhere() {
        val d = ItemDoc(json)
            .withTitle("GitHub (work)")
            .withFieldValue("password", "new secret")
            .withUrl("u1", "https://github.com/login")
            .withUrlMatch("u1", "host")
            .withAutofillNever(true)
            .withNotes("bye")
            .withTags(listOf("工作/开发"))
        val o = tree(d)
        assertEquals("GitHub (work)", o["title"]!!.jsonPrimitive.content)
        assertEquals(PlainJson.parseToJsonElement("""{"nested": [1, 2, {"x": true}]}"""), o["future_top"])
        assertEquals("keep me", field(o, "username")["future_field"]!!.jsonPrimitive.content)
        val pw = field(o, "password")
        assertEquals("new secret", pw["value"]!!.jsonPrimitive.content)
        assertEquals(24, pw["generator"]!!.jsonObject["length"]!!.jsonPrimitive.content.toInt())
        assertEquals("cube", field(o, "f_2")["value"]!!.jsonObject["shape"]!!.jsonPrimitive.content)
        assertEquals("hologram", field(o, "f_2")["kind"]!!.jsonPrimitive.content)
        val u = o["urls"]!!.jsonArray[0].jsonObject
        assertEquals("https://github.com/login", u["url"]!!.jsonPrimitive.content)
        assertEquals("host", u["match"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive(true))), u["future_url"])
        assertEquals("x", o["passkeys"]!!.jsonArray[0].jsonObject["future_pk"]!!.jsonPrimitive.content)
        val af = o["autofill"]!!.jsonObject
        assertEquals("true", af["never"]!!.jsonPrimitive.content)
        assertEquals("7", af["future_af"]!!.jsonPrimitive.content)
        assertEquals(1, o["sections"]!!.jsonArray[0].jsonObject["future_section"]!!.jsonPrimitive.content.toInt())
        assertEquals("1.7", o["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun untouchedDocumentIsUnchanged() {
        assertEquals(PlainJson.parseToJsonElement(json), tree(ItemDoc(json)))
    }

    @Test
    fun fieldsAddRemoveMove() {
        var d = ItemDoc(json)
        d = d.withField(ItemDoc.newField("f_new", "PIN", "pin"))
        assertEquals(listOf("username", "password", "f_1", "f_2", "f_new"), d.fields.map { ItemDoc.idOf(it) })
        d = d.move("fields", "f_new", -1)
        assertEquals("f_new", ItemDoc.idOf(d.fields[3]))
        d = d.move("fields", "username", -1) // already first
        assertEquals("username", ItemDoc.idOf(d.fields[0]))
        d = d.withoutField("f_2")
        assertEquals(4, d.fields.size)
        val addr = ItemDoc.newField("f_a", "地址", "address")
        assertTrue(addr["value"] is JsonObject)
    }

    @Test
    fun removingASectionKeepsItsFields() {
        val d = ItemDoc(json).withoutSection("s_1")
        assertTrue(d.sections.isEmpty())
        val f = d.field("f_1")!!
        assertNull(f["section"])
        assertEquals("a\nb", ItemDoc.text(f, "value"))
    }

    @Test
    fun emptyUrlsAreDroppedOnSave() {
        val d = ItemDoc(json).withNewUrl("u2").withNewUrl("u3", "androidapp://com.example", "exact").withoutEmptyUrls()
        assertEquals(listOf("u1", "u3"), d.urls.map { ItemDoc.idOf(it) })
    }

    @Test
    fun readSideIgnoresUnknownKeys() {
        val c = Content.of(PlainJson.parseToJsonElement(json).jsonObject)
        assertEquals("octocat", c.username)
        assertEquals("old", c.password)
        assertEquals(4, c.fields.size)
        assertTrue(c.fields.first { it.id == "f_1" }.multiline)
        assertEquals("恢复", c.sections[0].label)
        assertEquals(1, c.passkeys.size)
        assertEquals("""{"shape":"cube"}""", c.fields.first { it.id == "f_2" }.text)
    }
}
