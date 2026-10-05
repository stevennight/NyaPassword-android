package app.nya.password.core

/**
 * "使用前需要验证" (item `reprompt`, like Bitwarden's master password
 * re-prompt): a guard against someone using the unlocked phone. The item is
 * encrypted like any other; the app hides its secrets until the user verifies
 * (biometrics or the master password).
 *
 * - Item detail: the verification is for one item and lasts while it stays
 *   open; opening another item or locking ends it ([Vault.verifiedItem]).
 * - Autofill: datasets of such items carry no values and authenticate through
 *   AutofillActivity first.
 * - Credential provider: verifies the user on every use anyway.
 */
object Reprompt {
    fun key(vaultId: String, itemId: String): String = "$vaultId/$itemId"

    /** The item's secrets stay hidden: it asks for verification and was not verified since it was opened. */
    fun gated(reprompt: Boolean, vaultId: String, itemId: String, verified: String?): Boolean =
        reprompt && verified != key(vaultId, itemId)

    /** The verification that survives opening an item: kept for the same item, dropped for any other. */
    fun afterOpen(verified: String?, vaultId: String, itemId: String): String? =
        verified?.takeIf { it == key(vaultId, itemId) }
}
