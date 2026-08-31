package com.clearcmos.kata.api

import android.content.Context
import android.util.Log
import java.io.File
import java.security.SecureRandom

/**
 * The bearer token for the control API.
 *
 * Loopback binding stops anything off-device, but every app on the phone can also reach
 * loopback, and this API can rewrite secure settings. The token closes that gap.
 *
 * It is mirrored into the app's external files directory because `adb shell` can read that
 * path while other apps cannot, which gives the CLI a way to fetch it with no user step and no
 * debuggable build.
 */
object ApiToken {
    private const val TAG = "KataToken"
    private const val FILENAME = "api-token"

    fun get(context: Context): String {
        val primary = File(context.filesDir, FILENAME)
        val token =
            if (primary.exists()) {
                primary.readText().trim()
            } else {
                generate().also { primary.writeText(it) }
            }
        mirror(context, token)
        return token
    }

    private fun generate(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun mirror(context: Context, token: String) {
        val external = context.getExternalFilesDir(null) ?: return
        runCatching {
            val file = File(external, FILENAME)
            if (!file.exists() || file.readText().trim() != token) file.writeText(token)
        }.onFailure { Log.w(TAG, "could not mirror token for adb", it) }
    }
}
