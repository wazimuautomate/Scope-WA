package com.tricreta.scopewa.brain.whatsapp

import java.net.URLEncoder

/**
 * Builds the `wa.me` deep link used to open a chat with a prefilled message —
 * architecture doc section 5.1. This is a documented WhatsApp link format, not
 * a private API, which is why the send path uses it rather than driving the
 * contact picker by hand.
 *
 * Pure JVM code, no Android imports, so it is unit tested in CI.
 */
object WaDeepLink {

    private const val BASE = "https://wa.me/"

    /**
     * @param e164 phone number in `+2547…` form, as produced by
     *   [com.tricreta.scopewa.brain.phone.PhoneNormalizer]. The `+` and any
     *   separators are stripped — wa.me wants bare digits with the country code.
     * @param prefilledText message body to drop into the compose box. Already
     *   rendered per-recipient by the template engine; pass it verbatim.
     * @throws IllegalArgumentException if [e164] contains no digits, because a
     *   deep link without a number silently opens WhatsApp's contact list and
     *   would look to the job runner like a successful send.
     */
    fun chatUrl(e164: String, prefilledText: String? = null): String {
        val digits = e164.filter { it.isDigit() }
        require(digits.isNotEmpty()) { "Cannot build a wa.me link from a number with no digits: '$e164'" }

        return if (prefilledText.isNullOrEmpty()) {
            "$BASE$digits"
        } else {
            "$BASE$digits?text=${encode(prefilledText)}"
        }
    }

    /**
     * `URLEncoder` is form-encoding, which turns a space into `+`. In a URL
     * *query value* WhatsApp renders `+` literally, so a message would arrive
     * with plus signs between every word. Convert to `%20` to get a real space.
     */
    private fun encode(text: String): String =
        URLEncoder.encode(text, Charsets.UTF_8.name()).replace("+", "%20")
}
