package app.navilink.live

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/*
 * The phone identity Smartphone Link 2.11.12 gives the nüvi in its /auth-info answer
 * (access/bt/server/handlers/a.java). The nüvi uses it to sign the Live Services requests it sends through the phone.
 *  - device-identifier: a random UUID kept for the install (framework/util/Installation)
 *  - client-generated-token: |hashCode| of a second random UUID string (api/btlink/util/c.java)
 *  - app-hash: base64(sha1("<CRC32 of classes.dex><device-identifier><unix seconds>")) (PhoneLinkApp.r)
 * SPL also sends account-id (a signed-in Google/Garmin account); it is left out here, so no account is involved.
 * The same scheme registered fine with gold.garmin.com in probe/auth.py (2026-10-30).
 */
const val SPL_VERSION = "2.11.12"
const val SPL_DEX_CRC = 561418213L
const val SPL_CLIENT_TYPE = "105"

data class Identity(val deviceId: String, val tokenSeed: String) {
    val token: Int get() = Math.abs(tokenSeed.hashCode()).let { if (it < 0) 0 else it }

    fun appHash(unixSeconds: Long): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest("$SPL_DEX_CRC$deviceId$unixSeconds".toByteArray()))

    /** The /auth-info answer text, field for field as SPL builds it (map joined with &, then subscription-token). */
    fun authInfoText(unixSeconds: Long, androidRelease: String): String {
        val m = linkedMapOf(
            "device-identifier" to deviceId,
            "client-generated-token" to token.toString(),
            "application-version" to SPL_VERSION,
            "app-hash" to appHash(unixSeconds),
            "device-type" to "Android$androidRelease",
            "operating-system" to "Android",
            "client-type-id" to SPL_CLIENT_TYPE,
        )
        @Suppress("DEPRECATION")
        return m.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v)}" } + "\r\nsubscription-token=0\r\n"
    }

    companion object {
        fun fresh() = Identity(UUID.randomUUID().toString(), UUID.randomUUID().toString())
    }
}
