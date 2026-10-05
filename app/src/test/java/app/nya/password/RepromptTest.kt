package app.nya.password

import app.nya.password.core.Content
import app.nya.password.core.ItemDoc
import app.nya.password.core.ItemView
import app.nya.password.core.PasskeyCandidate
import app.nya.password.core.PlainJson
import app.nya.password.core.Reprompt
import app.nya.password.core.decode
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "使用前需要验证" (item `reprompt`): gating rules and the JSON the core exchanges. */
class RepromptTest {
    @Test
    fun gatesOnlyItemsThatAsk() {
        assertFalse(Reprompt.gated(false, "v", "a", null))
        assertTrue(Reprompt.gated(true, "v", "a", null))
        assertFalse(Reprompt.gated(true, "v", "a", Reprompt.key("v", "a")))
        assertTrue(Reprompt.gated(true, "v", "b", Reprompt.key("v", "a")), "verified for another item")
        assertTrue(Reprompt.gated(true, "w", "a", Reprompt.key("v", "a")), "same id in another vault")
    }

    @Test
    fun openingAnotherItemEndsTheVerification() {
        val a = Reprompt.key("v", "a")
        assertEquals(a, Reprompt.afterOpen(a, "v", "a"), "back from the editor: still verified")
        assertNull(Reprompt.afterOpen(a, "v", "b"))
        assertNull(Reprompt.afterOpen(null, "v", "a"))
        // a → b → a: verify again
        assertTrue(Reprompt.gated(true, "v", "a", Reprompt.afterOpen(Reprompt.afterOpen(a, "v", "b"), "v", "a")))
    }

    @Test
    fun viewsCarryTheFlag() {
        val v: List<ItemView> = decode("""[{"vault_id":"v","item_id":"a","title":"Bank","reprompt":true},{"vault_id":"v","item_id":"b"}]""")
        assertTrue(v[0].reprompt)
        assertFalse(v[1].reprompt, "older cores leave it out")
        val p: List<PasskeyCandidate> = decode("""[{"vault_id":"v","item_id":"a","passkey_id":"pk","reprompt":true}]""")
        assertTrue(p[0].reprompt)
        val c = Content.of(PlainJson.parseToJsonElement("""{"format":"1.0","template":"login","reprompt":true}""").jsonObject)
        assertTrue(c.reprompt)
    }

    @Test
    fun editorTogglesTheKeyAndKeepsTheRest() {
        val json = """{"format":"1.0","template":"login","title":"Bank","future":{"x":1},"fields":[{"id":"password","kind":"concealed","value":"p","future_field":2}]}"""
        val on = ItemDoc(json).withReprompt(true)
        assertTrue(on.reprompt)
        val o = PlainJson.parseToJsonElement(on.toJson()).jsonObject
        assertEquals("true", o["reprompt"]!!.jsonPrimitive.content)
        assertEquals(PlainJson.parseToJsonElement("""{"x":1}"""), o["future"])
        val off = on.withReprompt(false)
        assertFalse(off.reprompt)
        // left out when off, as the core writes it
        assertFalse(PlainJson.parseToJsonElement(off.toJson()).jsonObject.containsKey("reprompt"))
        assertEquals(PlainJson.parseToJsonElement(json), PlainJson.parseToJsonElement(off.toJson()))
    }
}
